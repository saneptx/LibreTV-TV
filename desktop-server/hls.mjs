export const problem = (status, message) => Object.assign(new Error(message), { status });
export function attributes(line) {
  return Object.fromEntries([...line.slice(line.indexOf(':') + 1).matchAll(/([A-Z0-9-]+)=(?:"([^"]*)"|([^,]*))(?:,|$)/g)].map((m) => [m[1], m[2] ?? m[3]]));
}
export function parseMaster(text, base) {
  const lines = text.split(/\r?\n/).map((s) => s.trim()), variants = [];
  const media = lines.filter((s) => s.startsWith('#EXT-X-MEDIA:')).map(attributes);
  for (let i = 0; i < lines.length; i++) if (lines[i].startsWith('#EXT-X-STREAM-INF:')) {
    const attr = attributes(lines[i]); const uri = lines.slice(i + 1).find((s) => s && !s.startsWith('#'));
    if (!uri) throw problem(422, '片源主列表缺少视频地址');
    const [width, height] = (attr.RESOLUTION || '0x0').split('x').map(Number);
    const audio = media.find((m) => m.TYPE === 'AUDIO' && m['GROUP-ID'] === attr.AUDIO && m.URI && m.DEFAULT === 'YES') || media.find((m) => m.TYPE === 'AUDIO' && m['GROUP-ID'] === attr.AUDIO && m.URI);
    variants.push({ url: new URL(uri, base).href, width, height, bandwidth: Number(attr.BANDWIDTH) || 0, audio: audio ? new URL(audio.URI, base).href : undefined });
  }
  return variants.sort((a, b) => b.height - a.height || b.bandwidth - a.bandwidth);
}
function byteRange(value, previous, url) {
  const match = value?.match(/^(\d+)(?:@(\d+))?$/);
  if (!match) throw problem(422, '片源 BYTERANGE 格式无效');
  const length = Number(match[1]), offset = match[2] ? Number(match[2]) : previous?.url === url ? previous.end + 1 : NaN;
  if (!Number.isSafeInteger(length) || length < 1 || !Number.isSafeInteger(offset) || offset < 0) throw problem(422, '片源 BYTERANGE 缺少有效偏移');
  return { url, start: offset, end: offset + length - 1 };
}
/**
 * 与设备「重复插入」规则一致：按 DISCONTINUITY 或分片目录变化切段组，同一非正片目录、时长完全相同（≤60 秒）
 * 的段组出现两次以上即为轮播插入的广告。返回这些分片的原文行。
 */
export function repeatedInsertLines(text, base) {
  const groups = []; let current, duration = 0, boundary = true;
  const dir = (line) => { try { const u = new URL(line, base); return u.origin + u.pathname.slice(0, u.pathname.lastIndexOf('/') + 1); } catch { return ''; } };
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === '#EXT-X-DISCONTINUITY') boundary = true;
    else if (line.startsWith('#EXTINF:')) duration = Number(line.slice(8).split(',')[0]) || 0;
    else if (line && !line.startsWith('#')) {
      const d = dir(line);
      if (!current || boundary || d !== current.dir) { current = { dir: d, duration: 0, lines: [] }; groups.push(current); }
      current.lines.push(line); current.duration += duration; duration = 0; boundary = false;
    }
  }
  if (groups.length < 3 || groups.filter((g) => g.duration <= 180).length > 24) return [];
  const content = groups.reduce((a, g) => g.duration > a.duration ? g : a).dir, count = new Map();
  const key = (g) => `${g.dir}|${g.duration.toFixed(2)}`;
  for (const g of groups) if (g.dir !== content && g.duration > 0 && g.duration <= 60) count.set(key(g), (count.get(key(g)) || 0) + 1);
  return groups.filter((g) => (count.get(key(g)) || 0) >= 2).flatMap((g) => g.lines);
}
/** TS 分片里第一个视频 PES 的显示时间戳（秒）；前缀里没有视频 PES 时退回第一个带时间戳的 PES。与设备端解析一致 */
export function firstPts(buf) {
  let start = -1;
  for (let i = 0; i < Math.min(buf.length, 188 * 4); i++) if (buf[i] === 0x47 && buf[i + 188] === 0x47 && buf[i + 376] === 0x47) { start = i; break; }
  if (start < 0) return undefined;
  let pmt = -1, video = -1, fallback;
  for (let i = start; i + 188 <= buf.length; i += 188) {
    if (buf[i] !== 0x47 || !(buf[i + 1] & 0x40) || !(buf[i + 3] & 0x10)) continue;
    const pid = ((buf[i + 1] & 0x1f) << 8) | buf[i + 2];
    const at = i + 4 + (buf[i + 3] & 0x20 ? 1 + buf[i + 4] : 0);
    if (at >= i + 188) continue;
    const p = buf.subarray(at, i + 188);
    if (pid === 0) { const b = 1 + p[0]; if (pmt < 0 && p[b] === 0) pmt = ((p[b + 10] & 0x1f) << 8) | p[b + 11]; continue; }
    if (pid === pmt) {
      const b = 1 + p[0], end = b + 3 + (((p[b + 1] & 0x0f) << 8) | p[b + 2]) - 4;
      for (let q = b + 12 + (((p[b + 10] & 0x0f) << 8) | p[b + 11]); video < 0 && q + 5 <= end && q + 5 <= p.length; q += 5 + (((p[q + 3] & 0x0f) << 8) | p[q + 4]))
        if ([0x02, 0x1b, 0x24].includes(p[q])) video = ((p[q + 1] & 0x1f) << 8) | p[q + 2];
      continue;
    }
    if (p.length < 14 || p[0] !== 0 || p[1] !== 0 || p[2] !== 1 || !(p[7] & 0x80)) continue;
    const pts = ((p[9] & 0x0e) * 536870912 + p[10] * 4194304 + (p[11] & 0xfe) * 16384 + p[12] * 128 + (p[13] >> 1)) / 90000;
    if (pid === video) return pts;
    fallback ??= pts;
  }
  return fallback;
}
const PTS_WRAP = 2 ** 33 / 90000;
const ptsDelta = (a, b) => { const d = (a - b) % PTS_WRAP; return d > PTS_WRAP / 2 ? d - PTS_WRAP : d < -PTS_WRAP / 2 ? d + PTS_WRAP : d; };
/** 按 DISCONTINUITY 或分片目录变化切段组；加密或 fMP4 列表读不到时间戳，返回空 */
export function timelineGroups(text, base) {
  if (/#EXT-X-(KEY:METHOD=(?!NONE)|MAP:)/.test(text)) return [];
  const groups = []; let current, duration = 0, boundary = true, start = 0;
  const dir = (url) => url.slice(0, url.lastIndexOf('/') + 1);
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === '#EXT-X-DISCONTINUITY') boundary = true;
    else if (line.startsWith('#EXTINF:')) duration = Number(line.slice(8).split(',')[0]) || 0;
    else if (line && !line.startsWith('#')) {
      let url; try { url = new URL(line, base).href; } catch { return []; }
      if (!current || boundary || dir(url) !== current.dir) { current = { start, duration: 0, lines: [], url, dir: dir(url) }; groups.push(current); }
      current.lines.push(line); current.duration += duration; start += duration; duration = 0; boundary = false;
    }
  }
  return groups.length >= 3 && groups.length <= 4000 ? groups : [];
}
/**
 * 与设备「时间轴接续」规则一致：正片各段的时间戳接着上一段往下走，插入的广告自带时间戳、播完后正片接回插入前的位置。
 * 夹在两段能接上的正片之间、自身接不上的短段组（总长 ≤ 90 秒且 ≤ 全片 15%）判为广告。没读到时间戳的组跳过。
 * 插入点前面的连续正片不足 90 秒时不判定：「开头一段广告 + 正片」与「正片开头一段 + 插入」只看时间戳完全一样，判错会删掉正片。
 */
