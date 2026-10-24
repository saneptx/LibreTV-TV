import { parseArgs } from 'node:util';
import { randomBytes } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { toolConfig } from './runtime.mjs';
import { createProcessor } from './processing.mjs';
import { createDesktopServer } from './server.mjs';

process.chdir(resolve(import.meta.dirname, '..'));

const { values } = parseArgs({ options: { host: { type: 'string', default: '127.0.0.1' }, port: { type: 'string', default: '8090' }, 'allow-test-origin': { type: 'string' }, 'allow-fake-ip': { type: 'boolean', default: false }, processing: { type: 'string', default: 'enhance' }, model: { type: 'string', default: 'fsrcnnx' }, encoder: { type: 'string', default: 'h264_nvenc' }, 'target-height': { type: 'string', default: 'auto' }, ffmpeg: { type: 'string' }, ffprobe: { type: 'string' }, sr: { type: 'string' }, 'cache-gb': { type: 'string', default: '8' }, 'chunk-seconds': { type: 'string', default: '4' } } });
const dir = resolve(import.meta.dirname, '../.desktop-server');
await mkdir(dir, { recursive: true, mode: 0o700 });
let token;
try { token = JSON.parse(await readFile(`${dir}/config.json`, 'utf8')).token; }
catch (error) {
  if (error.code !== 'ENOENT') throw error;
  token = randomBytes(24).toString('hex');
  await writeFile(`${dir}/config.json`, JSON.stringify({ token }, null, 2), { mode: 0o600, flag: 'wx' });
}
const port = Number(values.port);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('端口无效');
if (!['relay','enhance'].includes(values.processing)) throw new Error('处理模式必须为 enhance 或 relay');
const cacheBytes = Number(values['cache-gb']) * 1024 ** 3;
if (!Number.isFinite(cacheBytes) || cacheBytes < 512 * 1024 ** 2 || cacheBytes > 128 * 1024 ** 3) throw new Error('缓存上限须为 0.5–128 GB');
let processor;
if (values.processing === 'enhance') {
  try { processor = createProcessor(await toolConfig({ model: values.model, encoder: values.encoder, targetHeight: values['target-height'] === 'auto' ? 'auto' : Number(values['target-height']), chunkSeconds: Number(values['chunk-seconds']), ffmpeg: values.ffmpeg, ffprobe: values.ffprobe, sr: values.sr }), { cacheBytes }); }
  catch (error) { console.error('电脑增强环境未就绪：'+error.message+'。请执行 npm run desktop:setup，或显式选择 --processing relay。'); process.exit(1); }
}
const { server, closeSessions } = createDesktopServer({ token, allowTestOrigin: values['allow-test-origin'], allowFakeIp: values['allow-fake-ip'], processor });
server.on('error', (error) => { console.error(`电脑服务启动失败：${error.code || error.message}`); process.exitCode = 1; });
server.listen(port, values.host, () => {
  console.log(`LibreTV 电脑串流服务：http://${values.host}:${port}`);
  if (processor) console.log(processor.tools.targetHeight === 'auto' ? '超分策略：默认升一档（720P → 1080P、1080P → 4K，4K 保持）。' : `输出上限：${processor.tools.targetHeight}P（手动档位）。`);
  console.log(processor ? `已启用：广告分片过滤 → ${processor.tools.model} 超分 → ${processor.tools.encoder} 编码 → HLS 缓存串流。` : '当前仅转发原画面；去广告和超分尚未启用。');
  console.log(`配对码保存在 ${dir}/config.json（仅电脑服务连接使用，APP 独立模式不需密码）。`);
  if (values['allow-test-origin']) console.log(`仅为模拟器测试额外放行：${new URL(values['allow-test-origin']).origin}`);
  if (values['allow-fake-ip']) console.log('已启用本机代理 Fake-IP 域名兼容（198.18.0.0/15；仍拒绝 IP 字面量和普通私网）。');
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { closeSessions(); server.close(); server.closeAllConnections(); });
