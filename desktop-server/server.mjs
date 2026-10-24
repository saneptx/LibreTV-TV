import http from 'node:http';
import https from 'node:https';
import { lookup } from 'node:dns/promises';
import { isIP } from 'node:net';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { processedPlaylist } from './processing.mjs';

const uid = () => randomBytes(16).toString('hex');
const failure = (status, message) => Object.assign(new Error(message), { status });
export function privateAddress(value) {
  const ip = value.replace(/^\[|\]$/g, '').toLowerCase();
  if (ip.includes(':')) {
    if (ip.startsWith('::ffff:')) {
      const tail = ip.slice(7);
      if (tail.includes('.')) return privateAddress(tail);
      const parts = tail.split(':');
      if (parts.length === 2) { const n = parseInt(parts[0], 16) * 65536 + parseInt(parts[1], 16); return privateAddress([n >>> 24, n >>> 16 & 255, n >>> 8 & 255, n & 255].join('.')); }
    }
    // Only global unicast IPv6; reject local, multicast and translation/tunnel ranges.
    return !/^[23][0-9a-f]{3}:/.test(ip) || /^(2001:db8:|2001:0:|2002:)/.test(ip);
  }
  const [a, b] = ip.split('.').map(Number);
  return !isIP(ip) || a === 0 || a === 10 || a === 127 || a >= 224 || a === 169 && b === 254 ||
    a === 172 && b >= 16 && b <= 31 || a === 192 && [0, 168].includes(b) || a === 100 && b >= 64 && b <= 127 ||
    a === 198 && [18, 19, 51].includes(b) || a === 203 && b === 0;
}

/** URI attributes include variant/audio/subtitle playlists, AES keys and initialization maps. */
export function rewritePlaylist(body, base, asset) {
  if (!body.trimStart().startsWith('#EXTM3U')) throw failure(502, '片源返回的播放列表无效');
  if (/#EXT-X-(DEFINE|CONTENT-STEERING):/.test(body) || /\{\$/.test(body)) throw failure(422, '暂不支持带变量或内容转向的 HLS');
  return body.split(/\r?\n/).map((line) => {
    if (line.trim() && !line.trimStart().startsWith('#')) return asset(new URL(line.trim(), base).href);
    return line.replace(/\bURI="([^"]+)"/g, (_, uri) => `URI="${asset(new URL(uri, base).href)}"`);
  }).join('\n');
}

