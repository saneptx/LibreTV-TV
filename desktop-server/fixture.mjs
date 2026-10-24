// Explicit local test source. Never started by the production relay.
import http from 'node:http';
import { stat } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { parseArgs } from 'node:util';
import { resolve, extname } from 'node:path';
import { pipeline } from 'node:stream/promises';
const { values } = parseArgs({ options: { dir: { type: 'string' }, port: { type: 'string', default: '8091' } } });
if (!values.dir) throw new Error('用法：node desktop-server/fixture.mjs --dir 测试视频目录');
const root = resolve(values.dir), port = Number(values.port), origin = `http://127.0.0.1:${port}`;
const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, origin);
    if (url.pathname === '/api') {
      const item = { vod_id: 'desktop-demo', vod_name: '电脑串流测试 TV', vod_pic: '', vod_remarks: '合成测试画面 · 720P', type_name: '测试', vod_play_from: '测试源', vod_play_url: `HLS测试$${origin}/film.m3u8#MP4测试$${origin}/film.mp4` };
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ code: 1, page: 1, pagecount: 1, total: 1, list: [item] })); return;
    }
    if (!/^\/[a-zA-Z0-9_-]+\.(m3u8|mp4|ts)$/.test(url.pathname)) { res.writeHead(404); res.end(); return; }
    const file = resolve(root, '.' + url.pathname), size = (await stat(file)).size;
    const types = { '.m3u8': 'application/vnd.apple.mpegurl', '.mp4': 'video/mp4', '.ts': 'video/mp2t' };
    res.setHeader('Content-Type', types[extname(file)]); res.setHeader('Accept-Ranges', 'bytes');
    let start = 0, end = size - 1;
    if (req.headers.range) {
      const range = req.headers.range.match(/^bytes=(\d+)-(\d*)$/);
      if (!range || +range[1] >= size || range[2] && +range[2] < +range[1]) { res.writeHead(416, { 'Content-Range': `bytes */${size}` }); res.end(); return; }
      start = +range[1]; end = range[2] ? Math.min(+range[2], size - 1) : end;
      res.statusCode = 206; res.setHeader('Content-Range', `bytes ${start}-${end}/${size}`);
    }
    res.setHeader('Content-Length', end - start + 1);
    if (req.method === 'HEAD') { res.end(); return; }
    await pipeline(createReadStream(file, { start, end }), res);
  } catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.destroy(); }
});
server.listen(port, '127.0.0.1', () => console.log(`仅本机测试片源：${origin}/api`));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { server.close(); server.closeAllConnections(); });
