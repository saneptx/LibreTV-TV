#!/usr/bin/env node
// 一键启动（Windows / Linux / macOS 通用）：网页服务 8080 + 电脑串流服务 8090。
// 用法：node start_all.mjs [--no-stream] [--no-build]
//   --no-stream  只启动网页服务（没有 NVIDIA 显卡或不需要超分时）
//   --no-build   跳过网页构建，直接用上一次的构建结果
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { networkInterfaces } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
process.chdir(root);
const flags = new Set(process.argv.slice(2));
for (const flag of flags) if (!['--no-stream', '--no-build'].includes(flag)) { console.error('用法：node start_all.mjs [--no-stream] [--no-build]'); process.exit(1); }
const node = process.execPath, next = join(root, 'node_modules/next/dist/bin/next');
const step = (title, command, args, options = {}) => {
  console.log(title);
  const result = spawnSync(command, args, { stdio: 'inherit', ...options });
  if (result.status !== 0) { console.error(`${title} 失败`); process.exit(result.status ?? 1); }
};

// 家庭局域网默认免登录；AUTH_DISABLED=false 可恢复密码验证（与 start_local.sh 一致）。
process.env.AUTH_DISABLED ??= 'true';
if (process.env.AUTH_DISABLED !== 'true' && !process.env.PASSWORD) { console.error('已开启访问验证，请先设置 PASSWORD 环境变量'); process.exit(1); }

if (!existsSync(next)) step('正在安装项目依赖……', 'npm ci --include=dev', [], { shell: true });

const sources = JSON.parse(readFileSync('source.json', 'utf8'));
if (!Array.isArray(sources)) throw new Error('source.json 必须是数组');
if (sources.length > 100) throw new Error('最多支持 100 个点播源');
for (const s of sources) if (!s.name || !/^https?:\/\//.test(s.url || '')) throw new Error('点播源名称或 URL 无效');
process.env.DEFAULT_SOURCES = JSON.stringify(sources);
console.log(`已加载 ${sources.length} 个点播源`);

if (!flags.has('--no-build') || !existsSync('.next/BUILD_ID')) step('正在构建网页……', node, [next, 'build']);

const stream = !flags.has('--no-stream');
if (stream) step('正在检查电脑串流环境（首次会下载 FFmpeg 和超分模型）……', node, ['desktop-server/setup-tools.mjs']);

const all = Object.values(networkInterfaces()).flat().filter((a) => a && a.family === 'IPv4' && !a.internal).map((a) => a.address);
// 198.18.0.0/15 是本机代理的 Fake-IP 虚拟网卡：不是可供电视连接的地址，但说明片源域名会被解析到该网段
const fakeIp = (address) => /^198\.1[89]\./.test(address);
const proxied = all.some(fakeIp), addresses = all.filter((a) => !fakeIp(a));
const children = [];
let stopping = false;
const stop = (code = 0) => { if (stopping) return; stopping = true; for (const child of children) child.kill(); process.exitCode = code; };
const launch = (name, args) => {
  const child = spawn(node, args, { stdio: 'inherit' });
  child.on('exit', (code) => { if (!stopping) { console.error(`${name}已退出（${code}），正在停止其余服务`); stop(code || 1); } });
  children.push(child);
};
launch('网页服务', [next, 'start', '-p', '8080']);
// 电视 / 手机要从局域网连接，串流服务监听所有网卡；所有请求仍需配对码。
if (stream) launch('电脑串流服务', ['desktop-server/start.mjs', '--host', '0.0.0.0', ...(proxied ? ['--allow-fake-ip'] : [])]);
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => stop(0));

console.log('\n本机浏览器：http://localhost:8080');
for (const address of addresses) console.log(`局域网设备：网页 http://${address}:8080${stream ? `　电脑串流地址 http://${address}:8090` : ''}`);
if (stream) console.log(`电视「片源管理」里填写电脑串流地址和配对码；配对码在 ${join(root, '.desktop-server', 'config.json')}`);
if (process.env.AUTH_DISABLED === 'true') console.log('网页服务已关闭密码验证，局域网内的设备均可免登录访问。');
console.log('按 Ctrl+C 停止全部服务。\n');
