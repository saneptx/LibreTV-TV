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
