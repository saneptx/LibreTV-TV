import { looksLikeMp4, parseMp4VideoResolution, parseTsFirstPts, parseTsSignature } from './codec-parse-core';
import { stripAdGroups } from './m3u8';

/**
 * 夹杂广告识别（第 1 阶段）。
 *
 * 盗链站插入的广告与正片分别编码、往往托管在别的目录。这里把媒体列表切成「段组」
 * （DISCONTINUITY 边界，或分片目录发生变化），对短段组读一小段前缀：
 * - 编码特征（轨道布局 + 视频尺寸）与正片主体不一致 → 判为广告；
 * - 前缀指纹或「目录 + 特征 + 时长」与用户标记过的广告一致 → 判为广告；
 * - 同一非正片目录、时长完全相同的短段组重复出现 → 判为轮播插入（不需联网）；
 * - 时间轴接续：正片各段的时间戳接着上一段往下走，插入的广告自带时间戳，播完后正片又接回插入前的位置。
 *   夹在两段能接上的正片之间、自身接不上的短段组判为广告。只读每组开头几 KB，段组很多的片子也能做。
 * 结果只是要删除的分片行（播放列表里的原始文本），由设备 Media3 或电脑服务执行，
 * 两边按同样的行删除，时间轴换算保持一致。分析失败或超时只会少删，不会阻塞播放。
 */

export interface AdTransport {
  fetchText: (url: string, signal: AbortSignal) => Promise<{ text: string; url: string } | undefined>;
  /** range 为真时用 Range 头只请求前 maxBytes 字节，读完整个响应以便复用连接 */
  fetchPrefix: (url: string, maxBytes: number, signal: AbortSignal, range?: boolean) => Promise<Uint8Array | undefined>;
  decrypt: (buf: Uint8Array, key: Uint8Array, iv: Uint8Array) => Promise<Uint8Array>;
}

export interface LearnedAd { hash: string; sig?: string; dir?: string; duration: number; title?: string; at: number }

export interface AdGroup {
  start: number;
  duration: number;
  lines: string[];
  firstUrl: string;
  dir: string;
  key?: { uri: string; iv: Uint8Array };
  initUrl?: string;
  sig?: string;
  hash?: string;
  probed?: boolean;
  /** 第一个分片开头的显示时间戳（秒）；只对 TS 分片读取 */
  pts?: number;
}

/** text 为分析时读到的媒体列表原文：盗链站按请求动态插入广告，播放器须使用同一份列表，删除行才能对上 */
export interface MediaAnalysis { url: string; text: string; total: number; groups: AdGroup[]; contentDir?: string; heuristicLines: string[] }

export interface AdDecision {
  /** 编码不一致或已学习命中的分片行；设备直连只需要这部分，常规启发式由 Media3 过滤器自己执行 */
  extraLines: string[];
  /** 常规启发式（URL 特征、短插入段、片头段）要删的行；电脑增强没有这套规则，需要一并下发 */
  heuristicLines: string[];
  codecGroups: number;
  learnedGroups: number;
  repeatGroups: number;
  timelineGroups: number;
}

export const MAX_SHORT_GROUP = 180;
const CODEC_AD_MAX = 90;
/** 同一非正片目录、时长完全相同的短段组重复出现：盗链站轮播插入的典型形态 */
const REPEAT_AD_MAX = 60;
const MAX_PROBED = 24;
const PREFIX_BYTES = 64 * 1024;
/** 只读时间戳时的前缀：PAT/PMT 和第一个视频 PES 头通常在前 4 个包里（实测第 564 字节） */
const PTS_BYTES = 4 * 1024;
/** 分层抽查：每轮在一个可疑区间里抽的点数；区间不超过这么多组时整段读完 */
const TIMELINE_SAMPLES = 16;
const MAX_TIMED_READS = 200;
/** 时间戳抽查单独计时：大播放列表本身就要下载好几秒，不和列表下载共用预算 */
const TIMELINE_BUDGET_MS = 4000;
/** 插入广告的总长上限，与编码比对一致 */
const TIMELINE_AD_MAX = 90;
/** 时间戳接续容差：EXTINF 取整与首帧偏移通常在 0.1 秒内 */
const PTS_TOLERANCE = .5;
const PTS_WRAP = 2 ** 33 / 90000;

