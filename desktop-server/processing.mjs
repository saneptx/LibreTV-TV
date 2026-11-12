import { createWriteStream, createReadStream } from 'node:fs';
import { mkdir, readFile, writeFile, rename, rm, stat, readdir } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { attributes, parseMaster, parseMedia, sliceMedia, targetSize, problem, firstPts, probeTimeline, timelineGroups, timelineInsertLines } from './hls.mjs';
import { AD_DISTANCE, THUMB_HEIGHT, THUMB_WIDTH, chooseInsert, dropWindow, mediaSegments, thumbDistance, thumbFeatures, timelineDrops } from './inserts.mjs';
import { ANALYSIS_WIDTH, delogoFilter, detectOverlays, marqueeExtent, maskImage, staticTextBands, toSource } from './overlay.mjs';
import { hash, exists, run, WorkQueue, DiskBudget, check, aborted, vulkanDevice, vulkanFilter, encodingProfile, videoEncoderOptions, chooseCodec } from './runtime.mjs';
const MiB = 1024 ** 2;
/** 时间轴插播检测结果，按媒体列表原文缓存 */
const timelineCache = new Map();
export function compactRemovals(removed) {
  const result = [];
  for (const item of removed) { const last = result.at(-1); if (last && Math.abs(last.start + last.duration - item.start) < .001) last.duration += item.duration; else result.push({ start: item.start, duration: item.duration }); }
  return result;
}
export function cleanPosition(position, removals) {
  return Math.max(0, position - removals.reduce((n, r) => n + Math.max(0, Math.min(r.duration, position - r.start)), 0));
}
/**
 * 分段音频对齐全片统一的 AAC 帧网格（每帧 1024 采样）：每段多编码前 8 帧、后 3 帧作为预热，只保留起点落在本段内的帧。
 * 相邻两段的音频帧首尾相接，不会有编码器起始静音或末帧补零造成的「顿一下」。
 */
