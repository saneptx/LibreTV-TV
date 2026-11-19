import assert from 'node:assert/strict';
import test from 'node:test';
import { buildAnnouncement, decodeName, encodeName, lanAddresses, parseQuestions, SERVICE_TYPE } from './discovery.mjs';

function query(name, type = 12, unicast = false) {
  const header = Buffer.alloc(12); header.writeUInt16BE(1, 4);
  const tail = Buffer.alloc(4); tail.writeUInt16BE(type, 0); tail.writeUInt16BE(unicast ? 0x8001 : 1, 2);
  return Buffer.concat([header, encodeName(name), tail]);
}
test('域名编码、解码与压缩指针', () => {
  const encoded = encodeName('SuperTV 书房.' + SERVICE_TYPE);
  assert.deepEqual(decodeName(encoded, 0), ['SuperTV 书房.' + SERVICE_TYPE, encoded.length]);
  const packet = Buffer.concat([encodeName('a.local'), Buffer.from([1, 0x62, 0xc0, 0x00])]);
  assert.deepEqual(decodeName(packet, 9), ['b.a.local', 13]);
  assert.throws(() => decodeName(Buffer.from([0xc0, 0x00]), 0));   // 指向自己的指针
  assert.throws(() => encodeName('x'.repeat(64) + '.local'));
});
test('只认对本服务的查询', () => {
  assert.deepEqual(parseQuestions(query('_SuperTV-Hub._tcp.local')), [{ name: SERVICE_TYPE, type: 12, unicast: false }]);
  assert.equal(parseQuestions(query(SERVICE_TYPE, 12, true))[0].unicast, true);
  const response = query(SERVICE_TYPE); response.writeUInt16BE(0x8400, 2);
  assert.deepEqual(parseQuestions(response), []);
  assert.deepEqual(parseQuestions(Buffer.from([1, 2, 3])), []);
  assert.deepEqual(parseQuestions(Buffer.concat([Buffer.alloc(4), Buffer.from([0, 5]), Buffer.alloc(6), Buffer.from([0x3f])])), []);
});
test('应答里带着实例、端口和地址', () => {
  const packet = buildAnnouncement({ instance: 'SuperTV PC', host: 'pc', port: 8090, address: '192.168.1.14', txt: { protocol: 1 } });
  assert.equal(packet.readUInt16BE(2), 0x8400); assert.equal(packet.readUInt16BE(6), 4);
  let [name, offset] = decodeName(packet, 12);
  assert.equal(name, SERVICE_TYPE); assert.equal(packet.readUInt16BE(offset), 12);
  const length = packet.readUInt16BE(offset + 8);
  assert.equal(decodeName(packet, offset + 10)[0], 'SuperTV PC.' + SERVICE_TYPE);
  [name, offset] = decodeName(packet, offset + 10 + length);
  assert.equal(packet.readUInt16BE(offset), 33); assert.equal(packet.readUInt16BE(offset + 14), 8090);
  assert.equal(decodeName(packet, offset + 16)[0], 'pc.local');
  assert.deepEqual([...packet.subarray(packet.length - 4)], [192, 168, 1, 14]);
  assert.ok(packet.includes(Buffer.from('protocol=1')));
});
test('只广播电视能连的地址', () => {
  assert.deepEqual(lanAddresses({ lo: [{ family: 'IPv4', internal: true, address: '127.0.0.1' }], wifi: [{ family: 'IPv4', internal: false, address: '192.168.1.14' }, { family: 'IPv6', internal: false, address: 'fe80::1' }],
    proxy: [{ family: 'IPv4', internal: false, address: '198.18.0.1' }], auto: [{ family: 'IPv4', internal: false, address: '169.254.3.4' }] }), ['192.168.1.14']);
});