function resolve(base: string, ref: string): string | undefined {
  try { return new URL(ref, base).href; } catch { return undefined; }
}
function directory(url: string): string {
  try { const u = new URL(url); return u.origin + u.pathname.slice(0, u.pathname.lastIndexOf('/') + 1); } catch { return ''; }
}
function hexIv(value: string): Uint8Array | undefined {
  const hex = value.replace(/^0x/i, '').padStart(32, '0');
  if (!/^[0-9a-f]{32}$/i.test(hex)) return undefined;
  return Uint8Array.from({ length: 16 }, (_, i) => parseInt(hex.slice(i * 2, i * 2 + 2), 16));
}
function sequenceIv(sequence: number): Uint8Array {
  const iv = new Uint8Array(16), seq = sequence >>> 0;
  iv[12] = seq >>> 24; iv[13] = (seq >>> 16) & 0xff; iv[14] = (seq >>> 8) & 0xff; iv[15] = seq & 0xff;
  return iv;
}
/** 两个不同种子的 FNV-1a，拼成 64 位十六进制；只用于识别同一个广告文件的重复投放 */
export function prefixHash(buf: Uint8Array): string {
  let a = 0x811c9dc5, b = 0x01000193 ^ 0x5bd1e995;
  for (let i = 0; i < buf.length; i++) {
    a = Math.imul(a ^ buf[i], 0x01000193);
    b = Math.imul(b ^ buf[i], 0x01000193) ^ (b >>> 13);
  }
  return (a >>> 0).toString(16).padStart(8, '0') + (b >>> 0).toString(16).padStart(8, '0');
}

function segmentLines(text: string): string[] {
  return text.split('\n').map((line) => line.trim()).filter((line) => line && !line.startsWith('#'));
}
/** stripAdGroups 只会整组删除分片，按顺序比对就能得到被删的行 */
export function heuristicRemovedLines(text: string): string[] {
  const before = segmentLines(text), after = segmentLines(stripAdGroups(text)), removed: string[] = [];
  let j = 0;
  for (const line of before) { if (j < after.length && after[j] === line) j++; else removed.push(line); }
  return removed;
}

/** 切分段组；分片目录变化也算边界，但目录逐片变化（散列 CDN）时退回只按 DISCONTINUITY 切分 */
export function playlistGroups(text: string, base: string): { groups: AdGroup[]; total: number } {
  const build = (splitByDir: boolean) => {
    const groups: AdGroup[] = [];
    let sequence = 0, duration = 0, total = 0, boundary = true;
    let key: { uri: string; iv?: Uint8Array } | undefined, initUrl: string | undefined, current: AdGroup | undefined;
    for (const raw of text.split('\n')) {
      const line = raw.trim();
      if (line.startsWith('#EXT-X-MEDIA-SEQUENCE:')) sequence = Number(line.slice(22)) || 0;
      else if (line.startsWith('#EXT-X-KEY:')) {
        const method = line.match(/METHOD=([^,]+)/)?.[1], uri = line.match(/URI="([^"]+)"/)?.[1], iv = line.match(/IV=(0x[0-9a-fA-F]+)/)?.[1];
        const resolved = uri && resolve(base, uri);
        key = method === 'AES-128' && resolved ? { uri: resolved, iv: iv ? hexIv(iv) : undefined } : undefined;
      } else if (line.startsWith('#EXT-X-MAP:')) {
        const uri = line.match(/URI="([^"]+)"/)?.[1];
        initUrl = uri ? resolve(base, uri) : undefined;
      } else if (line === '#EXT-X-DISCONTINUITY') boundary = true;
      else if (line.startsWith('#EXTINF:')) duration = parseFloat(line.slice(8)) || 0;
      else if (line && !line.startsWith('#')) {
        const url = resolve(base, line) || line, dir = directory(url);
        if (!current || boundary || splitByDir && dir !== current.dir) {
          current = { start: total, duration: 0, lines: [], firstUrl: url, dir, initUrl,
            key: key ? { uri: key.uri, iv: key.iv ?? sequenceIv(sequence) } : undefined };
          groups.push(current);
        }
        current.lines.push(line); current.duration += duration; total += duration;
        sequence++; duration = 0; boundary = false;
      }
    }
    return { groups, total };
  };
  const split = build(true);
  return split.groups.filter((g) => g.duration <= MAX_SHORT_GROUP).length > MAX_PROBED ? build(false) : split;
}

