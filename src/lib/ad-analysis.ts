import { looksLikeMp4, parseMp4VideoResolution, parseTsSignature } from './codec-parse-core';
import { stripAdGroups } from './m3u8';

/**
 * 夹杂广告识别（第 1 阶段）。
 *
 * 盗链站插入的广告与正片分别编码、往往托管在别的目录。这里把媒体列表切成「段组」
 * （DISCONTINUITY 边界，或分片目录发生变化），对短段组读一小段前缀：
 * - 编码特征（轨道布局 + 视频尺寸）与正片主体不一致 → 判为广告；
 * - 前缀指纹或「目录 + 特征 + 时长」与用户标记过的广告一致 → 判为广告；
- 同一非正片目录、时长完全相同的短段组重复出现 → 判为轮播插入（不需联网）。
 * 结果只是要删除的分片行（播放列表里的原始文本），由设备 Media3 或电脑服务执行，
 * 两边按同样的行删除，时间轴换算保持一致。分析失败或超时只会少删，不会阻塞播放。
 */

export interface AdTransport {
  fetchText: (url: string, signal: AbortSignal) => Promise<{ text: string; url: string } | undefined>;
  fetchPrefix: (url: string, maxBytes: number, signal: AbortSignal) => Promise<Uint8Array | undefined>;
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
}

export const MAX_SHORT_GROUP = 180;
const CODEC_AD_MAX = 90;
/** 同一非正片目录、时长完全相同的短段组重复出现：盗链站轮播插入的典型形态 */
const REPEAT_AD_MAX = 60;
const MAX_PROBED = 24;
const PREFIX_BYTES = 64 * 1024;

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

async function fingerprint(group: AdGroup, transport: AdTransport, keys: Map<string, Promise<Uint8Array | undefined>>, signal: AbortSignal): Promise<void> {
  if (group.probed) return;
  let buf = await transport.fetchPrefix(group.firstUrl, PREFIX_BYTES, signal);
  if (!buf || buf.length < 188) return;
  if (group.key) {
    if (!keys.has(group.key.uri)) keys.set(group.key.uri, transport.fetchPrefix(group.key.uri, 32, signal));
    const key = await keys.get(group.key.uri);
    if (!key || key.length < 16) return;
    buf = await transport.decrypt(buf.subarray(0, buf.length - buf.length % 16), key, group.key.iv);
  }
  group.hash = prefixHash(buf);
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

async function analyzeMedia(text: string, url: string, transport: AdTransport, budget: AbortSignal): Promise<MediaAnalysis> {
  const { groups, total } = playlistGroups(text, url);
  const analysis: MediaAnalysis = { url, text, total, groups, heuristicLines: heuristicRemovedLines(text) };
  if (groups.length < 2) return analysis;
  const longest = [...groups].sort((a, b) => b.duration - a.duration);
  analysis.contentDir = longest[0].dir;
  const shorts = groups.filter((g) => g.duration <= MAX_SHORT_GROUP);
  if (shorts.length > MAX_PROBED) return analysis;
  const targets = [...new Set([...longest.filter((g) => g.duration > MAX_SHORT_GROUP).slice(0, 3), ...shorts])];
  const keys = new Map<string, Promise<Uint8Array | undefined>>();
  await pool(targets, 4, (group) => fingerprint(group, transport, keys, budget), budget);
  return analysis;
}

/** 读取播放列表并给短段组建指纹；主列表最多看 3 个档位（Media3 可能自适应切换） */
export async function analyzePlaylist(url: string, transport: AdTransport, signal?: AbortSignal, budgetMs = 5000): Promise<MediaAnalysis[]> {
  if (!/\.m3u8([?#]|$)/i.test(url)) return [];
  const budget = withBudget(signal, budgetMs);
  try {
    const first = await transport.fetchText(url, budget.signal);
    if (!first || !first.text.trimStart().startsWith('#EXTM3U')) return [];
    if (!first.text.includes('#EXT-X-STREAM-INF')) return [await analyzeMedia(first.text, first.url, transport, budget.signal)];
    const lines = first.text.split('\n').map((s) => s.trim());
    const variants = lines.flatMap((line, i) => line.startsWith('#EXT-X-STREAM-INF')
      ? [lines.slice(i + 1).find((s) => s && !s.startsWith('#'))].filter((s): s is string => !!s) : [])
      .map((ref) => resolve(first.url, ref)).filter((s): s is string => !!s).slice(0, 3);
    const result: MediaAnalysis[] = [];
    for (const variant of variants) {
      const media = await transport.fetchText(variant, budget.signal).catch(() => undefined);
      if (media?.text.trimStart().startsWith('#EXTM3U')) result.push(await analyzeMedia(media.text, media.url, transport, budget.signal));
    }
    return result;
  } catch { return []; }
  finally { budget.dispose(); }
}

/** 以已建好的指纹做判定；学习库更新后无需重新联网 */
export function decideAds(media: MediaAnalysis[], learned: LearnedAd[]): AdDecision {
  const hashes = new Set(learned.map((l) => l.hash));
  const extra = new Set<string>(), heuristic = new Set<string>();
  let codecGroups = 0, learnedGroups = 0, repeatGroups = 0;
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
  }
  return { extraLines: [...extra], heuristicLines: [...heuristic].filter((line) => !extra.has(line)), codecGroups, learnedGroups, repeatGroups };
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