export const AAC_FRAME = 1024 / 48000;
export function audioWindow(start, end) {
  const grid = (t) => Math.ceil(t / AAC_FRAME - 1e-6) * AAC_FRAME;
  const keepStart = grid(start), keepEnd = grid(end), from = Math.max(0, keepStart - 8 * AAC_FRAME);
  return { from, keepStart, keepEnd, length: keepEnd - from + 3 * AAC_FRAME };
}
export const audioKeepFilter = ({ keepStart, keepEnd }) => `noise=drop=lt(pts*tb\\,${(keepStart - .0005).toFixed(6)})+gte(pts*tb\\,${(keepEnd - .0005).toFixed(6)})`;
/** continuous：分段时间轴连续（新版编码），不再在每段之间插入 DISCONTINUITY，播放器无需在段间重新同步 */
export function processedPlaylist(duration, chunkSeconds, path, continuous = false) {
  const count = Math.ceil((duration - .000001) / chunkSeconds);
  if (!Number.isFinite(duration) || duration <= 0 || count > 30000) throw problem(422, '视频时长无效');
  const lines = ['#EXTM3U','#EXT-X-VERSION:3','#EXT-X-PLAYLIST-TYPE:VOD','#EXT-X-INDEPENDENT-SEGMENTS',`#EXT-X-TARGETDURATION:${Math.ceil(chunkSeconds)}`,'#EXT-X-MEDIA-SEQUENCE:0'];
  for (let n = 0; n < count; n++) { if (n && !continuous) lines.push('#EXT-X-DISCONTINUITY'); lines.push(`#EXTINF:${Math.min(chunkSeconds, duration - n * chunkSeconds).toFixed(6)},`,`${path}/${n}.ts`); }
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
/** 片源读取：缓存的原始下载与本地 HLS 窗口；处理任务与多来源比对共用同一份原始缓存。 */
export class SourceReader {
  constructor(engine, session, input, network) {
    Object.assign(this, engine, { session, input, network });
    this.controller = new AbortController(); this.signal = this.controller.signal; this.rawPending = new Map(); this.pins = new Map();
    this.info = { rawCacheHits: 0 };
  }
  update(stage, message) { check(this.signal); this.info.stage = stage; this.info.message = message; }
  pin(file) { if (!this.pins.has(file)) this.pins.set(file, this.budget.pin(file)); return file; }
  release() { for (const unpin of this.pins.values()) unpin(); this.pins.clear(); }
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
  /** 全片 10 个位置各抽两帧灰度小图，识别固定水印和滚动字幕；结果按片源缓存，重开不再抽帧 */
  async overlays(video) {
    if (this.input.overlayRemoval === false) return [];
    const w = ANALYSIS_WIDTH, h = Math.max(2, Math.round(w * video.height / video.width / 2) * 2);
    const file = join(this.root, 'overlays', await hash(JSON.stringify({ version: 2, source: this.mediaSource?.signature || this.url, width: video.width, height: video.height })) + '.json');
    try { return JSON.parse(await readFile(file, 'utf8')); } catch { /* 首次分析 */ }
    this.update('overlay', '电脑正在检查画面水印和滚动字幕');
    const controller = new AbortController(), abort = () => controller.abort(), timer = setTimeout(abort, 30_000);
    this.signal.addEventListener('abort', abort, { once: true });
    try {
      const positions = Array.from({ length: 10 }, (_, i) => this.duration * (.08 + .84 * i / 9)), pairs = [];
      let next = 0;
      await Promise.all(Array.from({ length: 3 }, async () => {
        while (next < positions.length && !controller.signal.aborted) {
          const frames = await this.frames(positions[next++], w, h, controller.signal).catch(() => undefined);
          if (frames?.length === 2 * w * h) pairs.push({ a: frames.subarray(0, w * h), b: frames.subarray(w * h) });
        }
      }));
      check(this.signal);
      if (pairs.length < 6) return [];
      const regions = detectOverlays(pairs, w, h, Number(video.width), Number(video.height));
      await mkdir(join(this.root, 'overlays'), { recursive: true, mode: 0o700 }); await writeFile(file, JSON.stringify(regions), { mode: 0o600 });
      return regions;
    } finally { clearTimeout(timer); this.signal.removeEventListener('abort', abort); }
  }
  /** 本段编码用的叠加物滤镜：台标常驻处理，滚动字幕只在本段确实在滚动时处理 */
  async overlayFor(start, duration, signal) {
    if (this.input.overlayRemoval === false) return this.overlayFilter;
    const regions = this.overlayRegions || [], bands = regions.filter((r) => r.kind === 'marquee');
    const w = ANALYSIS_WIDTH, h = Math.max(2, Math.round(w * this.info.input.height / this.info.input.width / 2) * 2), pairs = [];
    // 段首与段尾各取一对帧（相隔约 7 秒），既判断滚动字幕是否在滚，也识别本段才出现的静止横幅广告
    for (const t of [start + Math.min(.3, duration * .1), start + Math.max(duration - 1, duration * .6)]) {
      const frames = await this.frames(Math.min(t, this.duration - .5), w, h, signal).catch(() => undefined);
      if (frames?.length === 2 * w * h) pairs.push({ a: frames.subarray(0, w * h), b: frames.subarray(w * h) });
    }
    // 判断不了（取帧失败）时按出现处理，宁可多模糊也不漏掉广告
    const width = Number(this.info.input.width), height = Number(this.info.input.height);
    const active = bands.flatMap((band) => {
      if (!pairs.length) return [band];
      const found = pairs.map((pair) => marqueeExtent(pair, w, h, band, width, height)).filter(Boolean);
      if (!found.length) return [];
      // 取两次采样的并集，覆盖完整字高
      const y = Math.min(band.y, ...found.map((r) => r.y)), bottom = Math.max(band.y + band.h, ...found.map((r) => r.y + r.h));
      return [{ ...band, y, h: Math.min(bottom, height - 2) - y }];
    });
    const found = pairs.length === 2 ? staticTextBands([pairs[0].a, pairs[0].b, pairs[1].a, pairs[1].b], w, h) : [];
    const banners = [];
    for (const [i, band] of found.entries()) {
      const [region] = toSource([band], w, h, width, height);
      if (!region || active.some((m) => Math.min(region.y + region.h, m.y + m.h) - Math.max(region.y, m.y) > 0)) continue;
      // 文字掩码写成图片，只修补文字像素
      const maskFile = join(this.root, 'work', this.session.id, `banner-${Math.round(start * 10)}-${i}.pgm`);
      try { await mkdir(dirname(maskFile), { recursive: true, mode: 0o700 }); await writeFile(maskFile, maskImage(band.mask, w, h, width, height, region), { mode: 0o600 }); region.maskFile = maskFile; } catch { /* 写不了掩码时退回模糊 */ }
      banners.push(region);
    }
    if (banners.length) this.info.bannerSegments = (this.info.bannerSegments || 0) + 1;
    return delogoFilter([...regions.filter((r) => r.kind !== 'marquee'), ...active, ...banners]);
  }
  async frames(t, w, h, signal) {
    const dir = join(this.root, 'work', this.session.id, `overlay-${Math.round(t * 10)}`); await mkdir(dir, { recursive: true, mode: 0o700 });
    try {
      let input;
      if (this.file) input = ['-ss', t.toFixed(3), ...this.inputOptions(false), '-i', this.file];
      else { const window = await this.localWindow(this.mediaSource, t, 1.5, dir, 'video', signal); input = [...this.inputOptions(true, true), '-i', window.file, '-ss', window.offset.toFixed(3)]; }
      const { stdout } = await this.runner(this.tools.ffmpeg, ['-hide_banner', '-loglevel', 'error', ...input, '-an', '-frames:v', '2', '-vf', `fps=2.5,scale=${w}:${h},format=gray`, '-f', 'rawvideo', 'pipe:1'],
        { env: this.tools.env, signal, timeout: 60_000, binary: true, maxBytes: 2 * w * h + 1024 });
      return stdout;
    } finally { await rm(dir, { recursive: true, force: true }); }
  }
  // jumps：窗口内有 DISCONTINUITY（去广告后的拼接点），才让 ffmpeg 把时间戳跳变拉平；连续窗口用这个阈值会误判正常的分片衔接，把音视频整体挪动约 0.2 秒
  inputOptions(hls, probe = false, jumps = true) { return hls ? ['-protocol_whitelist','file,crypto,data','-allowed_extensions','ALL',...(probe || !jumps ? [] : ['-dts_delta_threshold','0.1'])] : ['-protocol_whitelist','file,crypto,data']; }
  async localWindow(media, start, duration, dir, name, signal = this.signal) {
    const selected = sliceMedia(media, start, duration); const lines = ['#EXTM3U','#EXT-X-VERSION:6',`#EXT-X-TARGETDURATION:${Math.ceil(Math.max(...selected.segments.map((s) => s.duration)))}`,'#EXT-X-MEDIA-SEQUENCE:0'];
    let previous, jumps = false;
    const keyLine = async (key) => key ? `#EXT-X-KEY:METHOD=AES-128,URI="file:${await this.raw(key.url, undefined, undefined, signal)}",IV=${key.iv}` : '#EXT-X-KEY:METHOD=NONE';
    for (const segment of selected.segments) {
      check(signal);
      if (previous !== undefined && (segment.sequence !== previous + 1 || segment.discontinuity)) { lines.push('#EXT-X-DISCONTINUITY'); jumps = true; }
      if (segment.map) { lines.push(await keyLine(segment.map.key)); lines.push(`#EXT-X-MAP:URI="file:${await this.raw(segment.map.url, segment.map.range, undefined, signal)}"`); }
      lines.push(await keyLine(segment.key));
      lines.push(`#EXTINF:${segment.duration},`,`file:${await this.raw(segment.url, segment.range, undefined, signal)}`); previous = segment.sequence;
    }
    lines.push('#EXT-X-ENDLIST'); const file = join(dir, name + '.m3u8'); await writeFile(file, lines.join('\n'), { mode: 0o600 });
    return { file, offset: selected.offset + await this.windowAnchor(file, signal), hls: true, jumps };
  }
  /**
   * ffmpeg 把窗口时间零点设为首个分片里最早的包（音频或视频），而分片的标称起点对应首个视频帧；
   * 各分片音视频交错不同，零点会差一两个音频帧。这里返回两者之差，使每个窗口都按同一条时间轴裁剪。
   */
  async windowAnchor(file, signal) {
    try {
      const { stdout } = await this.runner(this.tools.ffprobe, ['-v','error',...this.inputOptions(true, true),'-show_entries','format=start_time:stream=codec_type,start_time','-of','json',file], { env: this.tools.env, signal, timeout: 30_000 });
      const data = JSON.parse(stdout), video = data.streams?.find((s) => s.codec_type === 'video');
      const anchor = Number(video?.start_time) - Number(data.format?.start_time);
      return Number.isFinite(anchor) && anchor >= 0 && anchor < 2 ? anchor : 0;
    } catch (error) { if (signal?.aborted) throw error; return 0; }
  }
}
class ProcessingSession extends SourceReader {
  constructor(engine, session, input, network) {
    super(engine, session, input, network);
    this.jobs = new Map(); this.requested = -1; this.activeWork = 0; this.workElapsed = 0; this.workStarted = 0;
    this.info = { state: 'preparing', stage: 'source', message: '电脑正在读取视频清单', readySegments: 0, cacheHits: 0, rawCacheHits: 0, processedSeconds: 0, processingSeconds: 0, sourcePreparationSeconds: 0, encodeSeconds: 0, validationSeconds: 0, removedSegments: 0, removedSeconds: 0 };
    this.preparing = this.prepareWithFallback().catch((error) => { if (!this.signal.aborted) this.fail(error); });
  }
  fail(error) {
    this.info.state = 'error'; this.info.message = error.status ? error.message : '电脑处理片源失败，请尝试其他片源'; this.info.error = this.info.message;
    console.error(`[processing ${this.session.id.slice(0,8)}] ${this.info.message}${error.diagnostic ? '\n' + error.diagnostic.slice(-2500) : ''}`);
  }
  close() { this.controller.abort(); for (const task of this.jobs.values()) task.controller.abort(); this.release(); }
  timing() {
    const processingSeconds = (this.workElapsed + (this.activeWork ? performance.now()-this.workStarted : 0))/1000;
    return { processingSeconds, ...(processingSeconds && this.info.processedSeconds ? { speed:this.info.processedSeconds/processingSeconds } : {}) };
  }
  beginWork() {
    if (this.activeWork++ === 0) this.workStarted = performance.now();
    return () => { if (--this.activeWork === 0) this.workElapsed += performance.now()-this.workStarted; };
  }
  status() { return { ...this.info, ...this.timing(), position: this.position, timeMap: this.timeMap || [], label: this.label || '电脑增强', processing: 'enhance' }; }
  async media(url, adFilter) {
    const result = await this.network.open(url, undefined, this.signal); const body = await readPlaylist(result.response);
    return { ...parseMedia(body, result.url, { adFilter }), signature: await hash(body) };
  }
  /** 设备给的媒体列表在电脑上被拒（分片按请求 IP 签名）时，改用电脑自己下载的列表重新准备一次 */
  /** 读各段组第一个分片开头 4 KB 的时间戳，找出自带时间戳、播完后正片又接回原位的插入段。按列表原文缓存，拖动进度重建任务时不再联网 */
  async timelineInserts(body, base) {
    const key = await hash(body), saved = join(this.root, 'inserts', key + '.json');
    if (timelineCache.has(key)) return timelineCache.get(key);
    // 检测结果也写在磁盘上：电脑服务重启后再打开同一集不用重新联网核对
    try { const value = JSON.parse(await readFile(saved, 'utf8')); if (Array.isArray(value.lines) && Date.now() - value.at < 7 * 86400_000) { timelineCache.set(key, value.lines); return value.lines; } } catch { /* 没有缓存 */ }
    const groups = timelineGroups(body, base);
    if (!groups.length) return [];
    this.update('source', '电脑正在检查插播广告');
    const controller = new AbortController(), began = Date.now();
    let timedOut = false, failed = 0;
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, 8000);
    const abort = () => controller.abort(); this.signal.addEventListener('abort', abort, { once: true });
    let reads = 0;
    try {
      reads = await probeTimeline(groups, async (group) => {
        const { response } = await this.network.open(group.url, { start: 0, end: 4095 }, controller.signal);
        if (![200, 206].includes(response.statusCode)) { response.destroy(); return undefined; }
        const chunks = []; let bytes = 0;
        for await (const chunk of response) { chunks.push(chunk); bytes += chunk.length; if (bytes >= 4096) break; }
        response.destroy();
        return firstPts(Buffer.concat(chunks).subarray(0, 4096));
      }, { signal: controller.signal }).catch(() => 0);
    } finally { clearTimeout(timer); this.signal.removeEventListener('abort', abort); }
    check(this.signal);
    const lines = timelineInsertLines(groups);
    failed = groups.filter((g) => g.tried && g.pts === undefined).length;
    // 时间戳规则解释不了的偏移下降：用画面核对广告分片（广告被打上与正片衔接的时间戳时）
    const visual = timedOut ? { lines: [], drops: 0, confirmed: 0, complete: false } : await this.visualInserts(body, base, groups, new Set(lines));
    lines.push(...visual.lines);
    console.log(`[processing ${this.session.id.slice(0, 8)}] 插播检测：${groups.length} 个段组，读 ${reads} 组${failed ? `（${failed} 组未读到）` : ''}，${((Date.now() - began) / 1000).toFixed(1)} 秒${timedOut ? '，已超时' : ''}，删除 ${lines.length} 个分片`);
    if (visual.drops) console.log(`[processing ${this.session.id.slice(0, 8)}] 画面核对：${visual.drops} 处时间戳下降，确认 ${visual.confirmed} 处插播，删除 ${visual.lines.length} 个分片${visual.complete ? '' : '（部分画面未读到）'}`);
    // 超时或有组没读到时不缓存：下次打开重新检测，不能把一次网络慢的空结果沿用下去
    if (!timedOut && !failed && visual.complete) {
      if (timelineCache.size >= 64) timelineCache.delete(timelineCache.keys().next().value);
      timelineCache.set(key, lines);
      await mkdir(dirname(saved), { recursive: true, mode: 0o700 }).then(() => writeFile(saved, JSON.stringify({ lines, at: Date.now() }), { mode: 0o600 })).catch(() => {});
    }
    return lines;
  }
  /** 对每处偏移下降，逐片取关键帧小图与窗口外正片参照比较，选出广告分片。最多 6 处、12 秒 */
  async visualInserts(body, base, groups, removed) {
    const drops = timelineDrops(groups, removed).slice(0, 6);
    if (!drops.length) return { lines: [], drops: 0, confirmed: 0, complete: true };
    const segments = mediaSegments(body, base);
    if (!segments.length) return { lines: [], drops: drops.length, confirmed: 0, complete: true };
    this.update('source', '电脑正在核对插播画面');
    // 取第一帧与边界复核各自计时，边界复核要读整片，不能被前一步用掉预算
    const budget = (ms) => {
      const controller = new AbortController(), timer = setTimeout(() => controller.abort(), ms);
      const abort = () => controller.abort(); this.signal.addEventListener('abort', abort, { once: true });
      return { signal: controller.signal, done: () => { clearTimeout(timer); this.signal.removeEventListener('abort', abort); } };
    };
    const pool = async (items, concurrency, signal, task) => {
      let cursor = 0;
      await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, async () => { while (cursor < items.length && !signal.aborted) await task(items[cursor++]); }));
    };
    const features = new Map(), windows = drops.map((drop) => ({ drop, ...dropWindow(segments, drop) }));
    const wanted = [...new Set(windows.flatMap((w) => [...w.inside, ...w.refs]))];
    const dir = join(this.root, 'work', this.session.id); await mkdir(dir, { recursive: true, mode: 0o700 });
    const first = budget(10_000);
    try {
      await pool(wanted, 16, first.signal, async (i) => {
        const rgb = await this.thumbnail(segments[i].url, join(dir, `thumb-${i}.ts`), first.signal).catch(() => undefined);
        if (rgb) features.set(i, thumbFeatures(rgb));
      });
    } finally { first.done(); }
    check(this.signal);
    const chosen = [];
    for (const { drop, inside, refs } of windows) {
      const references = refs.map((i) => features.get(i)).filter(Boolean);
      if (references.length < 2) continue;
      const distance = (f) => Math.min(...references.map((r) => thumbDistance(f, r)));
      const scores = [];
      for (const i of inside) if (features.has(i)) scores[i] = distance(features.get(i));
      const range = chooseInsert(segments, inside, scores, drop);
      if (range) chosen.push({ ...range, distance });
    }
    // 网站常在分片中间切开插广告，两端会有「半片广告」；第一帧也可能只是残留的一两帧。
    // 读整片、每秒两帧，像广告的帧过半才算：选中片段两端不过半就退回正片，紧挨的外侧片过半就并进来
    const edges = budget(10_000), share = new Map();
    try {
      const jobs = chosen.flatMap((c) => [c.from, c.to, c.from - 1, c.to + 1].filter((i) => i >= 0 && i < segments.length).map((i) => ({ i, c })));
      await pool(jobs, 8, edges.signal, async ({ i, c }) => {
        const frames = await this.thumbnail(segments[i].url, join(dir, `edge-${i}.ts`), edges.signal, true).catch(() => undefined);
        if (frames?.length) share.set(`${c.from}:${i}`, frames.filter((f) => c.distance(thumbFeatures(f)) >= AD_DISTANCE).length / frames.length);
      });
    } finally { edges.done(); }
    check(this.signal);
    const lines = [];
    for (const c of chosen) {
      const part = (i) => share.get(`${c.from}:${i}`);
      let { from, to } = c;
      if (from < to && (part(from) ?? 1) < .5) from++;
      if (from < to && (part(to) ?? 1) < .5) to--;
      if (from > 0 && (part(from - 1) ?? 0) >= .5) from--;
      if (to < segments.length - 1 && (part(to + 1) ?? 0) >= .5) to++;
      for (let i = from; i <= to; i++) if (!removed.has(segments[i].line)) lines.push(segments[i].line);
    }
    const edgeCount = chosen.reduce((n, c) => n + [c.from, c.to, c.from - 1, c.to + 1].filter((i) => i >= 0 && i < segments.length).length, 0);
    return { lines, drops: drops.length, confirmed: chosen.length, complete: wanted.every((i) => features.has(i)) && share.size === edgeCount };
  }
  /**
   * 分片第一帧的 64×36 RGB 小图；先读前 256 KB，第一帧不完整时再读 768 KB。
   * whole 为真时读整片、每秒取两帧，返回小图数组（判断「半片广告」用）。
   */
  async thumbnail(url, file, signal, whole = false) {
    for (const bytes of whole ? [8 * 1024 * 1024] : [262144, 786432]) {
      const { response } = await this.network.open(url, { start: 0, end: bytes - 1 }, signal);
      if (![200, 206].includes(response.statusCode)) { response.destroy(); return undefined; }
      const chunks = []; let size = 0;
      for await (const chunk of response) { chunks.push(chunk); size += chunk.length; if (size >= bytes) break; }
      response.destroy();
      await writeFile(file, Buffer.concat(chunks).subarray(0, bytes), { mode: 0o600 });
      try {
        const frame = THUMB_WIDTH * THUMB_HEIGHT * 3;
        const { stdout } = await this.runner(this.tools.ffmpeg, ['-v', 'quiet', '-i', file, ...(whole ? [] : ['-frames:v', '1']), '-vf', `${whole ? 'fps=2,' : ''}scale=${THUMB_WIDTH}:${THUMB_HEIGHT}:flags=area,format=rgb24`, '-f', 'rawvideo', 'pipe:1'],
          { env: this.tools.env, signal, timeout: 15_000, binary: true, maxBytes: frame * (whole ? 120 : 2) });
        const count = Math.floor(stdout.length / frame);
        if (whole) return Array.from({ length: count }, (_, k) => new Uint8Array(stdout.subarray(k * frame, (k + 1) * frame)));
        if (count) return new Uint8Array(stdout.subarray(0, frame));
      } catch (error) { if (signal.aborted) throw error; }
      finally { await rm(file, { force: true }); }
    }
    return undefined;
  }
  async prepareWithFallback() {
    try { await this.prepare(); }
    catch (error) {
      if (!this.usedDevicePlaylist || this.signal.aborted || !/HTTP 40[34]/.test(error.message)) throw error;
      for (const job of this.jobs.values()) job.controller.abort();
      this.input = { ...this.input, playlists: undefined, adSegments: undefined, repeatFilter: true };
      this.info.devicePlaylistRejected = true;
      await this.prepare();
    }
  }
  async prepare() {
    await this.budget.reserve(); this.url = this.input.url;
    // 盗链站按请求动态插入广告：有设备分析过的媒体列表原文时直接使用，设备标记的分片行才对得上。
    const provided = new Map((this.input.playlists || []).map((p) => [p.url, p.text]));
    let target = this.url, body, externalAudio, base;
    for (let depth = 0; depth < 6; depth++) {
      if (provided.has(target)) { body = provided.get(target); base = target; }
      else {
        const result = await this.network.open(target, undefined, this.signal);
        const type = String(result.response.headers['content-type'] || '');
        if (!/mpegurl/i.test(type) && !/\.m3u8(?:\?|$)/i.test(result.url)) {
          this.progressive = true; this.url = result.url; this.file = await this.raw(this.url, undefined, result); break;
        }
        body = await readPlaylist(result.response); base = result.url;
        if (provided.has(base)) body = provided.get(base);
      }
      const variants = parseMaster(body, base);
      if (variants.length) {
        const maximum = this.tools.targetHeight === 'auto' ? 2160 : this.tools.targetHeight;
        const choice = variants.find((v) => !v.height || v.height <= maximum) || variants.at(-1);
        externalAudio = choice.audio || externalAudio; target = choice.url; continue;
      }
      this.usedDevicePlaylist = provided.has(base);
      // 设备网络慢时时间轴检测可能来不及做完：电脑再按同一规则查一遍，结果与设备标记合并
      const inserted = this.input.adFilter !== false && !externalAudio ? await this.timelineInserts(body, base) : [];
      if (inserted.length) this.info.timelineSegments = inserted.length;
      // 实际删除的分片行：设备标记 + 电脑自己查出的插播。处理缓存的签名必须按这份算，否则会复用没去广告时的旧分段
      this.removalLines = externalAudio ? undefined : [...(this.input.adSegments || []), ...inserted];
      // 独立音轨的分片行不同，设备的额外标记无法对齐音轨，此时只用电脑自身规则。
      this.mediaSource = { ...parseMedia(body, base, { adFilter: this.input.adFilter !== false, adSegments: this.removalLines, repeatFilter: !!this.input.repeatFilter && !externalAudio }), signature: await hash(body) };
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
    this.codec = chooseCodec(this.tools, this.input.decoders, this.size, this.fps);
    this.encodingProfile = encodingProfile(this.size, this.fps, this.codec.codec);
    this.chunkFrames = Math.max(1, Math.round(this.tools.chunkSeconds * this.fps)); this.chunkDuration = this.chunkFrames / this.fps;
    this.hasAudio = !!this.audioSource || metadata.streams.some((s) => s.codec_type === 'audio');
    this.duration = this.mediaSource?.duration ?? Number(metadata.format.duration);
    if (!Number.isFinite(this.duration) || this.duration <= 0 || this.duration > 8 * 3600) throw problem(422, '点播视频时长无效');
    this.timeMap = compactRemovals(this.mediaSource?.removed || []);
    this.position = Math.min(Math.max(0, cleanPosition(Number(this.input.position) || 0, this.timeMap)), Math.max(0, this.duration - .05));
    this.info.removedSegments = this.mediaSource?.removed.length || 0; this.info.removedSeconds = this.mediaSource?.removedSeconds || 0;
    this.info.duration = this.duration; this.info.input = { width: video.width, height: video.height, fps: this.fps };
    this.info.output = { width: this.size.width, height: this.size.height, fps: this.fps, encoder: this.codec.encoder, codec: this.codec.codec, model: this.size.superResolution ? this.tools.model : null, superResolution: this.size.superResolution, bitrate: this.encodingProfile.average, maxBitrate: this.encodingProfile.peak };
    this.info.processingInfo = { sourceWidth: video.width, sourceHeight: video.height, outputWidth: this.size.width, outputHeight: this.size.height,
      superResolution: this.size.superResolution, superResolutionEnabled: this.input.superResolution !== false, model: this.info.output.model, adRemovalEnabled: this.input.adFilter !== false,
      adRemovalSupported: !!this.mediaSource, codec: this.codec.codec, removedSegments: this.info.removedSegments, removedSeconds: this.info.removedSeconds };
    this.overlayRegions = await this.overlays(video).catch((error) => { if (this.signal.aborted) throw error; this.info.warning = '画面水印检查失败，按原画处理'; return []; });
    this.overlayFilter = delogoFilter(this.overlayRegions);
    Object.assign(this.info.processingInfo, { overlayRemovalEnabled: this.input.overlayRemoval !== false, overlays: this.overlayRegions });
    const outputLabel = this.size.width >= 3840 ? (this.size.height >= 2160 ? '4K' : '4K 宽屏') : `${this.size.height}P`;
    this.label = `电脑${this.size.superResolution ? ' AI 超分' : '转码'} · ${outputLabel}${this.codec.codec === 'hevc' ? ' HEVC' : ''}${this.info.removedSegments ? ` · 过滤 ${this.info.removedSegments} 个广告分片` : ''}${this.overlayRegions.length ? ` · 去水印 ${this.overlayRegions.length} 处` : ''}`;
    const signature = await hash(JSON.stringify({ version: 12, url: this.input.url, headers: this.session.headers, source: this.mediaSource?.signature || (await stat(this.file)).size, audio: this.audioSource?.signature, model: this.tools.modelSignature, size: this.size, fps: this.frameRate, encoder: this.codec.encoder, bitrate: this.encodingProfile, chunk: this.chunkDuration, superResolution: this.input.superResolution !== false, adFilter: this.input.adFilter !== false, adSegments: this.removalLines?.length ? await hash(JSON.stringify(this.removalLines)) : undefined, overlays: this.overlayFilter || undefined }));
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
        // 读取范围覆盖音频预热帧，并再提前 1 秒：TS 分片里分片开头几毫秒的音频常放在上一个分片末尾
        const grid = audioWindow(start, start + duration), from = Math.max(0, grid.from - 1), length = grid.from + grid.length - from;
        const source = this.file ? { file:this.file, offset:from, hls:false } : await this.localWindow(this.mediaSource,from,length,work,'video',controller.signal);
        const audio = this.audioSource ? await this.localWindow(this.audioSource,from,length,work,'audio',controller.signal) : source;
        const overlay = await this.overlayFor(start, duration, controller.signal);
        this.info.sourcePreparationSeconds += (performance.now()-began)/1000;
        return { source,audio,start,duration,grid,from,overlay };
      },controller.signal,priority);
      check(controller.signal);
      return await this.queue.enqueue(async () => {
        check(controller.signal);
        const { source,audio,start,duration,grid,from,overlay } = prepared;
        const encodingBegan = performance.now();
        const options = { env: this.tools.env, signal: controller.signal, timeout: 5 * 60_000 };
        const input = [...this.inputOptions(source.hls, false, source.jumps),...(source.hls ? [] : ['-ss',source.offset.toFixed(6)]),'-i',source.file];
        // 时间戳沿用片中绝对时间，各段首尾相接，播放列表无需 DISCONTINUITY
        const videoSkip = (source.hls ? source.offset : 0) + start - from;
        // 处理链内保持从 0 开始（libplacebo 的帧率转换按 0 起的时间轴生成帧），最后一步再挪到片中时间
        const videoTrim = `trim=start=${videoSkip.toFixed(6)}:duration=${duration.toFixed(6)},setpts=PTS-STARTPTS`, videoTimeline = `setpts=PTS+${start.toFixed(6)}/TB`;
        const audioSkip = (audio.hls ? audio.offset : 0) + grid.from - from, audioLength = grid.length.toFixed(6);
        const audioTimeline = `asetpts=N/SR/TB+${grid.from.toFixed(6)}/TB`, audioKeep = ['-bsf:a', audioKeepFilter(grid)];
        const partial = join(work, 'encoded.ts');
        const encoder = videoEncoderOptions(this.codec.encoder, this.encodingProfile);
        if (this.tools.engine === 'libplacebo-vulkan' || !this.size.superResolution) {
          const upscale = this.size.superResolution;
          if (warming) this.update(upscale ? 'super-resolution' : 'encode', upscale ? '电脑正在 GPU 超分并编码 · FSRCNNX' : '电脑正在编码视频');
          const filter = upscale ? vulkanFilter(this.tools,this.info.input,this.size,this.frameRate) : `fps=${this.frameRate},scale=${this.size.width}:${this.size.height}:flags=lanczos,setsar=1`;
          const separateAudio = this.hasAudio && !!this.audioSource;
          const audioInput = separateAudio ? [...this.inputOptions(audio.hls, false, audio.jumps),...(audio.hls ? [] : ['-ss',audio.offset.toFixed(6)]),'-i',audio.file] : [];
          const audioFilter = `atrim=start=${audioSkip.toFixed(6)}:duration=${audioLength},asetpts=PTS-${audioSkip.toFixed(6)}/TB,aresample=48000:async=1:first_pts=0,apad=whole_dur=${audioLength},atrim=duration=${audioLength},${audioTimeline}`;
          await this.runner(this.tools.ffmpeg,['-hide_banner','-loglevel','error','-threads','2',...(upscale ? vulkanDevice(this.tools) : []),...input,
            ...audioInput,'-map','0:v:0',...(this.hasAudio ? ['-map',`${separateAudio ? 1 : 0}:a:0`,'-c:a','aac','-b:a','128k','-ac','2','-ar','48000','-af',audioFilter,...audioKeep] : ['-an']),
            // 帧数上限放在滤镜里：-frames:v 会在视频结束时提前收尾整个输出，最后一个音频帧缺少后续采样，段间出现约 10 毫秒的声音凹陷
            '-vf',`${videoTrim},${overlay ? overlay + ',' : ''}${filter},trim=end_frame=${Math.ceil(duration*this.fps-.000001)},${videoTimeline}`,
            '-c:v',this.codec.encoder,...encoder,'-pix_fmt','yuv420p','-g',String(this.chunkFrames),'-bf','0','-mpegts_flags','+resend_headers','-muxdelay','0','-muxpreload','0','-f','mpegts','-y',partial],options);
        } else {
          if (this.hasAudio) await this.runner(this.tools.ffmpeg, ['-hide_banner','-loglevel','error','-threads','2',...this.inputOptions(audio.hls, false, audio.jumps),...(audio.hls ? [] : ['-ss',audio.offset.toFixed(6)]),'-i',audio.file,'-vn','-ac','2','-ar','48000','-af',`atrim=start=${audioSkip.toFixed(6)}:duration=${audioLength},asetpts=PTS-${audioSkip.toFixed(6)}/TB,aresample=48000:async=1:first_pts=0,apad=whole_dur=${audioLength},atrim=duration=${audioLength}`,'-c:a','pcm_s16le','-y',join(work,'audio.wav')], options);
          const frames = join(work, 'frames'), enhanced = join(work, 'enhanced'); await mkdir(frames); await mkdir(enhanced);
          if (warming) this.update('decode', '电脑正在解码首段视频');
          await this.runner(this.tools.ffmpeg, ['-hide_banner','-loglevel','error','-threads','2',...input,'-t',duration.toFixed(6),'-an','-vf',`${videoTrim},${overlay ? overlay + ',' : ''}fps=${this.frameRate}`,'-frames:v',String(Math.ceil(duration * this.fps - .000001)),'-fps_mode','cfr','-y',join(frames,'%08d.png')], options);
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
          await this.runner(this.tools.ffmpeg, ['-hide_banner','-loglevel','error','-threads','2','-framerate',this.frameRate,'-i',join(imageDir,this.size.superResolution ? '%08d.jpg' : '%08d.png'),...(this.hasAudio ? ['-i',join(work,'audio.wav')] : []),'-map','0:v:0',...(this.hasAudio ? ['-map','1:a:0','-c:a','aac','-b:a','128k','-ar','48000','-af',audioTimeline,...audioKeep] : ['-an']),'-vf',`setpts=PTS-STARTPTS,${videoTimeline},scale=${this.size.width}:${this.size.height}:flags=lanczos,setsar=1`,'-c:v',this.codec.encoder,...encoder,'-pix_fmt','yuv420p','-g',String(frameCount),'-bf','0','-mpegts_flags','+resend_headers','-muxdelay','0','-muxpreload','0','-f','mpegts','-y',partial], options);
          }
        this.info.encodeSeconds += (performance.now()-encodingBegan)/1000;
        const validationBegan = performance.now();
        const metadata = JSON.parse((await this.runner(this.tools.ffprobe, ['-v','error','-show_streams','-show_format','-of','json',partial], options)).stdout);
        const video = metadata.streams.find((s) => s.codec_type === 'video');
        const sound = metadata.streams.find((s) => s.codec_type === 'audio');
        if (!video || video.codec_name !== this.codec.codec || video.width !== this.size.width || video.height !== this.size.height || this.hasAudio && !sound) throw problem(502, '电脑编码产物验证失败');
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
  /** 预先并行下载之后几段所需的原始分片（不编码）：整集下载时多数 CDN 单连接限速，并行可明显提速 */
  prefetchSource(fromIndex, count, parallel = 4) {
    if (this.file || !this.mediaSource || this.signal.aborted) return;
    const start = fromIndex * this.chunkDuration, length = Math.min(count * this.chunkDuration, this.duration - start);
    if (length <= 0) return;
    const pending = sliceMedia(this.mediaSource, start, length).segments.flatMap((s) => [s.key && { url: s.key.url }, s.map && { url: s.map.url, range: s.map.range }, { url: s.url, range: s.range }]).filter(Boolean);
    let next = 0;
    for (let n = 0; n < parallel; n++) void (async () => {
      while (next < pending.length && !this.signal.aborted) { const item = pending[next++]; await this.raw(item.url, item.range).catch(() => {}); }
    })();
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
    // 片源连接偶尔中断、单次编码偶发失败：播放中的分段自动重试（1、2 秒后），避免一次失败就让设备弹出串流失败
    for (let attempt = 0; ; attempt++) {
      try { const file = await this.segment(index); this.prefetchFrom(index); return file; }
      catch (error) {
        if (this.signal.aborted || error.name === 'AbortError' || [404, 422].includes(error.status) || attempt >= 2) {
          if (!this.signal.aborted && error.name !== 'AbortError') console.error(`[processing ${this.session.id.slice(0,8)}] 分段 ${index} 处理失败：${error.message}${error.diagnostic ? '\n' + error.diagnostic.slice(-1500) : ''}`);
          throw error;
        }
        console.error(`[processing ${this.session.id.slice(0,8)}] 分段 ${index} 第 ${attempt + 1} 次失败，重试：${error.message}`);
        await new Promise((r) => setTimeout(r, 1000 * (attempt + 1))); check(this.signal);
      }
    }
  }
}