async function readPrefix(group: AdGroup, bytes: number, transport: AdTransport, keys: Map<string, Promise<Uint8Array | undefined>>, signal: AbortSignal, range = false): Promise<Uint8Array | undefined> {
  const buf = await transport.fetchPrefix(group.firstUrl, bytes, signal, range);
  if (!buf || buf.length < 188) return undefined;
  if (!group.key) return buf;
  if (!keys.has(group.key.uri)) keys.set(group.key.uri, transport.fetchPrefix(group.key.uri, 32, signal));
  const key = await keys.get(group.key.uri);
  if (!key || key.length < 16) return undefined;
  return transport.decrypt(buf.subarray(0, buf.length - buf.length % 16), key, group.key.iv);
}

async function fingerprint(group: AdGroup, transport: AdTransport, keys: Map<string, Promise<Uint8Array | undefined>>, signal: AbortSignal): Promise<void> {
  if (group.probed) return;
  const buf = await readPrefix(group, PREFIX_BYTES, transport, keys, signal);
  if (!buf) return;
  group.hash = prefixHash(buf);
  if (!group.initUrl && !looksLikeMp4(buf)) group.pts = parseTsFirstPts(buf);
  if (group.initUrl || looksLikeMp4(buf)) {
    const init = group.initUrl ? await transport.fetchPrefix(group.initUrl, PREFIX_BYTES, signal) : buf;
    const size = init ? parseMp4VideoResolution(init) : undefined;
    group.sig = size ? `mp4|${size.width}x${size.height}` : undefined;
  } else group.sig = parseTsSignature(buf);
  group.probed = true;
}

async function pool<T>(items: T[], concurrency: number, task: (item: T) => Promise<void>, signal: AbortSignal) {
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (next < items.length && !signal.aborted) await task(items[next++]).catch(() => undefined);
  }));
}

function withBudget(signal: AbortSignal | undefined, ms: number) {
  const controller = new AbortController(), abort = () => controller.abort();
  const timer = setTimeout(abort, ms);
  if (signal?.aborted) controller.abort(); else signal?.addEventListener('abort', abort, { once: true });
  return { signal: controller.signal, dispose: () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); } };
}

async function analyzeMedia(text: string, url: string, transport: AdTransport, budget: AbortSignal, parent?: AbortSignal, timeline = true): Promise<MediaAnalysis> {
  const { groups, total } = playlistGroups(text, url);
  const analysis: MediaAnalysis = { url, text, total, groups, heuristicLines: heuristicRemovedLines(text) };
  if (groups.length < 2) return analysis;
  const longest = [...groups].sort((a, b) => b.duration - a.duration);
  analysis.contentDir = longest[0].dir;
  const shorts = groups.filter((g) => g.duration <= MAX_SHORT_GROUP);
  const keys = new Map<string, Promise<Uint8Array | undefined>>();
  if (shorts.length <= MAX_PROBED) {
    const targets = [...new Set([...longest.filter((g) => g.duration > MAX_SHORT_GROUP).slice(0, 3), ...shorts])];
    await pool(targets, 4, (group) => fingerprint(group, transport, keys, budget), budget);
  }
  // fMP4 分片没有 PES 时间戳，跳过
  if (timeline && groups.every((g) => !g.initUrl) && !parent?.aborted) {
    const timeline = withBudget(parent, TIMELINE_BUDGET_MS);
    try {
      await probeTimeline(groups, async (group) => {
        const buf = await readPrefix(group, PTS_BYTES, transport, keys, timeline.signal, true);
        if (buf && !looksLikeMp4(buf)) group.pts = parseTsFirstPts(buf);
      }, timeline.signal);
    } finally { timeline.dispose(); }
  }
  return analysis;
}

