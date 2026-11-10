// 多来源比对（第 2 阶段）：同一集的两个来源对齐音频，找出只在当前来源里出现、且与正片编在一起的插入段。
//
// 设 o(t) = 参考来源时间 − 当前来源时间。没有插入时 o 恒定；当前来源在 t 处多出 L 秒，
// 之后的 o 会减少 L，而插入段本身在参考里找不到。用二分法定位 o 的跳变，每个插入约十几次测量，
// 每次只解码几秒音频。插入长度直接取跳变量（相关性给出的偏移很精确），并用找不到匹配的区间交叉核对。
import { mkdir, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { parseMedia, problem } from './hls.mjs';
import { SourceReader, compactRemovals, readPlaylist } from './processing.mjs';
import { check } from './runtime.mjs';

export const RATE = 8000;
const FRAME = 256, HOP = 128, BANDS = 16;
export const NEEDLE_SECONDS = 4;
const MATCH_SCORE = .45;

function fft(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) { [re[i], re[j]] = [re[j], re[i]]; [im[i], im[j]] = [im[j], im[i]]; }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const angle = -2 * Math.PI / len, wr = Math.cos(angle), wi = Math.sin(angle);
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0;
      for (let k = 0; k < len / 2; k++) {
        const a = i + k, b = a + len / 2, tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
        re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
        const next = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = next;
      }
    }
  }
}
const window = Float64Array.from({ length: FRAME }, (_, i) => .5 - .5 * Math.cos(2 * Math.PI * i / (FRAME - 1)));
const edges = Array.from({ length: BANDS + 1 }, (_, i) => Math.round(150 * Math.pow(3800 / 150, i / BANDS) / (RATE / FRAME)));

/** 每 16 ms 一帧、16 个对数频带能量的时间差分；差分对音量和编码差异不敏感，无关音频的相关接近 0 */
export function audioFeatures(samples) {
  const frames = Math.max(0, Math.floor((samples.length - FRAME) / HOP) + 1);
  const energy = new Float64Array(frames * BANDS), re = new Float64Array(FRAME), im = new Float64Array(FRAME);
  for (let f = 0; f < frames; f++) {
    for (let i = 0; i < FRAME; i++) { re[i] = samples[f * HOP + i] * window[i]; im[i] = 0; }
    fft(re, im);
    for (let b = 0; b < BANDS; b++) {
      let sum = 0;
      for (let k = edges[b]; k < Math.max(edges[b + 1], edges[b] + 1); k++) sum += re[k] * re[k] + im[k] * im[k];
      energy[f * BANDS + b] = Math.log(sum + 1e-6);
    }
  }
  const delta = new Float32Array(Math.max(0, frames - 1) * BANDS);
  for (let i = 0; i < delta.length; i++) delta[i] = energy[i + BANDS] - energy[i];
  return { frames: Math.max(0, frames - 1), data: delta };
}
export const frameSeconds = HOP / RATE;

/** 片段在长窗口中的最佳位置（Pearson 相关），返回帧偏移和得分 */
export function bestMatch(needle, hay) {
  const n = needle.frames * BANDS;
  if (!needle.frames || hay.frames < needle.frames) return { lag: -1, score: 0 };
  let mean = 0; for (let i = 0; i < n; i++) mean += needle.data[i]; mean /= n;
  const centered = new Float64Array(n); let norm = 0;
  for (let i = 0; i < n; i++) { centered[i] = needle.data[i] - mean; norm += centered[i] * centered[i]; }
  if (norm < 1e-6) return { lag: -1, score: 0 };
  const prefix = new Float64Array(hay.frames + 1), prefix2 = new Float64Array(hay.frames + 1);
  for (let f = 0; f < hay.frames; f++) {
    let s = 0, s2 = 0;
    for (let b = 0; b < BANDS; b++) { const v = hay.data[f * BANDS + b]; s += v; s2 += v * v; }
    prefix[f + 1] = prefix[f] + s; prefix2[f + 1] = prefix2[f] + s2;
  }
  let best = { lag: -1, score: -1 };
  for (let lag = 0; lag + needle.frames <= hay.frames; lag++) {
    const sy = prefix[lag + needle.frames] - prefix[lag], syy = prefix2[lag + needle.frames] - prefix2[lag];
    const variance = syy - sy * sy / n;
    if (variance < 1e-6) continue;
    let sxy = 0; const offset = lag * BANDS;
    for (let i = 0; i < n; i++) sxy += centered[i] * hay.data[offset + i];
    const score = sxy / Math.sqrt(norm * variance);
    if (score > best.score) best = { lag, score };
  }
  return best;
}