export function createDesktopServer({ token, allowTestOrigin, allowFakeIp = false, idleMs = 30 * 60_000, lookupHost = lookup, processor = null } = {}) {
  if (typeof token !== 'string' || token.length < 24 || token.length > 256) throw new Error('需要至少 24 字符的配对码');
  const secret = Buffer.from(`Bearer ${token}`), sessions = new Map();
  const stats = { created: 0, mediaRequests: 0, playlistRequests: 0, rangeRequests: 0, bytes: 0 };
  const testOrigin = allowTestOrigin ? new URL(allowTestOrigin).origin : null;
  function authorized(req) {
    const candidate = Buffer.from(req.headers.authorization || '');
    return candidate.length === secret.length && timingSafeEqual(candidate, secret);
  }
  async function destination(raw) {
    let url;
    try { url = new URL(raw); } catch { throw failure(400, '播放地址无效'); }
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw failure(400, '仅支持无账号的 HTTP/HTTPS 片源');
    const hostname = url.hostname.replace(/^\[|\]$/g, '');
    let addresses;
    try { addresses = isIP(hostname) ? [{ address: hostname, family: isIP(hostname) }] : await lookupHost(hostname, { all: true }); }
    catch { throw failure(502, '无法解析片源域名'); }
    const fakeIp = (ip) => /^198\.(18|19)\./.test(ip);
    if (url.origin !== testOrigin && !allowFakeIp && !isIP(hostname) && addresses.some((entry) => fakeIp(entry.address))) throw failure(403, '电脑 DNS 使用代理 Fake-IP；请在可信的本机代理环境中启用 --allow-fake-ip');
    if (!addresses.length || url.origin !== testOrigin && addresses.some((entry) => privateAddress(entry.address) && !(allowFakeIp && !isIP(hostname) && fakeIp(entry.address)))) throw failure(403, '不允许访问内网或保留地址片源');
    return { url, address: addresses[0] };
  }
  async function upstream(raw, headers, method, session, hop = 0) {
    if (hop > 5) throw failure(502, '片源重定向过多');
    const { url, address } = await destination(raw);
    if (session.closed) throw failure(410, '播放任务已关闭');
    const response = await new Promise((resolve, reject) => {
      const req = (url.protocol === 'https:' ? https : http).request(url, {
        method, headers: { 'User-Agent': 'Mozilla/5.0 LibreTV-Desktop/0.1', ...headers, 'Accept-Encoding': 'identity' },
        // Pin the validated address; a second DNS lookup must not bypass the private-address check.
        lookup: (_hostname, options, callback) => options.all ? callback(null, [address]) : callback(null, address.address, address.family),
      }, resolve);
      session.requests.add(req);
      req.once('close', () => session.requests.delete(req));
      req.once('error', reject);
      req.setTimeout(20_000, () => req.destroy(failure(504, '片源响应超时')));
      req.end();
    });
    if ([301, 302, 303, 307, 308].includes(response.statusCode)) {
      const location = response.headers.location; response.destroy();
      if (!location) throw failure(502, '片源缺少重定向地址');
      return upstream(new URL(location, url).href, headers, method, session, hop + 1);
    }
    return { response, url: url.href };
  }
  function closeSession(session) {
    session.closed = true; session.pipeline?.close();
    for (const req of session.requests) req.destroy();
    sessions.delete(session.id);
  }
  function addAsset(session, url) {
    const existing = session.byUrl.get(url);
    if (existing) return existing;
    if (session.assets.size >= 8192) throw failure(422, '播放列表包含过多资源');
    const ext = new URL(url).pathname.match(/\.[a-zA-Z0-9]{1,8}$/)?.[0] || '';
    const id = uid() + ext, path = `/v1/sessions/${session.id}/media/${id}`;
    session.assets.set(id, url); session.byUrl.set(url, path);
    return path;
  }
  const send = (res, status, value) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(value)); };
  const server = http.createServer(async (req, res) => {
    // The APK uses its native HTTP bridge; these origins also allow local browser development.
    if (['http://127.0.0.1:8080', 'http://localhost:8080'].includes(req.headers.origin)) {
      res.setHeader('Access-Control-Allow-Origin', req.headers.origin); res.setHeader('Vary', 'Origin');
      res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type');
      res.setHeader('Access-Control-Allow-Methods', 'GET, HEAD, POST, DELETE, OPTIONS');
      if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }
    }
    let abandoned;
    try {
      if (!authorized(req)) throw failure(401, '电脑服务配对码不匹配');
      const path = new URL(req.url, 'http://desktop.local').pathname;
      if (path === '/v1/status' && req.method === 'GET') {
        send(res, 200, { protocol: 1, service: 'LibreTV Desktop', processing: processor ? 'enhance' : 'relay', capabilities: processor ? { adRemoval: true, superResolution: true, superResolutionToggle: true, transcoding: true, caching: true, model: processor.tools.model, encoder: processor.tools.encoder, targetHeight: processor.tools.targetHeight, upscalePolicy: processor.tools.targetHeight === 'auto' ? 'next-tier' : 'fixed' } : { adRemoval: false, superResolution: false }, activeSessions: sessions.size, ...stats }); return;
      }
      if (path === '/v1/sessions' && req.method === 'POST') {
        let body = '';
        for await (const chunk of req) { body += chunk; if (Buffer.byteLength(body) > 32_768) throw failure(413, '请求过大'); }
        let input; try { input = JSON.parse(body); } catch { throw failure(400, '请求格式无效'); }
        if (!input || typeof input.url !== 'string' || input.superResolution !== undefined && typeof input.superResolution !== 'boolean' || input.position !== undefined && (!Number.isFinite(input.position) || input.position < 0)) throw failure(400, '播放参数无效');
        await destination(input.url);
        if (sessions.size >= 8) throw failure(429, '电脑服务的播放任务已满');
        const headers = {};
        for (const [key, value] of Object.entries(input.headers || {})) {
          if (/^(user-agent|referer|origin|accept)$/i.test(key) && typeof value === 'string' && value.length <= 4096 && !/[\r\n]/.test(value)) headers[key] = value;
        }
        const session = { id: uid(), assets: new Map(), byUrl: new Map(), requests: new Set(), headers, touched: Date.now(), closed: false };
        let mediaPath;
        if (processor) {
          session.pipeline = processor.create(session, input, { open: (url, range) => upstream(url, { ...headers, ...(range ? { Range: `bytes=${range.start}-${range.end}` } : {}) }, 'GET', session) });
          mediaPath = `/v1/sessions/${session.id}/processed/index.m3u8`;
        } else mediaPath = addAsset(session, input.url);
        sessions.set(session.id, session); stats.created++;
        send(res, 201, { protocol: 1, id: session.id, path: mediaPath, processing: processor ? 'enhance' : 'relay' }); return;
      }
      const match = path.match(/^\/v1\/sessions\/([a-f0-9]{32})(?:\/media\/([a-f0-9]{32}(?:\.[a-zA-Z0-9]{1,8})?)|\/processed\/(index\.m3u8|\d+\.ts))?$/);
      const session = match && sessions.get(match[1]);
      if (!session) throw failure(404, '播放任务不存在或已过期，请重试');
      session.touched = Date.now();
      if (!match[2] && !match[3] && req.method === 'GET') { send(res, 200, session.pipeline ? session.pipeline.status() : { state: 'ready', processing: 'relay', label: '电脑串流 · 原画转发' }); return; }
      if (!match[2] && !match[3] && req.method === 'DELETE') { closeSession(session); send(res, 200, { closed: true }); return; }
      if (match[3] && session.pipeline && ['GET','HEAD'].includes(req.method)) {
        const job = session.pipeline;
        await job.preparing;
        if (job.info.state === 'error') throw failure(502, job.info.error);
        if (session.closed) throw failure(410, '播放任务已关闭');
        res.setHeader('Cache-Control','no-store');
        if (match[3] === 'index.m3u8') {
          const body = processedPlaylist(job.duration, job.chunkDuration, `/v1/sessions/${session.id}/processed`);
          res.writeHead(200, { 'Content-Type': 'application/vnd.apple.mpegurl', 'Content-Length': Buffer.byteLength(body) });
          stats.playlistRequests++; stats.mediaRequests++;
          res.end(req.method === 'HEAD' ? undefined : body); return;
        }
        const index = Number(match[3].slice(0,-3));
        const file = await job.serve(index), size = (await stat(file)).size;
        const unpin = processor.budget.pin(file);
        try {
          let start = 0, end = size - 1;
          if (req.headers.range) {
            const range = req.headers.range.match(/^bytes=(\d+)-(\d*)$/);
            if (!range || +range[1] >= size || range[2] && +range[2] < +range[1]) { res.writeHead(416, { 'Content-Range': `bytes */${size}` }); res.end(); return; }
            start = +range[1]; end = range[2] ? Math.min(+range[2],size - 1) : end;
            res.statusCode=206; res.setHeader('Content-Range',`bytes ${start}-${end}/${size}`); stats.rangeRequests++;
          }
          res.setHeader('Content-Type','video/mp2t'); res.setHeader('Accept-Ranges','bytes'); res.setHeader('Content-Length',end-start+1); stats.mediaRequests++;
          if (req.method==='HEAD') res.end(); else { const stream=createReadStream(file,{start,end}); stream.on('data',chunk=>{stats.bytes+=chunk.length;}); await pipeline(stream,res); }
        } finally { unpin(); }
        return;
      }
      if (!match[2] || !['GET', 'HEAD'].includes(req.method)) throw failure(405, '请求方法不支持');
      const url = session.assets.get(match[2]);
      if (!url) throw failure(404, '播放资源不存在');
      const forwarded = { ...session.headers };
      for (const key of ['range', 'if-range']) if (req.headers[key]) forwarded[key] = req.headers[key];
      // Client disconnect cancels just this upstream request; other HLS requests stay alive.
      let disconnected = false;
      const scope = { ...session, requests: new Set(), get closed() { return disconnected || session.closed; } };
      abandoned = () => { disconnected = true; for (const request of scope.requests) request.destroy(); };
      res.once('close', abandoned);
      // Also retain requests in the parent so DELETE/TTL cancels all streams.
      const add = scope.requests.add.bind(scope.requests), remove = scope.requests.delete.bind(scope.requests);
      scope.requests.add = (request) => { session.requests.add(request); return add(request); };
      scope.requests.delete = (request) => { session.requests.delete(request); return remove(request); };
      const result = await upstream(url, forwarded, req.method, scope);
      const response = result.response;
      stats.mediaRequests++;
      if (req.headers.range) stats.rangeRequests++;
      const type = String(response.headers['content-type'] || 'application/octet-stream');
      const playlist = /mpegurl/i.test(type) || /\.m3u8(?:$|\?)/i.test(result.url);
      res.setHeader('Cache-Control', 'no-store');
      if (response.statusCode >= 200 && response.statusCode < 300 && playlist && req.method !== 'HEAD') {
        if (req.headers.range || response.statusCode === 206) { response.destroy(); throw failure(422, '不支持对播放列表本身进行分段读取'); }
        let body = '';
        for await (const chunk of response) { body += chunk.toString('utf8'); if (Buffer.byteLength(body) > 4 * 1024 * 1024) { response.destroy(); throw failure(502, '片源播放列表过大'); } }
        const rewritten = rewritePlaylist(body, result.url, (asset) => addAsset(session, asset));
        stats.playlistRequests++;
        res.writeHead(200, { 'Content-Type': 'application/vnd.apple.mpegurl', 'Content-Length': Buffer.byteLength(rewritten) }); stats.bytes += Buffer.byteLength(rewritten); res.end(rewritten);
      } else {
        res.statusCode = response.statusCode;
        for (const key of ['content-type', 'content-length', 'content-range', 'accept-ranges', 'etag', 'last-modified']) if (response.headers[key]) res.setHeader(key, response.headers[key]);
        response.on('data', (chunk) => { stats.bytes += chunk.length; });
        await pipeline(response, res);
      }
    } catch (error) {
      if (!res.headersSent && !res.destroyed) send(res, error.status || 502, { error: error.status ? error.message : '电脑服务无法读取片源，请检查片源或电脑网络' });
      else if (!res.destroyed) res.destroy();
    } finally { if (abandoned) { res.removeListener('close', abandoned); abandoned(); } }
  });
  server.requestTimeout = 30_000; server.headersTimeout = 15_000;
  const cleanup = setInterval(() => { for (const session of sessions.values()) if (Date.now() - session.touched > (session.pipeline?.info.state === 'preparing' ? Math.min(idleMs,30_000) : idleMs)) closeSession(session); }, Math.min(idleMs, 10_000));
  cleanup.unref();
  server.on('close', () => { clearInterval(cleanup); for (const session of sessions.values()) closeSession(session); });
  return { server, closeSessions: () => { for (const session of sessions.values()) closeSession(session); } };
}
