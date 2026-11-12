// 画面核对插播：有的片源把广告重新打上与正片衔接的时间戳（时间戳只在广告结束处倒退），时间戳规则给不出准确的起止分片。
// 时间戳负责「在哪里、多长」：正片的「时间戳 − 原片位置」在插入处下降 D 秒，D 就是插入长度；
// 画面负责「是哪几片」：在下降点附近逐片取关键帧小图，与窗口外的正片参照帧比较，
// 紧挨下降点、与参照差异都很大、总长接近 D 的一串连续分片判为广告。赌博广告是大面积高饱和画面，与剧集场景差异很大。
export const THUMB_WIDTH = 64, THUMB_HEIGHT = 36;
/** 画面差异阈值：实测广告 0.77–1.10，正片 0.20–0.61（爱情公寓3、爱死机） */
export const AD_DISTANCE = .7;
const PTS_WRAP = 2 ** 33 / 90000;
const ptsDelta = (a, b) => { const d = (a - b) % PTS_WRAP; return d > PTS_WRAP / 2 ? d - PTS_WRAP : d < -PTS_WRAP / 2 ? d + PTS_WRAP : d; };

/** 媒体列表里的每个分片（原文行、地址、原片起点与时长）；加密或 fMP4 列表返回空 */
export function mediaSegments(text, base) {
  if (/#EXT-X-(KEY:METHOD=(?!NONE)|MAP:)/.test(text)) return [];
  const out = []; let duration = 0, start = 0;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith('#EXTINF:')) duration = Number(line.slice(8).split(',')[0]) || 0;
    else if (line && !line.startsWith('#')) {
      let url; try { url = new URL(line, base).href; } catch { return []; }
      out.push({ line, url, start, duration }); start += duration; duration = 0;
    }
  }
  return out;
}

/**
 * 时间戳规则没有处理的「偏移下降」：相邻两个读到时间戳的段组，后者的「时间戳 − 原片位置」比前者小 1–95 秒。
 * at 为下降处（后一组的原片起点），drop 为下降量。已被时间戳规则删除的插入不再返回。
 */
export function timelineDrops(groups, removed = new Set()) {
  const known = groups.filter((g) => g.pts !== undefined), drops = [];
  for (let k = 1; k < known.length; k++) {
    const a = known[k - 1], b = known[k];
    const drop = ptsDelta(a.pts - a.start, b.pts - b.start);
    if (drop < 1 || drop > 95) continue;
    // 时间戳规则删掉的插入就在 a 与 b 之间
    if (groups.slice(groups.indexOf(a), groups.indexOf(b) + 1).some((g) => g.lines.some((line) => removed.has(line)))) continue;
    drops.push({ at: b.start, after: a.start, drop });
  }
  return drops;
}

/** 下降点附近要取画面的分片，以及窗口外前后各 3 片正片参照（隔几片取，避开紧挨窗口的） */
export function dropWindow(segments, { at, after, drop }) {
  const from = Math.max(0, Math.min(after, at - drop - 12)), to = at + drop + 12;
  const inside = segments.map((s, i) => i).filter((i) => segments[i].start + segments[i].duration > from && segments[i].start < to);
  if (!inside.length) return { inside, refs: [] };
  const first = inside[0], last = inside.at(-1), refs = [];
  for (let k = 1; k <= 3; k++) { const i = first - k * 3; if (i >= 0) refs.push(i); }
  for (let k = 1; k <= 3; k++) { const i = last + k * 3; if (i < segments.length) refs.push(i); }
  return { inside, refs };
}

/** 64×36 RGB 小图的特征：4×4×4 颜色直方图 + 8×4 亮度布局 */
export function thumbFeatures(rgb) {
  const hist = new Float32Array(64), layout = new Float32Array(32), w = THUMB_WIDTH, h = THUMB_HEIGHT;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = (y * w + x) * 3, r = rgb[i], g = rgb[i + 1], b = rgb[i + 2];
    hist[(r >> 6) * 16 + (g >> 6) * 4 + (b >> 6)] += 1 / (w * h);
    layout[Math.floor(y * 4 / h) * 8 + Math.floor(x * 8 / w)] += (r * 299 + g * 587 + b * 114) / 1000 / 255 / (w * h / 32);
  }
  return { hist, layout };
}
/** 两帧的画面差异：颜色分布差（0–1）+ 平均亮度布局差（0–1） */
export function thumbDistance(a, b) {
  let hist = 0, layout = 0;
  for (let k = 0; k < 64; k++) hist += Math.abs(a.hist[k] - b.hist[k]);
  for (let k = 0; k < 32; k++) layout += Math.abs(a.layout[k] - b.layout[k]);
  return hist / 2 + layout / 32;
}

/**
 * 在窗口里选插播：枚举一端紧挨下降点（结束于下降点前一片之内，或从下降点开始）、总长接近下降量（±max(4 秒, 30%)）的连续分片，
 * 取「片段内平均差异 − 两侧各两片的平均差异」最大的一段。要求片段内平均 ≥ AD_DISTANCE、每片 ≥ 0.55、对比 ≥ 0.25；
 * 不用每片固定阈值：有的剧集正片场景与参照差异本身就到 0.6，广告里个别画面也会低于 0.7。
 * scores[i] 为分片 i 与参照的最小差异，读不到画面为 undefined（片段内与两侧邻片都必须有分数）。返回 { from, to, length } 或 undefined。
 */
export function chooseInsert(segments, inside, scores, { at, drop }) {
  const has = (i) => scores[i] !== undefined;
  let best;
  for (let p = 0; p < inside.length; p++) for (let q = p; q < inside.length; q++) {
    const a = inside[p], b = inside[q];
    const start = segments[a].start, end = segments[b].start + segments[b].duration, length = end - start;
    if (length > drop + Math.max(4, drop * .3)) break;
    if (Math.abs(length - drop) > Math.max(4, drop * .3)) continue;
    const next = segments[Math.min(segments.length - 1, b + 1)];
    if (!(Math.abs(start - at) <= 1 || end <= at + 1 && end >= at - Math.max(1, next.duration) - 1)) continue;
    const span = inside.slice(p, q + 1);
    if (!span.every(has)) continue;
    const outside = [a - 2, a - 1, b + 1, b + 2].filter((i) => i >= 0 && i < segments.length);
    if (a > 0 && !has(a - 1) || b < segments.length - 1 && !has(b + 1) || !outside.some(has)) continue;
    const mean = span.reduce((n, i) => n + scores[i], 0) / span.length, low = Math.min(...span.map((i) => scores[i]));
    const known = outside.filter(has), around = known.reduce((n, i) => n + scores[i], 0) / known.length;
    const contrast = mean - around;
    if (mean < AD_DISTANCE || low < .55 || contrast < .25) continue;
    if (!best || contrast > best.contrast + .02 || Math.abs(contrast - best.contrast) <= .02 && Math.abs(length - drop) < Math.abs(best.length - drop)) best = { from: a, to: b, length, contrast };
  }
  return best && { from: best.from, to: best.to, length: best.length };
}