/** 数值稳定的「是否相同偏移」：允许 0.6 秒误差（含编码起始差和帧量化） */
const same = (a, b) => a.none && b.none || !a.none && !b.none && Math.abs(a.offset - b.offset) < .6;

/**
 * 在 [start, end] 上定位偏移跳变。measure(t, low, high) 返回 { offset } 或 { none }，
 * low/high 为参考偏移的搜索范围。返回当前来源中多出来的区间 [{ start, duration }]（当前来源时间）。
 */
export async function findInsertions(measure, start, end, initial, final, { resolution = .5, maxInsert = 180, signal } = {}) {
  const points = [{ t: start, s: initial }, { t: end, s: final }];
  // 统一用首尾偏移围成的范围：中间点即使落在静音处，也不会把两侧的偏移误判为「找不到」
  const low = Math.min(initial.offset, final.offset) - 2, high = Math.max(initial.offset, final.offset) + 2;
  const refine = async (l, r) => {
    check(signal);
    if (same(l.s, r.s) || r.t - l.t <= resolution) return;
    const t = (l.t + r.t) / 2;
    const mid = { t, s: await measure(t, low, high) };
    points.push(mid);
    await refine(l, mid); await refine(mid, r);
  };
  await refine(points[0], points[1]);
  points.sort((a, b) => a.t - b.t);
  const result = [];
  for (let i = 0; i < points.length; i++) {
    if (points[i].s.none || !points[i + 1]?.s.none) continue;
    let j = i + 1; while (j < points.length && points[j].s.none) j++;
    if (j >= points.length) break;
    const before = points[i], after = points[j], drop = before.s.offset - after.s.offset;
    // 当前来源多出 drop 秒；找不到匹配的区间应约为 [插入起点 − 片段长度, 插入终点]
    if (drop >= 3 && drop <= maxInsert) {
      // 片段与广告部分重叠时仍可能匹配：左侧估计偏晚、右侧偏早，取两者中点
      const fromRight = after.t - drop, fromLeft = before.t + NEEDLE_SECONDS;
      if (Math.abs(fromRight - fromLeft) <= NEEDLE_SECONDS + resolution * 2 + 1) result.push({ start: Math.max(0, (fromRight + fromLeft) / 2), duration: drop, before: before.s.offset, after: after.s.offset });
    }
    i = j - 1;
  }
  return result;
}

/** 当前来源（清理后）时间 → 原片时间 */
function originalPosition(position, removals) {
  let result = position, removed = 0;
  for (const r of removals) { if (position + .00001 >= r.start - removed) result += r.duration; removed += r.duration; }
  return result;
}

