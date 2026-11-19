import { parseArgs } from 'node:util';
import { randomBytes, randomInt } from 'node:crypto';
import { hostname } from 'node:os';
import { advertise } from './discovery.mjs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { toolConfig } from './runtime.mjs';
import { createProcessor } from './processing.mjs';
import { createDesktopServer } from './server.mjs';

// 数据目录：电脑端程序通过 SUPERTV_HOME 指定（配对码、同步资料、片库、超分工具都在其下的 .desktop-server）；直接在仓库里运行时为仓库根目录
process.chdir(process.env.SUPERTV_HOME ? resolve(process.env.SUPERTV_HOME) : resolve(import.meta.dirname, '..'));

const { values } = parseArgs({ options: { host: { type: 'string', default: '127.0.0.1' }, port: { type: 'string', default: '8090' }, 'allow-test-origin': { type: 'string' }, 'allow-fake-ip': { type: 'boolean', default: false }, library: { type: 'string', default: '' }, processing: { type: 'string', default: 'enhance' }, model: { type: 'string', default: 'fsrcnnx' }, encoder: { type: 'string', default: 'h264_nvenc' }, 'target-height': { type: 'string', default: 'auto' }, ffmpeg: { type: 'string' }, ffprobe: { type: 'string' }, sr: { type: 'string' }, 'cache-gb': { type: 'string', default: '8' }, 'chunk-seconds': { type: 'string', default: '8' }, 'startup-buffer-seconds':{type:'string',default:'8'} } });
const dir = resolve('.desktop-server');
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
  try { processor = createProcessor(await toolConfig({ model: values.model, encoder: values.encoder, targetHeight: values['target-height'] === 'auto' ? 'auto' : Number(values['target-height']), chunkSeconds: Number(values['chunk-seconds']), ffmpeg: values.ffmpeg, ffprobe: values.ffprobe, sr: values.sr }), { cacheBytes, startupBufferSeconds:Number(values['startup-buffer-seconds']) }); }
  catch (error) { console.error('电脑增强环境未就绪：'+error.message+'。请执行 npm run desktop:setup，或显式选择 --processing relay。'); process.exit(1); }
}
// 只监听本机时别的设备连不上，不需要配对码和广播
const lan = !['127.0.0.1', 'localhost', '::1'].includes(values.host);
const pin = lan ? String(randomInt(0, 1_000_000)).padStart(6, '0') : undefined;
const { server, closeSessions } = createDesktopServer({ token, allowTestOrigin: values['allow-test-origin'], allowFakeIp: values['allow-fake-ip'], processor, syncFile: resolve(dir, 'sync.json'), libraryDir: values.library ? resolve(values.library) : undefined, pin, deviceName: hostname().replace(/\.(local|lan|home)$/i, '') });
let stopAdvertising = () => {};
server.on('error', (error) => { console.error(`电脑服务启动失败：${error.code || error.message}`); process.exitCode = 1; });
server.listen(port, values.host, () => {
  console.log(`LibreTV 电脑串流服务：http://${values.host}:${port}`);
  if (processor) console.log(processor.tools.targetHeight === 'auto' ? '超分策略：默认升一档（720P → 1080P、1080P → 4K，4K 保持）。' : `输出上限：${processor.tools.targetHeight}P（手动档位）。`);
  console.log(processor ? `已启用：广告分片过滤 → ${processor.tools.model} 超分 → ${processor.tools.encoder} 编码 → HLS 缓存串流。` : '当前仅转发原画面；去广告和超分尚未启用。');
  if(processor)console.log(`启动预缓冲：${processor.startupBufferSeconds} 秒；分段 ${processor.tools.chunkSeconds} 秒，下载与编码并行。`);
  console.log(`配对码保存在 ${dir}/config.json（仅电脑服务连接使用，APP 独立模式不需密码）。`);
  if (lan) {
    stopAdvertising = advertise({ port, txt: { protocol: 1 }, log: (message) => console.log(message) });
    console.log(`\n电视配对码：${pin.slice(0, 3)} ${pin.slice(3)}\n电视「设置 → 电脑服务」里会自动找到这台电脑，选中后填这 6 位数字即可（每次启动更换，只在首次连接时需要）。\n`);
  }
  if (values['allow-test-origin']) console.log(`仅为模拟器测试额外放行：${new URL(values['allow-test-origin']).origin}`);
  if (values['allow-fake-ip']) console.log('已启用本机代理 Fake-IP 域名兼容（198.18.0.0/15；仍拒绝 IP 字面量和普通私网）。');
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { stopAdvertising(); closeSessions(); server.close(); server.closeAllConnections(); });
