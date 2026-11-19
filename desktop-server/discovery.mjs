// 局域网发现：用 mDNS（DNS-SD）广播电脑服务，电视端用系统自带的发现功能就能找到，不必手填地址。
// 只应答对本服务类型的查询；不依赖第三方库，也不需要系统安装 Bonjour。
import dgram from 'node:dgram';
import { hostname, networkInterfaces } from 'node:os';

export const SERVICE_TYPE = '_supertv-hub._tcp.local';
const GROUP = '224.0.0.251', MDNS_PORT = 5353, TTL = 120;
const TYPE = { A: 1, PTR: 12, TXT: 16, SRV: 33 };

/** 域名编码为 DNS 标签序列（不做压缩；包很小） */
export function encodeName(name) {
  const parts = name.split('.').filter(Boolean).map((label) => {
    const bytes = Buffer.from(label, 'utf8');
    if (bytes.length > 63) throw new Error('域名标签过长');
    return Buffer.concat([Buffer.from([bytes.length]), bytes]);
  });
  return Buffer.concat([...parts, Buffer.from([0])]);
}
/** 从报文里读一个域名（支持压缩指针），返回 [域名, 下一个偏移] */
export function decodeName(packet, offset) {
  const labels = []; let next = -1, jumps = 0, at = offset;
  for (;;) {
    if (at >= packet.length) throw new Error('报文不完整');
    const length = packet[at];
    if (length === 0) { at++; break; }
    if ((length & 0xc0) === 0xc0) {
      if (at + 1 >= packet.length || ++jumps > 16) throw new Error('域名指针无效');
      if (next < 0) next = at + 2;
      at = ((length & 0x3f) << 8) | packet[at + 1];
      continue;
    }
    if (length > 63 || at + 1 + length > packet.length) throw new Error('域名标签无效');
    labels.push(packet.toString('utf8', at + 1, at + 1 + length)); at += 1 + length;
  }
  return [labels.join('.'), next < 0 ? at : next];
}
/** 解析查询报文里的问题；不是查询或格式不对时返回空 */
export function parseQuestions(packet) {
  try {
    if (packet.length < 12 || packet.readUInt16BE(2) & 0x8000) return [];
    const count = Math.min(packet.readUInt16BE(4), 32), questions = [];
    let offset = 12;
    for (let i = 0; i < count; i++) {
      const [name, next] = decodeName(packet, offset);
      if (next + 4 > packet.length) return questions;
      questions.push({ name: name.toLowerCase(), type: packet.readUInt16BE(next), unicast: (packet.readUInt16BE(next + 2) & 0x8000) !== 0 });
      offset = next + 4;
    }
    return questions;
  } catch { return []; }
}
function record(name, type, data, flush = true) {
  const head = Buffer.alloc(10);
  head.writeUInt16BE(type, 0); head.writeUInt16BE(flush ? 0x8001 : 0x0001, 2); head.writeUInt32BE(TTL, 4); head.writeUInt16BE(data.length, 8);
  return Buffer.concat([encodeName(name), head, data]);
}
/** 一个完整的应答：PTR（类型 → 实例）、SRV（实例 → 主机和端口）、TXT、A（主机 → 地址） */
export function buildAnnouncement({ instance, host, port, address, txt = {} }) {
  const service = `${instance}.${SERVICE_TYPE}`, target = `${host}.local`;
  const srv = Buffer.concat([Buffer.from([0, 0, 0, 0, port >> 8, port & 0xff]), encodeName(target)]);
  const entries = Object.entries(txt).map(([k, v]) => { const b = Buffer.from(`${k}=${v}`, 'utf8'); return Buffer.concat([Buffer.from([b.length]), b.subarray(0, 255)]); });
  const records = [
    record(SERVICE_TYPE, TYPE.PTR, encodeName(service), false),
    record(service, TYPE.SRV, srv),
    record(service, TYPE.TXT, entries.length ? Buffer.concat(entries) : Buffer.from([0])),
    record(target, TYPE.A, Buffer.from(address.split('.').map(Number))),
  ];
  const header = Buffer.alloc(12);
  header.writeUInt16BE(0x8400, 2); header.writeUInt16BE(records.length, 6);
  return Buffer.concat([header, ...records]);
}
/** 可供电视连接的本机 IPv4 地址（排除回环和代理的虚拟网卡 198.18.0.0/15） */
export function lanAddresses(interfaces = networkInterfaces()) {
  return Object.values(interfaces).flat().filter((a) => a && a.family === 'IPv4' && !a.internal && !/^198\.1[89]\./.test(a.address) && !/^169\.254\./.test(a.address)).map((a) => a.address);
}

/** 开始广播；失败（端口被占、没有网络）只影响自动发现，服务照常可用。返回停止函数 */
export function advertise({ port, name = hostname(), txt = {}, log = () => {} }) {
  const label = name.replace(/\.(local|lan|home)$/i, '').replace(/[^\p{L}\p{N} _-]/gu, '').slice(0, 40) || 'PC';
  const host = (label.replace(/[^A-Za-z0-9-]/g, '-').replace(/^-+|-+$/g, '') || 'supertv-pc').slice(0, 40);
  const sockets = [];
  for (const address of lanAddresses()) {
    const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    const packet = () => buildAnnouncement({ instance: `SuperTV ${label}`, host, port, address, txt });
    const announce = () => socket.send(packet(), MDNS_PORT, GROUP, () => {});
    socket.on('error', (error) => { log(`自动发现不可用（${address}）：${error.code || error.message}`); try { socket.close(); } catch { /* 已关闭 */ } });
    socket.on('message', (message, remote) => {
      const wanted = parseQuestions(message).filter((q) => q.name === SERVICE_TYPE && [TYPE.PTR, 255].includes(q.type));
      if (!wanted.length) return;
      // 单播查询直接回给对方，其余按规范回到组播地址
      if (wanted.some((q) => q.unicast) && remote.port !== MDNS_PORT) socket.send(packet(), remote.port, remote.address, () => {});
      else announce();
    });
    socket.bind(MDNS_PORT, () => {
      try { socket.setMulticastInterface(address); socket.addMembership(GROUP, address); socket.setMulticastTTL(255); } catch (error) { log(`自动发现不可用（${address}）：${error.code || error.message}`); return; }
      announce(); const timers = [setTimeout(announce, 1000), setTimeout(announce, 3000), setInterval(announce, 60_000)];
      timers.forEach((t) => t.unref?.()); socket.timers = timers;
    });
    sockets.push(socket);
  }
  return () => { for (const socket of sockets) { (socket.timers || []).forEach((t) => { clearTimeout(t); clearInterval(t); }); try { socket.close(); } catch { /* 已关闭 */ } } };
}
