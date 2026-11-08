import { createWriteStream, createReadStream } from 'node:fs';
import { mkdir, readFile, writeFile, rename, rm, stat, readdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { attributes, parseMaster, parseMedia, sliceMedia, targetSize, problem } from './hls.mjs';
import { hash, exists, run, WorkQueue, DiskBudget, check, aborted, vulkanDevice, vulkanFilter, encodingProfile, videoEncoderOptions } from './runtime.mjs';
const MiB = 1024 ** 2;
export function compactRemovals(removed) {
  const result = [];
  for (const item of removed) { const last = result.at(-1); if (last && Math.abs(last.start + last.duration - item.start) < .001) last.duration += item.duration; else result.push({ start: item.start, duration: item.duration }); }
  return result;
}
export function cleanPosition(position, removals) {
  return Math.max(0, position - removals.reduce((n, r) => n + Math.max(0, Math.min(r.duration, position - r.start)), 0));
}
export function processedPlaylist(duration, chunkSeconds, path) {
  const count = Math.ceil((duration - .000001) / chunkSeconds);
  if (!Number.isFinite(duration) || duration <= 0 || count > 30000) throw problem(422, '视频时长无效');
  const lines = ['#EXTM3U','#EXT-X-VERSION:3','#EXT-X-PLAYLIST-TYPE:VOD','#EXT-X-INDEPENDENT-SEGMENTS',`#EXT-X-TARGETDURATION:${Math.ceil(chunkSeconds)}`,'#EXT-X-MEDIA-SEQUENCE:0'];
  for (let n = 0; n < count; n++) { if (n) lines.push('#EXT-X-DISCONTINUITY'); lines.push(`#EXTINF:${Math.min(chunkSeconds, duration - n * chunkSeconds).toFixed(6)},`,`${path}/${n}.ts`); }
  return lines.concat('#EXT-X-ENDLIST','').join('\n');
}
function sourceFailure(response, resource) {
  const status = response.statusCode;
  response.destroy();
  const reason = [401, 403].includes(status) ? '片源拒绝访问' : [404, 410].includes(status) ? '片源地址已失效' : status === 429 ? '片源请求过于频繁' : `电脑读取${resource}失败`;
  return problem(502, `${reason}（HTTP ${status}），请稍后重试或换源`);
}
export async function readPlaylist(response, limit = 4 * MiB) {
  if (response.statusCode !== 200) throw sourceFailure(response, '视频清单');
  const chunks = []; let size = 0;
  for await (const chunk of response) { size += chunk.length; if (size > limit) { response.destroy(); throw problem(422, '视频清单过大'); } chunks.push(chunk); }
  return Buffer.concat(chunks).toString('utf8');
}
export function createProcessor(tools, { cacheDir = '.desktop-server/cache', cacheBytes = 8 * 1024 ** 3, runner = run, prefetch = 4, startupBufferSeconds = 8, overlapDownloads = true } = {}) {
  if (!Number.isInteger(startupBufferSeconds) || startupBufferSeconds < 0 || startupBufferSeconds > 30) throw problem(400, '启动预缓冲须为0–30秒');
  const root = resolve(cacheDir), budget = new DiskBudget(root, cacheBytes), queue = new WorkQueue(), sourceQueue = overlapDownloads ? new WorkQueue() : queue;
  return { tools, budget, queue, sourceQueue, root, runner, prefetch, startupBufferSeconds, overlapDownloads, create: (session, input, network) => new ProcessingSession({ tools, budget, queue, sourceQueue, root, runner, prefetch, startupBufferSeconds }, session, input, network) };
}
class ProcessingSession {
  constructor(engine, session, input, network) {
    Object.assign(this, engine, { session, input, network });
    this.controller = new AbortController(); this.signal = this.controller.signal; this.jobs = new Map(); this.rawPending = new Map(); this.pins = new Map(); this.requested = -1; this.activeWork = 0; this.workElapsed = 0; this.workStarted = 0;
    this.info = { state: 'preparing', stage: 'source', message: '电脑正在读取视频清单', readySegments: 0, cacheHits: 0, rawCacheHits: 0, processedSeconds: 0, processingSeconds: 0, sourcePreparationSeconds: 0, encodeSeconds: 0, validationSeconds: 0, removedSegments: 0, removedSeconds: 0 };
    this.preparing = this.prepare().catch((error) => { if (!this.signal.aborted) this.fail(error); });
  }
  fail(error) {
    this.info.state = 'error'; this.info.message = error.status ? error.message : '电脑处理片源失败，请尝试其他片源'; this.info.error = this.info.message;
    console.error(`[processing ${this.session.id.slice(0,8)}] ${this.info.message}${error.diagnostic ? '\n' + error.diagnostic.slice(-2500) : ''}`);
  }
  update(stage, message) { check(this.signal); this.info.stage = stage; this.info.message = message; }
  pin(file) { if (!this.pins.has(file)) this.pins.set(file, this.budget.pin(file)); return file; }
  close() { this.controller.abort(); for (const task of this.jobs.values()) task.controller.abort(); for (const unpin of this.pins.values()) unpin(); this.pins.clear(); }
  timing() {
    const processingSeconds = (this.workElapsed + (this.activeWork ? performance.now()-this.workStarted : 0))/1000;
    return { processingSeconds, ...(processingSeconds && this.info.processedSeconds ? { speed:this.info.processedSeconds/processingSeconds } : {}) };
  }
  beginWork() {
    if (this.activeWork++ === 0) this.workStarted = performance.now();
    return () => { if (--this.activeWork === 0) this.workElapsed += performance.now()-this.workStarted; };
  }
  status() { return { ...this.info, ...this.timing(), position: this.position, timeMap: this.timeMap || [], label: this.label || '电脑增强', processing: 'enhance' }; }
  async raw(url, range, provided, signal = this.signal) {
    check(signal);
    const key = await hash(JSON.stringify({ url, headers: this.session.headers, range }));
    const dir = join(this.root, 'raw'), file = join(dir, key + '.bin'); await mkdir(dir, { recursive: true, mode: 0o700 });
    if (await exists(file) && Date.now() - (await stat(file)).mtimeMs < 6 * 3600_000) {
      check(signal); provided?.response.destroy(); this.info.rawCacheHits++; this.pin(file); await this.budget.touch(file); return file;
    }
    if (this.rawPending.has(key)) { provided?.response.destroy(); return this.rawPending.get(key); }
    const task = (async () => {
      const { response } = provided || await this.network.open(url, range, signal);
      const limit = this.progressive && url === this.url ? Math.min(this.budget.limit / 2, 4 * 1024 ** 3) : 256 * MiB;
      if (response.statusCode !== (range ? 206 : 200)) throw sourceFailure(response, '视频分片');
      if (range && response.headers['content-range']?.split('/')[0] !== `bytes ${range.start}-${range.end}`) { response.destroy(); throw problem(502, '片源未返回所需字节范围'); }
      const length = Number(response.headers['content-length']);
      if (length > limit) { response.destroy(); throw problem(507, '片源文件超过电脑缓存上限'); }
      await this.budget.reserve(Number.isFinite(length) ? length : limit);
      const partial = file + '.' + randomBytes(5).toString('hex') + '.part'; let bytes = 0;
      const meter = new Transform({ transform: (chunk, _encoding, callback) => {
        bytes += chunk.length;
        if (signal.aborted) callback(aborted());
        else if (bytes > limit) callback(problem(507, '片源文件超过电脑缓存上限'));
        else { if (this.progressive && url === this.url) this.update('download', `电脑正在下载正片${length ? ` ${Math.floor(bytes / length * 100)}%` : ''}`); callback(null, chunk); }
      } });
      try { await pipeline(response, meter, createWriteStream(partial, { mode: 0o600 }), { signal }); check(signal); if (!bytes || Number.isFinite(length) && length > 0 && bytes !== length) throw problem(502, '片源下载不完整'); await rename(partial, file); check(signal); this.pin(file); return file; }
      finally { await rm(partial, { force: true }); }
    })();
    this.rawPending.set(key, task); try { return await task; } finally { this.rawPending.delete(key); }
  }
  async media(url, adFilter) {
    const result = await this.network.open(url, undefined, this.signal); const body = await readPlaylist(result.response);
    return { ...parseMedia(body, result.url, { adFilter }), signature: await hash(body) };
  }
  async prepare() {
    await this.budget.reserve(); this.url = this.input.url;
    let result = await this.network.open(this.url, undefined, this.signal), body, externalAudio;
    for (let depth = 0; depth < 6; depth++) {
      const type = String(result.response.headers['content-type'] || '');
      if (!/mpegurl/i.test(type) && !/\.m3u8(?:\?|$)/i.test(result.url)) {
        this.progressive = true; this.url = result.url; this.file = await this.raw(this.url, undefined, result); break;
      }
      body = await readPlaylist(result.response); const variants = parseMaster(body, result.url);
      if (variants.length) {
        const maximum = this.tools.targetHeight === 'auto' ? 2160 : this.tools.targetHeight;
        const choice = variants.find((v) => !v.height || v.height <= maximum) || variants.at(-1);
        externalAudio = choice.audio || externalAudio; result = await this.network.open(choice.url, undefined, this.signal); continue;
      }
      // 独立音轨的分片行不同，设备的额外标记无法对齐音轨，此时只用电脑自身规则。
      this.mediaSource = { ...parseMedia(body, result.url, { adFilter: this.input.adFilter !== false, adSegments: externalAudio ? undefined : this.input.adSegments }), signature: await hash(body) };
      if (externalAudio) {
        this.audioSource = await this.media(externalAudio, this.input.adFilter !== false);
        if (Math.abs(this.mediaSource.duration - this.audioSource.duration) > .1) throw problem(422, '视频与独立音轨广告时间轴不一致，请换源');
      }
      break;
    }
    if (!this.file && !this.mediaSource) throw problem(422, '视频主列表嵌套过深');
    this.update('probe', '电脑正在检查编码和画面尺寸');
    let probeFile = this.file, probeIsHls = false, firstWork;
    if (!probeFile) {
      firstWork = join(this.root, 'work', this.session.id, 'probe'); await mkdir(firstWork, { recursive: true, mode: 0o700 });
      probeFile = (await this.localWindow(this.mediaSource, 0, Math.min(6, this.mediaSource.duration), firstWork, 'probe')).file; probeIsHls = true;
    }
    const args = ['-v','error',...this.inputOptions(probeIsHls,true),'-show_streams','-show_format','-of','json',probeFile];
    const metadata = JSON.parse((await this.runner(this.tools.ffprobe, args, { env: this.tools.env, signal: this.signal })).stdout);
    const video = metadata.streams.find((s) => s.codec_type === 'video');
    if (!video || ['smpte2084','arib-std-b67'].includes(video.color_transfer)) throw problem(422, '电脑增强暂不支持此编码或 HDR，可切回独立直连');
    this.size = targetSize(Number(video.width), Number(video.height), this.tools.targetHeight, this.input.superResolution !== false);
    const rational = String(video.avg_frame_rate && video.avg_frame_rate !== '0/0' ? video.avg_frame_rate : video.r_frame_rate).split('/').map(Number);
    this.fps = rational[0] / rational[1];
    if (!Number.isFinite(this.fps) || this.fps < 1 || this.fps > 60) throw problem(422, '电脑增强目前支持 1–60 FPS 视频');
    this.frameRate = `${rational[0]}/${rational[1]}`;
    this.encodingProfile = encodingProfile(this.size, this.fps);
    this.chunkFrames = Math.max(1, Math.round(this.tools.chunkSeconds * this.fps)); this.chunkDuration = this.chunkFrames / this.fps;
    this.hasAudio = !!this.audioSource || metadata.streams.some((s) => s.codec_type === 'audio');
    this.duration = this.mediaSource?.duration ?? Number(metadata.format.duration);
    if (!Number.isFinite(this.duration) || this.duration <= 0 || this.duration > 8 * 3600) throw problem(422, '点播视频时长无效');
    this.timeMap = compactRemovals(this.mediaSource?.removed || []);
    this.position = Math.min(Math.max(0, cleanPosition(Number(this.input.position) || 0, this.timeMap)), Math.max(0, this.duration - .05));
    this.info.removedSegments = this.mediaSource?.removed.length || 0; this.info.removedSeconds = this.mediaSource?.removedSeconds || 0;
    this.info.duration = this.duration; this.info.input = { width: video.width, height: video.height, fps: this.fps };
    this.info.output = { width: this.size.width, height: this.size.height, fps: this.fps, encoder: this.tools.encoder, model: this.size.superResolution ? this.tools.model : null, superResolution: this.size.superResolution, bitrate: this.encodingProfile.average, maxBitrate: this.encodingProfile.peak };
    this.info.processingInfo = { sourceWidth: video.width, sourceHeight: video.height, outputWidth: this.size.width, outputHeight: this.size.height,
      superResolution: this.size.superResolution, superResolutionEnabled: this.input.superResolution !== false, model: this.info.output.model, adRemovalEnabled: this.input.adFilter !== false,
      adRemovalSupported: !!this.mediaSource, removedSegments: this.info.removedSegments, removedSeconds: this.info.removedSeconds };
    const outputLabel = this.size.width >= 3840 ? (this.size.height >= 2160 ? '4K' : '4K 宽屏') : `${this.size.height}P`;
    this.label = `电脑${this.size.superResolution ? ' AI 超分' : '转码'} · ${outputLabel}${this.info.removedSegments ? ` · 过滤 ${this.info.removedSegments} 个广告分片` : ''}`;
    const signature = await hash(JSON.stringify({ version: 6, url: this.input.url, headers: this.session.headers, source: this.mediaSource?.signature || (await stat(this.file)).size, audio: this.audioSource?.signature, model: this.tools.modelSignature, size: this.size, fps: this.frameRate, encoder: this.tools.encoder, bitrate: this.encodingProfile, chunk: this.chunkDuration, superResolution: this.input.superResolution !== false, adFilter: this.input.adFilter !== false, adSegments: this.input.adSegments?.length ? await hash(JSON.stringify(this.input.adSegments)) : undefined }));
    this.directory = join(this.root, 'processed', signature); await mkdir(this.directory, { recursive: true, mode: 0o700 });
    if (firstWork) await rm(firstWork, { recursive: true, force: true });
    const first = Math.floor(this.position / this.chunkDuration);
    const count = Math.ceil((this.duration-.000001)/this.chunkDuration);
    const offset = this.position-first*this.chunkDuration;
    const warmCount = Math.min(count-first, Math.max(1,Math.ceil((this.startupBufferSeconds+offset)/this.chunkDuration)));
    this.info.startupTargetSeconds = Math.min(this.duration-this.position, Math.max(this.startupBufferSeconds,this.chunkDuration-offset));
    this.info.startupPreparedSeconds = 0;
    this.update('warming', `电脑正在预缓冲 ${warmCount} 段视频`);
    const completed = new Set(); let contiguous = first;
    await Promise.all(Array.from({ length:warmCount }, async (_,offset) => {
      const n = first+offset; await this.segment(n,true); completed.add(n);
      while (completed.has(contiguous)) contiguous++;
      this.info.startupPreparedSeconds = Math.min(this.duration,contiguous*this.chunkDuration)-this.position;
    }));
    check(this.signal); this.info.state = 'ready'; this.update('ready', this.label);
    this.prefetchFrom(first+warmCount-1);
  }
  inputOptions(hls, probe = false) { return hls ? ['-protocol_whitelist','file,crypto,data','-allowed_extensions','ALL',...(probe ? [] : ['-dts_delta_threshold','0.1'])] : ['-protocol_whitelist','file,crypto,data']; }
  async localWindow(media, start, duration, dir, name, signal = this.signal) {
    const selected = sliceMedia(media, start, duration); const lines = ['#EXTM3U','#EXT-X-VERSION:6',`#EXT-X-TARGETDURATION:${Math.ceil(Math.max(...selected.segments.map((s) => s.duration)))}`,'#EXT-X-MEDIA-SEQUENCE:0'];
    let previous;
    const keyLine = async (key) => key ? `#EXT-X-KEY:METHOD=AES-128,URI="file:${await this.raw(key.url, undefined, undefined, signal)}",IV=${key.iv}` : '#EXT-X-KEY:METHOD=NONE';
    for (const segment of selected.segments) {
      check(signal);
      if (previous !== undefined && (segment.sequence !== previous + 1 || segment.discontinuity)) lines.push('#EXT-X-DISCONTINUITY');
      if (segment.map) { lines.push(await keyLine(segment.map.key)); lines.push(`#EXT-X-MAP:URI="file:${await this.raw(segment.map.url, segment.map.range, undefined, signal)}"`); }
      lines.push(await keyLine(segment.key));
      lines.push(`#EXTINF:${segment.duration},`,`file:${await this.raw(segment.url, segment.range, undefined, signal)}`); previous = segment.sequence;
    }
    lines.push('#EXT-X-ENDLIST'); const file = join(dir, name + '.m3u8'); await writeFile(file, lines.join('\n'), { mode: 0o600 }); return { file, offset: selected.offset, hls: true };
  }
  async segment(index, warming = false, background = false) {
    check(this.signal);
    if (!Number.isInteger(index) || index < 0 || index >= Math.ceil((this.duration - .000001) / this.chunkDuration)) throw problem(404, '视频分段不存在');
    const output = join(this.directory, `${index}.ts`);
    const cached = await exists(output); check(this.signal);
    if (cached) { this.info.cacheHits++; this.pin(output); await this.budget.touch(output); return output; }
    const existing = this.jobs.get(index);
    if (existing) {
      if (existing.controller.signal.aborted) {
        await existing.promise.catch(()=>{}); check(this.signal);
        return this.segment(index,warming,background);
      }
      if (!background) { existing.background=false; this.sourceQueue.promote(existing.controller.signal); this.queue.promote(existing.controller.signal); }
      return existing.promise;
    }
    const controller = new AbortController(), abort = () => controller.abort();
    this.signal.addEventListener('abort', abort, { once: true });
    const work = join(this.root, 'work', this.session.id, String(index));
    let endWork;
    const priority = background ? 10 : 0;
    const promise = (async () => {
      const prepared = await this.sourceQueue.enqueue(async () => {
        check(controller.signal); endWork = this.beginWork(); const began = performance.now();
        await this.budget.reserve(256 * MiB); check(controller.signal);
        await mkdir(work, { recursive:true, mode:0o700 });
        const start = index*this.chunkDuration, duration = Math.min(this.chunkDuration,this.duration-start);
        const source = this.file ? { file:this.file, offset:start, hls:false } : await this.localWindow(this.mediaSource,start,duration,work,'video',controller.signal);
        const audio = this.audioSource ? await this.localWindow(this.audioSource,start,duration,work,'audio',controller.signal) : source;
        this.info.sourcePreparationSeconds += (performance.now()-began)/1000;
        return { source,audio,duration };
      },controller.signal,priority);
      check(controller.signal);
      return await this.queue.enqueue(async () => {
        check(controller.signal);
        const { source,audio,duration } = prepared;
        const encodingBegan = performance.now();
        const options = { env: this.tools.env, signal: controller.signal, timeout: 5 * 60_000 };
        const input = [...this.inputOptions(source.hls),...(source.hls ? [] : ['-ss',source.offset.toFixed(6)]),'-i',source.file];
        const videoTrim = source.hls ? `trim=start=${source.offset.toFixed(6)}:duration=${duration.toFixed(6)},setpts=PTS-STARTPTS` : 'setpts=PTS-STARTPTS';
        const partial = join(work, 'encoded.ts');
        const encoder = videoEncoderOptions(this.tools.encoder, this.encodingProfile);
        if (this.tools.engine === 'libplacebo-vulkan' || !this.size.superResolution) {
          const upscale = this.size.superResolution;
          if (warming) this.update(upscale ? 'super-resolution' : 'encode', upscale ? '电脑正在 GPU 超分并编码 · FSRCNNX' : '电脑正在编码视频');
          const filter = upscale ? vulkanFilter(this.tools,this.info.input,this.size,this.frameRate) : `fps=${this.frameRate},scale=${this.size.width}:${this.size.height}:flags=lanczos,setsar=1`;
          const separateAudio = this.hasAudio && !!this.audioSource;
          const audioInput = separateAudio ? [...this.inputOptions(audio.hls),...(audio.hls ? [] : ['-ss',audio.offset.toFixed(6)]),'-i',audio.file] : [];
          const audioFilter = `atrim=start=${audio.hls ? audio.offset.toFixed(6) : '0'}:duration=${duration.toFixed(6)},asetpts=PTS-STARTPTS,aresample=async=1:first_pts=0,apad`;
          await this.runner(this.tools.ffmpeg,['-hide_banner','-loglevel','error','-threads','2',...(upscale ? vulkanDevice(this.tools) : []),...input,
            ...audioInput,'-map','0:v:0',...(this.hasAudio ? ['-map',`${separateAudio ? 1 : 0}:a:0`,'-c:a','aac','-b:a','128k','-ac','2','-ar','48000','-af',audioFilter] : ['-an']),
            '-t',duration.toFixed(6),'-frames:v',String(Math.ceil(duration*this.fps-.000001)),'-vf',`${videoTrim},${filter}`,
            '-c:v',this.tools.encoder,...encoder,'-pix_fmt','yuv420p','-g',String(this.chunkFrames),'-bf','0','-mpegts_flags','+resend_headers','-muxdelay','0','-muxpreload','0','-f','mpegts','-y',partial],options);
        } else {
          if (this.hasAudio) await this.runner(this.tools.ffmpeg, ['-hide_banner','-loglevel','error','-threads','2',...this.inputOptions(audio.hls),...(audio.hls ? ['-i',audio.file,'-ss',audio.offset.toFixed(6)] : ['-ss',audio.offset.toFixed(6),'-i',audio.file]),'-t',duration.toFixed(6),'-vn','-ac','2','-ar','48000','-af','asetpts=PTS-STARTPTS,aresample=async=1:first_pts=0','-c:a','pcm_s16le','-y',join(work,'audio.wav')], options);
          const frames = join(work, 'frames'), enhanced = join(work, 'enhanced'); await mkdir(frames); await mkdir(enhanced);
          if (warming) this.update('decode', '电脑正在解码首段视频');
          await this.runner(this.tools.ffmpeg, ['-hide_banner','-loglevel','error','-threads','2',...input,'-t',duration.toFixed(6),'-an','-vf',`${videoTrim},fps=${this.frameRate}`,'-frames:v',String(Math.ceil(duration * this.fps - .000001)),'-fps_mode','cfr','-y',join(frames,'%08d.png')], options);
          const frameCount = (await readdir(frames)).filter((s) => s.endsWith('.png')).length;
          if (!frameCount) throw problem(502, '电脑未能从片源解码出画面');
          let imageDir = frames;
          if (this.size.superResolution) {
            if (warming) this.update('super-resolution', `电脑正在 AI 超分 · ${this.tools.model}`);
            let frameProgress = 0;
            await this.runner(this.tools.sr, ['-i',frames,'-o',enhanced,'-n',this.tools.model,'-s',String(this.tools.scale),'-t',String(this.tools.tile),'-g',this.tools.gpu,'-m',this.tools.models,'-j','1:1:2','-f','jpg'], { ...options, onStderr: (chunk) => { const matches = chunk.match(/(?:^|\n)0\.00%/g); if (matches) frameProgress += matches.length; if (warming && frameProgress) this.update('super-resolution', `电脑正在 AI 超分 · ${Math.min(frameProgress, frameCount)} / ${frameCount} 帧`); } });
            if ((await readdir(enhanced)).filter((s) => s.endsWith('.jpg')).length !== frameCount) throw problem(502, 'AI 超分输出帧数不完整');
            imageDir = enhanced;
          }
          await this.runner(this.tools.ffmpeg, ['-hide_banner','-loglevel','error','-threads','2','-framerate',this.frameRate,'-i',join(imageDir,this.size.superResolution ? '%08d.jpg' : '%08d.png'),...(this.hasAudio ? ['-i',join(work,'audio.wav')] : []),'-map','0:v:0',...(this.hasAudio ? ['-map','1:a:0','-c:a','aac','-b:a','128k','-ar','48000','-af','apad'] : ['-an']),'-t',duration.toFixed(6),'-vf',`scale=${this.size.width}:${this.size.height}:flags=lanczos,setsar=1`,'-c:v',this.tools.encoder,...encoder,'-pix_fmt','yuv420p','-g',String(frameCount),'-bf','0','-mpegts_flags','+resend_headers','-muxdelay','0','-muxpreload','0','-f','mpegts','-y',partial], options);
          }
        this.info.encodeSeconds += (performance.now()-encodingBegan)/1000;
        const validationBegan = performance.now();
        const metadata = JSON.parse((await this.runner(this.tools.ffprobe, ['-v','error','-show_streams','-show_format','-of','json',partial], options)).stdout);
        const video = metadata.streams.find((s) => s.codec_type === 'video');
        const sound = metadata.streams.find((s) => s.codec_type === 'audio');
        if (!video || video.width !== this.size.width || video.height !== this.size.height || this.hasAudio && !sound) throw problem(502, '电脑编码产物验证失败');
        const encodedDuration = Number(video.duration || metadata.format?.duration);
        if (!Number.isFinite(encodedDuration) || Math.abs(encodedDuration - duration) > Math.max(.1, 2 / this.fps) || sound && Math.abs(Number(video.start_time) - Number(sound.start_time)) > .1) throw problem(502, '电脑编码产物时长或音画时间轴无效');
        check(controller.signal); await rename(partial, output); check(controller.signal); this.pin(output);
        this.info.validationSeconds += (performance.now()-validationBegan)/1000;
        this.info.readySegments++; this.info.processedSeconds += duration;
        return output;
      },controller.signal,this.jobs.get(index)?.background ? 10 : 0);
    })().finally(async () => { try { await rm(work,{ recursive:true,force:true }); } finally { endWork?.(); } });
    this.jobs.set(index, { controller, promise, background });
    try { return await promise; } finally { this.jobs.delete(index); this.signal.removeEventListener('abort', abort); }
  }
  prefetchFrom(index) {
    if (this.signal.aborted || this.info.state !== 'ready') return;
    for (let n = index + 1; n <= index + this.prefetch && n < Math.ceil(this.duration / this.chunkDuration); n++) void this.segment(n, false, true).catch((error) => { if (error.name !== 'AbortError' && !this.signal.aborted) this.info.warning = error.message; });
  }
  async serve(index) {
    await this.preparing; check(this.signal);
    if (this.info.state === 'error') throw problem(502, this.info.error);
    // A new seek takes priority over stale speculative processing.
    if (this.requested >= 0 && Math.abs(index-this.requested)>1) for (const [n, job] of this.jobs) if (job.background && (n < index || n > index + this.prefetch)) job.controller.abort();
    this.requested = index;
    const file = await this.segment(index); this.prefetchFrom(index); return file;
  }
}