export class CompareJob extends SourceReader {
  constructor(engine, session, input, network) {
    super(engine, session, input, network);
    this.info = { state: 'running', message: '电脑正在读取两个来源的清单', measurements: 0, rawCacheHits: 0 };
    this.running = this.run().catch((error) => {
      if (this.signal.aborted) return;
      this.info.state = 'error'; this.info.message = this.info.error = error.status ? error.message : '多来源比对失败';
    }).finally(() => this.release());
  }
  close() { this.controller.abort(); this.release(); }
  status() { return { ...this.info }; }
  async playlist(url, provided) {
    let target = url;
    for (let depth = 0; depth < 5; depth++) {
      let body, base;
      if (provided?.has(target)) { body = provided.get(target); base = target; }
      else { const result = await this.network.open(target, undefined, this.signal); body = await readPlaylist(result.response); base = result.url; if (provided?.has(base)) body = provided.get(base); }
      const variant = body.includes('#EXT-X-STREAM-INF') ? body.split(/\r?\n/).map((s) => s.trim()).find((s) => s && !s.startsWith('#')) : undefined;
      if (!variant) return { body, base };
      target = new URL(variant, base).href;
    }
    throw problem(422, '视频主列表嵌套过深');
  }
  async audio(media, start, seconds, name) {
    start = Math.max(0, Math.min(start, media.duration - seconds));
    const dir = join(this.root, 'work', this.session.id, name); await mkdir(dir, { recursive: true, mode: 0o700 });
    try {
      const window = await this.localWindow(media, start, Math.min(seconds, media.duration - start), dir, 'audio');
      const { stdout } = await this.runner(this.tools.ffmpeg, ['-hide_banner', '-loglevel', 'error', ...this.inputOptions(true, true), '-i', window.file,
        '-ss', window.offset.toFixed(3), '-t', seconds.toFixed(3), '-vn', '-ac', '1', '-ar', String(RATE), '-f', 'f32le', 'pipe:1'],
        { env: this.tools.env, signal: this.signal, timeout: 60_000, binary: true, maxBytes: (seconds + 2) * RATE * 4 });
      return { start, samples: new Float32Array(stdout.buffer, stdout.byteOffset, Math.floor(stdout.length / 4)) };
    } finally { await rm(dir, { recursive: true, force: true }); }
  }
  async measure(primary, reference, t, low, high) {
    check(this.signal); this.info.measurements++;
    if (this.info.measurements > 240) throw problem(422, '比对测量次数过多，已停止');
    if (Date.now() > this.deadline) throw problem(504, '多来源比对耗时过长，已停止');
    const needle = await this.audio(primary, t, NEEDLE_SECONDS, `p${this.info.measurements}`);
    const features = audioFeatures(needle.samples);
    let energy = 0; for (const v of needle.samples) energy += v * v;
    if (energy / Math.max(1, needle.samples.length) < 1e-6) return { none: true, silent: true };
    const from = needle.start + low - 1, seconds = high - low + NEEDLE_SECONDS + 2;
    if (from + seconds < 0 || from > reference.duration) return { none: true };
    const hay = await this.audio(reference, from, Math.min(seconds, 300), `r${this.info.measurements}`);
    const match = bestMatch(features, audioFeatures(hay.samples));
    return match.score >= MATCH_SCORE ? { offset: hay.start + match.lag * frameSeconds - needle.start, score: match.score } : { none: true };
  }
  /** 逐帧相似度：正片与参考在已知偏移下对齐，相似度骤降处即插入段边界。side=1 求终点（之后匹配），-1 求起点（之前匹配） */
  async edge(primary, reference, approx, offset, side) {
    const span = 12, from = approx - span / 2;
    const p = await this.audio(primary, from, span, `e${this.info.measurements++}`);
    const r = await this.audio(reference, p.start + offset - 1, span + 2, `f${this.info.measurements++}`);
    const pf = audioFeatures(p.samples), rf = audioFeatures(r.samples);
    // 用确定匹配的一侧求精确帧偏移
    const half = Math.floor(pf.frames / 2), known = side > 0 ? { frames: pf.frames - half - 31, data: pf.data.subarray((half + 31) * BANDS) } : { frames: half - 31, data: pf.data.subarray(0, (half - 31) * BANDS) };
    const match = bestMatch(known, rf);
    if (match.score < MATCH_SCORE) return undefined;
    const lag = match.lag - (side > 0 ? half + 31 : 0), win = 31, sim = [];
    for (let f = 0; f + win <= pf.frames; f++) {
      const q = f + lag; if (q < 0 || q + win > rf.frames) { sim.push(NaN); continue; }
      sim.push(bestMatch({ frames: win, data: pf.data.subarray(f * BANDS, (f + win) * BANDS) }, { frames: win, data: rf.data.subarray(q * BANDS, (q + win) * BANDS) }).score);
    }
    // 从确定匹配的一侧向内扫描，连续 0.3 秒相似度低于 0.3 处为边界
    const order = side > 0 ? [...sim.keys()].reverse() : [...sim.keys()];
    let low = 0;
    for (const f of order) {
      if (!Number.isFinite(sim[f])) continue;
      low = sim[f] < .3 ? low + 1 : 0;
      if (low >= 19) { const boundary = side > 0 ? f + low + win - 1 : f - low + 1; return p.start + boundary * frameSeconds; }
    }
    return undefined;
  }
  async refine(primary, reference, r) {
    const end = await this.edge(primary, reference, r.start + r.duration, r.after, 1).catch(() => undefined);
    const start = await this.edge(primary, reference, r.start, r.before, -1).catch(() => undefined);
    // 长度以偏移跳变为准（精确）；两端估计一致时取其均值，否则以可得的一端为准
    const fromEnd = end === undefined ? undefined : end - r.duration, candidates = [fromEnd, start].filter((v) => v !== undefined && Math.abs(v - r.start) <= 3);
    const begin = candidates.length ? candidates.reduce((a, b) => a + b, 0) / candidates.length : r.start;
    return { start: Math.max(0, begin), duration: r.duration };
  }
  async anchor(primary, reference, t, expected, width) {
    for (let attempt = 0; attempt < 4; attempt++, t += 25) {
      const s = await this.measure(primary, reference, t, expected - width, expected + width);
      if (!s.none) return { t, s };
    }
    return undefined;
  }
  async compare(primary, reference) {
    const head = Math.min(60, primary.duration * .05), tail = primary.duration - Math.max(40, primary.duration * .03) - NEEDLE_SECONDS;
    const first = await this.anchor(primary, reference, head, 0, 30);
    if (!first) throw problem(422, '两个来源找不到相同的音频，可能不是同一集');
    const expected = first.s.offset + reference.duration - primary.duration;
    const last = await this.anchor(primary, reference, tail, expected, Math.min(240, Math.abs(expected - first.s.offset) + 30));
    if (!last || last.t <= first.t) throw problem(422, '两个来源的片尾无法对齐');
    this.info.message = '电脑正在定位两个来源的差异';
    const found = await findInsertions((t, low, high) => this.measure(primary, reference, t, low, high), first.t, last.t, first.s, last.s, { signal: this.signal });
    this.info.message = '电脑正在精确定位插入段边界';
    const refined = [];
    for (const r of found) refined.push(await this.refine(primary, reference, r));
    return refined;
  }
  async run() {
    this.deadline = Date.now() + 8 * 60_000;
    const provided = new Map((this.input.primary.playlists || []).map((p) => [p.url, p.text]));
    const load = async (devicePlaylist) => {
      const list = await this.playlist(this.input.primary.url, devicePlaylist ? provided : undefined);
      // 设备标记的行只对设备那份列表有效；改用电脑自己下载的列表时，以电脑的重复插入规则补上
      return parseMedia(list.body, list.base, { adFilter: this.input.adFilter !== false, adSegments: devicePlaylist ? this.input.adSegments : undefined, repeatFilter: !devicePlaylist || !this.input.adSegments?.length });
    };
    let usingDevice = provided.size > 0, primary = await load(usingDevice);
    const verdicts = [];
    for (const [n, url] of this.input.references.entries()) {
      this.info.message = `电脑正在比对第 ${n + 1} 个参考来源`;
      try {
        const list = await this.playlist(url);
        const reference = parseMedia(list.body, list.base, { adFilter: true, repeatFilter: true });
        try { verdicts.push(await this.compare(primary, reference)); }
        catch (error) {
          // 部分片源的分片地址按请求 IP 签名：设备给的列表在电脑（如经代理出口）上会被拒，改用电脑自己下载的列表重试
          if (!usingDevice || this.signal.aborted || !/HTTP 40[34]/.test(error.message)) throw error;
          usingDevice = false; primary = await load(false); this.info.devicePlaylistRejected = true;
          verdicts.push(await this.compare(primary, reference));
        }
      } catch (error) {
        if (this.signal.aborted) throw error;
        this.info.warning = error.message;
        console.error(`[compare ${this.session.id.slice(0, 8)}] 参考来源 ${new URL(url).host} 比对失败：${error.message}${this.info.devicePlaylistRejected ? '（已改用电脑自己下载的列表）' : ''}`);
      }
      if (verdicts.length >= 2) break;
    }
    const removals = compactRemovals(primary.removed);
    if (!verdicts.length) throw problem(422, this.info.warning || '没有可用的参考来源');
    // 两个参考都可用时，只保留两边都认定的插入，避免把参考来源剪掉的片段当成广告。
    const agreed = verdicts.length < 2 ? verdicts[0] : verdicts[0].filter((a) => verdicts[1].some((b) => Math.min(a.start + a.duration, b.start + b.duration) - Math.max(a.start, b.start) >= Math.min(a.duration, b.duration) * .7));
    this.info.intervals = agreed.map((r) => ({ start: Math.round(originalPosition(r.start, removals) * 100) / 100, duration: Math.round(r.duration * 100) / 100 }));
    this.info.references = verdicts.length;
    this.info.state = 'done'; this.info.message = agreed.length ? `多来源比对发现 ${agreed.length} 段插入` : '多来源比对未发现插入';
  }
}
