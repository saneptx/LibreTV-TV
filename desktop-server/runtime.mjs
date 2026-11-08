import { spawn } from 'node:child_process';
import { access, mkdir, readdir, stat, statfs, rm, utimes } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { constants } from 'node:fs';
import { problem } from './hls.mjs';
export const hash = async (value) => (await import('node:crypto')).createHash('sha256').update(value).digest('hex');
export const aborted = () => new DOMException('处理已取消', 'AbortError');
export function check(signal) { if (signal?.aborted) throw aborted(); }
export async function exists(path) { try { return (await stat(path)).isFile(); } catch { return false; } }
export function run(executable, args, { signal, env = {}, timeout = 120_000, onStderr } = {}) {
  check(signal);
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, { env: { ...process.env, OMP_NUM_THREADS: '2', ...env }, stdio: ['ignore', 'pipe', 'pipe'], detached: process.platform !== 'win32' });
    let stdout = '', stderr = '', killTimer;
    const kill = (sig) => { try { if (process.platform !== 'win32' && child.pid) process.kill(-child.pid, sig); else child.kill(sig); } catch {} };
    const stop = () => { kill('SIGTERM'); killTimer = setTimeout(() => kill('SIGKILL'), 2000); killTimer.unref(); };
    const timer = setTimeout(stop, timeout); timer.unref();
    signal?.addEventListener('abort', stop, { once: true });
    child.stdout.on('data', (b) => { stdout = (stdout + b).slice(-2 * 1024 * 1024); });
    child.stderr.on('data', (b) => { stderr = (stderr + b).slice(-8192); onStderr?.(b.toString()); });
    const cleanup = () => { clearTimeout(timer); clearTimeout(killTimer); signal?.removeEventListener('abort', stop); };
    child.once('error', (error) => { cleanup(); reject(problem(503, `处理工具无法启动：${executable.split('/').pop()} (${error.code || 'error'})`)); });
    child.once('close', (code, sig) => { cleanup(); if (signal?.aborted) reject(aborted()); else if (code !== 0) reject(Object.assign(problem(502, `电脑视频处理失败：${executable.split('/').pop()} (${sig || code})`), { diagnostic: stderr })); else resolve({ stdout, stderr }); });
  });
}
export class WorkQueue {
  pending = []; running = false;
  promote(signal, priority = 0) {
    const entry = this.pending.find(item => item.signal === signal);
    if (entry && priority < entry.priority) { entry.priority = priority; this.pending.sort((a,b) => a.priority-b.priority); }
  }
  async enqueue(task, signal, priority = 0) {
    check(signal);
    if (this.pending.length >= 32) throw problem(429, '电脑处理队列已满');
    return new Promise((resolve, reject) => {
      const entry = { task, signal, priority, resolve, reject };
      entry.abort = () => { this.pending = this.pending.filter((x) => x !== entry); reject(aborted()); };
      signal?.addEventListener('abort', entry.abort, { once: true });
      this.pending.push(entry); this.pending.sort((a, b) => a.priority - b.priority); void this.drain();
    });
  }
  async drain() {
    if (this.running) return;
    this.running = true;
    try { while (this.pending.length) {
      const item = this.pending.shift(); item.signal?.removeEventListener('abort', item.abort);
      try { check(item.signal); item.resolve(await item.task()); } catch (error) { item.reject(error); }
    } } finally { this.running = false; }
  }
}
export async function toolConfig(options = {}) {
  const root = resolve(options.toolsDir || '.desktop-server/tools');
  const local = join(root, 'ffmpeg/usr/bin/ffmpeg');
  const ffmpeg = options.ffmpeg || (await exists(local) ? local : 'ffmpeg');
  const ffprobe = options.ffprobe || (ffmpeg.includes('/') ? join(dirname(ffmpeg), 'ffprobe') : 'ffprobe');
  const sr = options.sr || join(root, 'realesrgan/realesrgan-ncnn-vulkan');
  const models = options.models || join(root, 'realesrgan/models');
  const env = { LD_LIBRARY_PATH: [join(dirname(ffmpeg), '../lib/x86_64-linux-gnu'), process.env.LD_LIBRARY_PATH].filter(Boolean).join(':') };
  if (options.vulkanIcd) env.VK_ICD_FILENAMES = options.vulkanIcd;
  else if (!process.env.VK_ICD_FILENAMES && await exists('/usr/share/vulkan/icd.d/nvidia_icd.json')) env.VK_ICD_FILENAMES = '/usr/share/vulkan/icd.d/nvidia_icd.json';
  const model = options.model || 'fsrcnnx';
  if (!['fsrcnnx', 'realesr-general-x4v3', 'realesrgan-x4plus', 'realesr-animevideov3'].includes(model)) throw problem(400, '超分模型不支持');
  const fast = model === 'fsrcnnx', shader = join(root,'shaders/FSRCNNX_x2_8-0-4-1.glsl');
  const modelFile = fast ? shader : join(models, model === 'realesr-animevideov3' ? model + '-x2.bin' : model + '.bin');
  if (!fast) await access(sr, constants.X_OK);
  const modelBytes=await (await import('node:fs/promises')).readFile(modelFile);
  const modelSignature=await hash(fast ? modelBytes : Buffer.concat([modelBytes,await (await import('node:fs/promises')).readFile(modelFile.replace(/\.bin$/,'.param'))]));
  env.XDG_CACHE_HOME=join(root,'gpu-cache'); await mkdir(env.XDG_CACHE_HOME,{recursive:true});
  const config = { ffmpeg, ffprobe, sr, models, env, model, shader, engine: fast ? 'libplacebo-vulkan' : 'ncnn-vulkan', modelSignature, scale: model === 'realesr-animevideov3' ? 2 : 4, encoder: options.encoder || 'h264_nvenc', tile: options.tile || 512, gpu: String(options.gpu ?? 0), targetHeight: options.targetHeight ?? 'auto', chunkSeconds: options.chunkSeconds || 8 };
  if (!['h264_nvenc', 'libx264'].includes(config.encoder) || !['auto',720,1080,2160].includes(config.targetHeight) || ![1,2,4,8].includes(config.chunkSeconds) || ![128,256,512].includes(config.tile)) throw problem(400, '电脑处理参数无效');
  await run(ffprobe, ['-version'], { env });
  await run(ffmpeg, ['-hide_banner','-loglevel','error','-f','lavfi','-i','color=s=320x180:r=24','-frames:v','1','-c:v',config.encoder,'-f','null','-'], { env, timeout: 15_000 });
  if(fast) await run(ffmpeg,['-hide_banner','-loglevel','error',...vulkanDevice(config),'-f','lavfi','-i','testsrc2=s=320x180:r=24','-frames:v','2','-vf',vulkanFilter(config,{width:320,height:180},{width:640,height:360}),'-f','null','-'],{env,timeout:30_000});
  return config;
}
export function vulkanDevice(tools) { return ['-init_hw_device',`vulkan=vk:${tools.gpu}`,'-filter_hw_device','vk']; }
export function vulkanFilter(tools, source, target, frameRate) {
  if(/[\n\r'\\]/.test(tools.shader)) throw problem(400,'Shader path contains unsupported characters');
  const path=tools.shader.replace(/:/g,'\\:');
  const filters = ['format=yuv420p', 'hwupload'];
  let current = source;
  const upscale = (width, height) => {
    filters.push(`libplacebo=w=${width}:h=${height}${frameRate ? ':fps='+frameRate : ''}:custom_shader_path='${path}':format=yuv420p`);
    frameRate = undefined;
    current = { width, height };
  };
  // FSRCNNX is a 2x network: cascade passes rather than stretch a single result to 3x/4x.
  while (Math.min(target.width/current.width,target.height/current.height) > 2.001) upscale(current.width*2,current.height*2);
  // The upstream model condition requires >1.3x; force its execution for smaller enlargements too.
  const forceTwo = Math.min(target.width/current.width,target.height/current.height)<=1.3;
  upscale(forceTwo ? current.width*2 : target.width, forceTwo ? current.height*2 : target.height);
  if (forceTwo) filters.push(`libplacebo=w=${target.width}:h=${target.height}:format=yuv420p`);
  return filters.concat('hwdownload','format=yuv420p','setsar=1').join(',');
}
export function encodingProfile(size, fps) {
  const tier = Math.max(size.height, size.width * 9 / 16);
  // Even-pixel aspect rounding (854x480, for example) must not move a source up a bitrate tier.
  const [average, peak] = tier > 1080*1.01 ? [12,20] : tier > 720*1.01 ? [5,8] : tier > 480*1.01 ? [3,5] : [1.5,3];
  const factor = Math.min(1.5, Math.max(1, fps/30));
  return { average:Math.round(average*factor*1_000_000), peak:Math.round(peak*factor*1_000_000), buffer:Math.round(peak*factor*1_000_000) };
}
export function videoEncoderOptions(encoder, profile) {
  const limits = ['-maxrate',String(profile.peak),'-bufsize',String(profile.buffer)];
  return encoder === 'h264_nvenc'
    ? ['-preset','p4','-rc','vbr','-cq','20','-b:v',String(profile.average),...limits,'-rc-lookahead','0','-zerolatency','1']
    : ['-preset','veryfast','-crf','20',...limits];
}
export class DiskBudget {
  constructor(root, limit = 8 * 1024 ** 3) { this.root = resolve(root); this.limit = limit; this.pins = new Map(); }
  pin(file) { this.pins.set(file, (this.pins.get(file) || 0) + 1); return () => { const count = this.pins.get(file) - 1; if (count) this.pins.set(file, count); else this.pins.delete(file); }; }
  async touch(file) { try { const now = new Date(); await utimes(file, now, now); } catch {} }
  async reserve(bytes = 0) {
    await mkdir(this.root, { recursive: true, mode: 0o700 });
    if (bytes > this.limit) throw problem(507, '视频超过电脑缓存上限');
    const files = [];
    const walk = async (dir) => { for (const item of await readdir(dir, { withFileTypes: true })) {
      const file = join(dir, item.name);
      if (item.isDirectory() && item.name !== 'work') await walk(file);
      else if (item.isFile() && !item.name.endsWith('.part')) { const s = await stat(file).catch(() => null); if (s) files.push({ file, size: s.size, time: s.mtimeMs }); }
    } };
    await walk(this.root);
    let total = files.reduce((n, f) => n + f.size, 0);
    for (const file of files.sort((a, b) => a.time - b.time)) {
      if (total + bytes <= this.limit) break;
      if (!this.pins.has(file.file)) { await rm(file.file, { force: true }); total -= file.size; }
    }
    if (total + bytes > this.limit) throw problem(507, '电脑缓存已满，请退出其他影片后重试');
    const fs = await statfs(this.root);
    if (fs.bavail * fs.bsize < bytes + 512 * 1024 ** 2) throw problem(507, '电脑磁盘剩余空间不足');
  }
}