export function timelineInsertLines(groups, tolerance = .5, maximum = 90) {
  const total = groups.reduce((sum, g) => sum + g.duration, 0), lines = [];
  let anchor, origin = 0;
  for (let i = 0; i < groups.length; i++) {
    const g = groups[i];
    if (g.pts === undefined) continue;
    if (!anchor) { anchor = g; origin = g.start; continue; }
    const resume = anchor.pts + g.start - anchor.start;
    if (Math.abs(ptsDelta(g.pts, resume)) <= tolerance) { anchor = g; continue; }
    let j = i + 1, inserted = g.duration;
    while (j < groups.length && inserted <= maximum && groups[j].pts !== undefined && Math.abs(ptsDelta(groups[j].pts, resume)) > tolerance) inserted += groups[j++].duration;
    const back = groups[j];
    if (back?.pts !== undefined && inserted <= maximum && inserted <= total * .15 && g.start - origin > maximum && Math.abs(ptsDelta(back.pts, resume)) <= tolerance) {
      for (let k = i; k < j; k++) lines.push(...groups[k].lines);
      anchor = back; i = j;
    } else { anchor = g; origin = g.start; }
  }
  return lines;
}
/** 分层抽查要读时间戳的组：全片均匀抽 16 组，相邻两点「时间戳 − 原片位置」不一致的区间再细分，区间不超过 16 组时整段读完 */
export async function probeTimeline(units, read, { samples = 16, maxReads = 240, signal } = {}) {
  const tried = new Set(); let reads = 0;
  const same = (a, b) => a.pts !== undefined && b.pts !== undefined && Math.abs(ptsDelta(a.pts - a.start, b.pts - b.start)) <= .5;
  let intervals = units.length > 1 ? [[0, units.length - 1]] : [];
  while (intervals.length && !signal?.aborted && reads < maxReads) {
    const points = new Set(), sampled = [], next = [];
    for (const [a, b] of intervals) {
      const count = b - a + 1;
      const picks = count <= samples ? Array.from({ length: count }, (_, i) => a + i) : Array.from({ length: samples }, (_, i) => a + Math.round(i * (b - a) / (samples - 1)));
      picks.forEach((i) => points.add(i)); sampled.push(count <= samples ? [] : picks);
    }
    const todo = [...points].map((i) => units[i]).filter((u) => u.pts === undefined && !tried.has(u)).slice(0, maxReads - reads);
    todo.forEach((u) => { tried.add(u); u.tried = true; }); reads += todo.length;
    let cursor = 0;
    await Promise.all(Array.from({ length: Math.min(8, todo.length) }, async () => { while (cursor < todo.length && !signal?.aborted) { const u = todo[cursor++]; u.pts = await read(u).catch(() => undefined); } }));
    for (const picks of sampled) for (let k = 1; k < picks.length; k++) if (picks[k] - picks[k - 1] > 1 && !same(units[picks[k - 1]], units[picks[k]])) next.push([picks[k - 1], picks[k]]);
    intervals = next;
  }
  return reads;
}
export function parseMedia(text, base, { adFilter = true, adSegments, repeatFilter = false } = {}) {
  // 设备按编码特征/已学习指纹识别出的分片行，按播放列表原文匹配；参考来源等没有设备标记时可用电脑自身的重复插入规则
  const lines = [...(adSegments || []), ...(repeatFilter ? repeatedInsertLines(text, base) : [])];
  const flagged = adFilter && lines.length ? new Set(lines) : undefined;
  if (!text.trimStart().startsWith('#EXTM3U')) throw problem(422, '视频清单格式无效');
  if (!text.includes('#EXT-X-ENDLIST')) throw problem(422, '电脑增强目前只支持点播，暂不处理直播清单');
  if (/#EXT-X-(DEFINE|CONTENT-STEERING):|\{\$/.test(text)) throw problem(422, '暂不支持带变量或内容转向的 HLS');
  let sequence = 0, key, map, duration, range, previousRange, cue = false, discontinuity = false, original = 0, retained = 0;
  const segments = [], removed = [];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith('#EXT-X-MEDIA-SEQUENCE:')) sequence = Number(line.split(':')[1]);
    else if (line.startsWith('#EXT-X-KEY:')) {
      const attr = attributes(line);
      if (attr.METHOD === 'NONE') key = undefined;
      else if (attr.METHOD === 'AES-128' && (!attr.KEYFORMAT || attr.KEYFORMAT === 'identity') && attr.URI) {
        if (attr.IV && !/^0x[0-9a-fA-F]{1,32}$/.test(attr.IV)) throw problem(422, 'AES IV 格式无效');
        key = { url: new URL(attr.URI, base).href, iv: attr.IV };
      } else throw problem(422, '暂不支持 DRM 或 SAMPLE-AES 片源');
    } else if (line.startsWith('#EXT-X-MAP:')) {
      const attr = attributes(line); if (!attr.URI) throw problem(422, '初始化片段缺少地址');
      const url = new URL(attr.URI, base).href;
      map = { url, range: attr.BYTERANGE ? byteRange(attr.BYTERANGE, undefined, url) : undefined, key };
      if (key && !key.iv) throw problem(422, '加密初始化片段缺少显式 IV');
    } else if (line.startsWith('#EXT-X-BYTERANGE:')) range = line.slice(line.indexOf(':') + 1);
    else if (line.startsWith('#EXT-X-CUE-OUT')) cue = true;
    else if (line.startsWith('#EXT-X-CUE-IN')) cue = false;
    else if (line === '#EXT-X-DISCONTINUITY') discontinuity = true;
    else if (line.startsWith('#EXTINF:')) duration = Number(line.slice(8).split(',')[0]);
    else if (line && !line.startsWith('#')) {
      if (!Number.isFinite(duration) || duration <= 0 || duration > 600 || !Number.isSafeInteger(sequence) || sequence < 0) throw problem(422, '清单时间或序号无效');
      const url = new URL(line, base).href;
      const bytes = range ? byteRange(range, previousRange, url) : undefined;
      previousRange = bytes;
      const adPath = /\/(?:ads?|advert(?:isement)?|adjump)(?:\/|[_.-])/i.test(new URL(url).pathname);
      const segment = { url, duration, originalStart: original, start: retained, sequence, range: bytes, key: key ? { ...key, iv: key.iv || '0x' + BigInt(sequence).toString(16).padStart(32, '0') } : undefined, map, discontinuity };
      if (adFilter && (cue || adPath || flagged?.has(line))) removed.push({ sequence, start: original, duration, reason: cue ? 'cue' : adPath ? 'url' : 'device' });
      else { segments.push(segment); retained += duration; }
      original += duration; sequence++; duration = undefined; range = undefined; discontinuity = false;
      if (segments.length + removed.length > 12000 || original > 8 * 3600) throw problem(422, '片源超过当前八小时处理上限');
    }
  }
  if (!segments.length || duration !== undefined) throw problem(422, '清单没有有效正片片段');
  return { segments, removed, duration: retained, originalDuration: original, removedSeconds: original - retained };
}
export function sliceMedia(media, start, duration) {
  const first = media.segments.findIndex((s) => s.start + s.duration > start + .00001);
  if (first < 0) throw problem(416, '播放位置超过影片长度');
  const selected = media.segments.slice(first).filter((s) => s.start < start + duration + .00001);
  return { segments: selected, offset: start - selected[0].start };
}
export function nextTierHeight(width, height) {
  // Use the 16:9 bounding size: cropped 1920x800 is still a 1080P source.
  const effective = Math.max(height, width * 9 / 16);
  const tiers = [360, 480, 720, 1080, 2160];
  if (effective >= 2160) return null;
  const nearby = tiers.slice(0, -1).filter(t => Math.abs(t - effective) <= t * .15)
    .sort((a, b) => Math.abs(a - effective) - Math.abs(b - effective))[0];
  return nearby ? tiers[tiers.indexOf(nearby) + 1] : tiers.find(t => t > effective);
}
export function targetSize(width, height, ceiling = 'auto', enabled = true) {
  if (![width, height].every((v) => Number.isFinite(v) && v > 0) || width > 7680 || height > 4320) throw problem(422, '视频尺寸不支持');
  if (!enabled) return { width: Math.max(2, Math.round(width / 2) * 2), height: Math.max(2, Math.round(height / 2) * 2), superResolution: false };
  if (ceiling === 'auto') {
    ceiling = nextTierHeight(width, height);
    if (ceiling === null) return { width: Math.max(2,Math.round(width/2)*2), height: Math.max(2,Math.round(height/2)*2), superResolution: false };
  }
  if (!Number.isFinite(ceiling) || ceiling <= 0) throw problem(400, '目标分辨率无效');
  const ratio = Math.min(ceiling / height, ceiling * 16 / 9 / width);
  return { width: Math.max(2, Math.round(width * ratio / 2) * 2), height: Math.max(2, Math.round(height * ratio / 2) * 2), superResolution: ratio > 1.01 };
}