/**
 * 读取播放列表并给短段组建指纹；主列表最多看 3 个档位（Media3 可能自适应切换）。
 * timeline 为假时不读时间戳：电脑串流由电脑按同一规则检测，电脑的网络比投影仪快得多。
 */
export async function analyzePlaylist(url: string, transport: AdTransport, signal?: AbortSignal, budgetMs = 5000, { timeline = true }: { timeline?: boolean } = {}): Promise<MediaAnalysis[]> {
  if (!/\.m3u8([?#]|$)/i.test(url)) return [];
  const budget = withBudget(signal, budgetMs);
  try {
    const first = await transport.fetchText(url, budget.signal);
    if (!first || !first.text.trimStart().startsWith('#EXTM3U')) return [];
    if (!first.text.includes('#EXT-X-STREAM-INF')) return [await analyzeMedia(first.text, first.url, transport, budget.signal, signal, timeline)];
    const lines = first.text.split('\n').map((s) => s.trim());
    const variants = lines.flatMap((line, i) => line.startsWith('#EXT-X-STREAM-INF')
      ? [lines.slice(i + 1).find((s) => s && !s.startsWith('#'))].filter((s): s is string => !!s) : [])
      .map((ref) => resolve(first.url, ref)).filter((s): s is string => !!s).slice(0, 3);
    const result: MediaAnalysis[] = [];
    for (const variant of variants) {
      const media = await transport.fetchText(variant, budget.signal).catch(() => undefined);
      if (media?.text.trimStart().startsWith('#EXTM3U')) result.push(await analyzeMedia(media.text, media.url, transport, budget.signal, signal, timeline));
    }
    return result;
  } catch { return []; }
  finally { budget.dispose(); }
}

/**
 * 只读段组开头的时间戳，按需分层抽查。正片的「时间戳 − 原片位置」在两次插入之间不变，每插一段广告整体下移一次；
 * 先在全片均匀抽点，相邻两点的差值不一致（或读不到）的区间再细分，区间够小就整段读完。
 * 请求数约为 16 + 每处插入十几个，与段组总数基本无关。每个请求只读 4 KB，耗时主要在往返上，并发与设备端网络线程数一致。
 */
async function probeTimeline(groups: AdGroup[], read: (group: AdGroup) => Promise<void>, signal: AbortSignal): Promise<void> {
  const tried = new Set<AdGroup>();
  let reads = 0;
  const offset = (g: AdGroup) => g.pts === undefined ? undefined : g.pts - g.start;
  const same = (a: AdGroup, b: AdGroup) => {
    const x = offset(a), y = offset(b);
    return x !== undefined && y !== undefined && Math.abs(ptsDelta(x, y)) <= PTS_TOLERANCE;
  };
  let intervals: [number, number][] = groups.length > 1 ? [[0, groups.length - 1]] : [];
  while (intervals.length && !signal.aborted && reads < MAX_TIMED_READS) {
    const points = new Set<number>(), next: [number, number][] = [], sampled: number[][] = [];
    for (const [a, b] of intervals) {
      const count = b - a + 1;
      const picks = count <= TIMELINE_SAMPLES ? Array.from({ length: count }, (_, i) => a + i)
        : Array.from({ length: TIMELINE_SAMPLES }, (_, i) => a + Math.round(i * (b - a) / (TIMELINE_SAMPLES - 1)));
      picks.forEach((i) => points.add(i)); sampled.push(count <= TIMELINE_SAMPLES ? [] : picks);
    }
    const todo = [...points].map((i) => groups[i]).filter((g) => g.pts === undefined && !tried.has(g)).slice(0, MAX_TIMED_READS - reads);
    todo.forEach((g) => tried.add(g)); reads += todo.length;
    await pool(todo, 8, read, signal);
    for (const picks of sampled) for (let k = 1; k < picks.length; k++) {
      const [a, b] = [picks[k - 1], picks[k]];
      if (b - a > 1 && !same(groups[a], groups[b])) next.push([a, b]);
    }
    intervals = next;
  }
}

/** 两个时间戳之差，按 33 位时钟回绕取最近的一个 */
function ptsDelta(a: number, b: number): number {
  const d = (a - b) % PTS_WRAP;
  return d > PTS_WRAP / 2 ? d - PTS_WRAP : d < -PTS_WRAP / 2 ? d + PTS_WRAP : d;
}

/**
 * 时间轴接续判定：沿播放列表走，记住最近一段正片的时间戳。某组接不上时，向后找第一个「接回插入前位置」的组；
 * 找到且中间这些组总长不超过上限，它们就是插进来的广告。找不到说明时间轴本身换了（多个文件拼接），以新位置为准继续。
 * 插入点前面的连续正片不足上限时不判定：「开头一段广告 + 正片」与「正片开头一段 + 插入」只看时间戳完全一样，判错会删掉正片。
 */
export function timelineAdGroups(groups: AdGroup[], total: number): AdGroup[][] {
  const spans: AdGroup[][] = [];
  let anchor: AdGroup | undefined, origin = 0;
  for (let i = 0; i < groups.length; i++) {
    const g = groups[i];
    // 没读时间戳的组跳过：若其中夹着广告，后面的正片就接不上锚点，不会误判
    if (g.pts === undefined) continue;
    if (!anchor) { anchor = g; origin = g.start; continue; }
    const resume = anchor.pts! + g.start - anchor.start;
    if (Math.abs(ptsDelta(g.pts, resume)) <= PTS_TOLERANCE) { anchor = g; continue; }
    let j = i + 1, inserted = g.duration;
    while (j < groups.length && inserted <= TIMELINE_AD_MAX && groups[j].pts !== undefined
      && Math.abs(ptsDelta(groups[j].pts!, resume)) > PTS_TOLERANCE) inserted += groups[j++].duration;
    const back = groups[j];
    if (back?.pts !== undefined && inserted <= TIMELINE_AD_MAX && inserted <= total * .15 && g.start - origin > TIMELINE_AD_MAX && Math.abs(ptsDelta(back.pts, resume)) <= PTS_TOLERANCE) {
      spans.push(groups.slice(i, j)); anchor = back; i = j;
    } else { anchor = g; origin = g.start; }
  }
  return spans;
}

/** 以已建好的指纹做判定；学习库更新后无需重新联网 */
export function decideAds(media: MediaAnalysis[], learned: LearnedAd[]): AdDecision {
  const hashes = new Set(learned.map((l) => l.hash));
  const extra = new Set<string>(), heuristic = new Set<string>();
  let codecGroups = 0, learnedGroups = 0, repeatGroups = 0, timelineGroups = 0;
  for (const m of media) {
    m.heuristicLines.forEach((line) => heuristic.add(line));
    // 主体特征以已探测到的长段为准；最长段必须与之一致，否则不做编码判定
    const bySig = new Map<string, number>();
    const probed = m.groups.filter((g) => g.sig), long = probed.filter((g) => g.duration > MAX_SHORT_GROUP);
    let probedLong = 0;
    for (const g of long.length ? long : probed) { bySig.set(g.sig!, (bySig.get(g.sig!) || 0) + g.duration); probedLong += g.duration; }
    const [main, mainSeconds = 0] = [...bySig.entries()].sort((a, b) => b[1] - a[1])[0] || [];
    const longest = m.groups.reduce<AdGroup | undefined>((a, g) => !a || g.duration > a.duration ? g : a, undefined);
    const reliable = !!main && mainSeconds >= probedLong * .7 && longest?.sig === main;
    const repeats = new Map<string, number>();
    for (const g of m.groups) if (g.duration > 0 && g.duration <= REPEAT_AD_MAX && g.dir !== m.contentDir) {
      const key = `${g.dir}|${g.duration.toFixed(2)}`; repeats.set(key, (repeats.get(key) || 0) + 1);
    }
    for (const g of m.groups) {
      if (g.duration > MAX_SHORT_GROUP || g.duration <= 0) continue;
      const byCodec = reliable && !!g.sig && g.sig !== main && g.duration <= CODEC_AD_MAX && g.duration <= m.total * .15;
      const byLearning = !!g.hash && hashes.has(g.hash) || learned.some((l) => !!l.dir && l.dir === g.dir && g.dir !== m.contentDir
        && !!l.sig && l.sig === g.sig && Math.abs(l.duration - g.duration) <= 1.5);
      const byRepeat = g.dir !== m.contentDir && g.duration <= REPEAT_AD_MAX && (repeats.get(`${g.dir}|${g.duration.toFixed(2)}`) || 0) >= 2;
      if (!byCodec && !byLearning && !byRepeat) continue;
      if (byLearning) learnedGroups++; else if (byCodec) codecGroups++; else repeatGroups++;
      g.lines.forEach((line) => extra.add(line));
    }
    for (const span of timelineAdGroups(m.groups, m.total)) {
      if (span.every((g) => g.lines.every((line) => extra.has(line)))) continue;
      timelineGroups += span.length;
      span.forEach((g) => g.lines.forEach((line) => extra.add(line)));
    }
  }
  return { extraLines: [...extra], heuristicLines: [...heuristic].filter((line) => !extra.has(line)), codecGroups, learnedGroups, repeatGroups, timelineGroups };
}

/** 用户在广告播放时标记：取原片时间所在的短段组，必要时补建指纹 */
export async function learnAdAt(media: MediaAnalysis[], position: number, transport: AdTransport, signal?: AbortSignal): Promise<{ group: AdGroup; entry: LearnedAd; contentDir?: string }> {
  const m = media[0];
  if (!m || m.groups.length < 2) throw new Error('这段广告与正片在同一个编码段里，暂时无法按分片识别');
  const group = m.groups.find((g) => position >= g.start - .5 && position < g.start + g.duration + .5);
  if (!group) throw new Error('没有找到当前播放位置对应的片段');
  if (group.duration > MAX_SHORT_GROUP || group.dir === m.contentDir && group.duration >= Math.max(...m.groups.map((g) => g.duration)))
    throw new Error('当前片段较长，更像正片，未做标记');
  const budget = withBudget(signal, 8000);
  try { await fingerprint(group, transport, new Map(), budget.signal); } finally { budget.dispose(); }
  if (!group.hash) throw new Error('读取当前片段失败，请稍后重试');
  return { group, contentDir: m.contentDir, entry: { hash: group.hash, sig: group.sig, dir: group.dir !== m.contentDir ? group.dir : undefined, duration: Math.round(group.duration * 10) / 10, at: Date.now() } };
}

const LEARNED_KEY = 'libretv-learned-ads-v1';
const LEARNED_LIMIT = 300;
export function loadLearnedAds(): LearnedAd[] {
  try {
    const value = JSON.parse(localStorage.getItem(LEARNED_KEY) || '[]');
    return Array.isArray(value) ? value.filter((v) => v && typeof v.hash === 'string' && Number.isFinite(v.duration)) : [];
  } catch { return []; }
}
export function saveLearnedAd(entry: LearnedAd): LearnedAd[] {
  const list = [entry, ...loadLearnedAds().filter((l) => l.hash !== entry.hash)].slice(0, LEARNED_LIMIT);
  try { localStorage.setItem(LEARNED_KEY, JSON.stringify(list)); } catch { /* 存储满时本次标记仍对当前播放生效 */ }
  return list;
}
export function clearLearnedAds(): void { try { localStorage.removeItem(LEARNED_KEY); } catch { /* 忽略 */ } }
