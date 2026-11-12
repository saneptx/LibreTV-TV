// 画面叠加物识别（第 3 阶段）：在全片多个位置抽取成对的灰度小图（间隔约 0.4 秒）。
// - 固定水印/台标：在不同场景的帧里，同一位置始终有边缘；
// - 滚动字幕：画面上下边缘的横条，在 0.4 秒内整体水平平移，而画面中部没有同样的平移（排除镜头横摇）。
// 字幕逐句变化、不会在所有场景同一处出现，也不会水平滚动，因此不会被当成叠加物。
export const ANALYSIS_WIDTH = 320;

export function gradient(frame, w, h) {
  const out = new Float32Array(w * h);
  for (let y = 1; y < h - 1; y++) for (let x = 1; x < w - 1; x++) {
    const i = y * w + x;
    const gx = frame[i - w + 1] + 2 * frame[i + 1] + frame[i + w + 1] - frame[i - w - 1] - 2 * frame[i - 1] - frame[i + w - 1];
    const gy = frame[i + w - 1] + 2 * frame[i + w] + frame[i + w + 1] - frame[i - w - 1] - 2 * frame[i - w] - frame[i - w + 1];
    out[i] = Math.hypot(gx, gy) / 4;
  }
  return out;
}

function components(mask, w, h) {
  const seen = new Uint8Array(w * h), boxes = [];
  for (let start = 0; start < mask.length; start++) {
    if (!mask[start] || seen[start]) continue;
    const stack = [start]; seen[start] = 1;
    let x0 = w, y0 = h, x1 = 0, y1 = 0, count = 0;
    while (stack.length) {
      const i = stack.pop(), x = i % w, y = (i - x) / w; count++;
      x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
      for (const j of [i - 1, i + 1, i - w, i + w]) if (j >= 0 && j < mask.length && mask[j] && !seen[j] && Math.abs((j % w) - x) <= 1) { seen[j] = 1; stack.push(j); }
    }
    boxes.push({ x: x0, y: y0, w: x1 - x0 + 1, h: y1 - y0 + 1, count });
  }
  return boxes;
}
function dilate(mask, w, h, r) {
  const out = new Uint8Array(mask.length);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) if (mask[y * w + x])
    for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) { const yy = y + dy, xx = x + dx; if (yy >= 0 && yy < h && xx >= 0 && xx < w) out[yy * w + xx] = 1; }
  return out;
}

function patternConsistency(grads, w, box) {
  const patch = (g) => { const out = []; for (let y = box.y; y < box.y + box.h; y++) for (let x = box.x; x < box.x + box.w; x++) out.push(g[y * w + x]); return out; };
  const patches = grads.map(patch), corr = (a, b) => {
    const n = a.length; let ma = 0, mb = 0; for (let i = 0; i < n; i++) { ma += a[i]; mb += b[i]; } ma /= n; mb /= n;
    let sab = 0, saa = 0, sbb = 0; for (let i = 0; i < n; i++) { const da = a[i] - ma, db = b[i] - mb; sab += da * db; saa += da * da; sbb += db * db; }
    return saa && sbb ? sab / Math.sqrt(saa * sbb) : 0;
  };
  let total = 0; for (let i = 1; i < patches.length; i++) total += corr(patches[i - 1], patches[i]);
  return total / (patches.length - 1);
}
/** 各帧都在同一处出现边缘、且边缘图案一致的区域；frames 为不同场景的灰度帧 */
export function staticOverlays(frames, w, h) {
  if (frames.length < 6) return [];
  const persistent = new Uint16Array(w * h), grads = frames.map((frame) => gradient(frame, w, h));
  for (const g of grads) for (let i = 0; i < g.length; i++) if (g[i] > 24) persistent[i]++;
  // 场景本身（不同时间点）不会在同一像素上反复出现边缘；要求至少 85% 的帧都有边缘
  const need = Math.ceil(frames.length * .85), mask = new Uint8Array(w * h);
  for (let i = 0; i < mask.length; i++) mask[i] = persistent[i] >= need ? 1 : 0;
  const boxes = components(dilate(mask, w, h, 3), w, h).filter((b) => b.count >= 40 && b.w * b.h <= w * h * .06 && b.w <= w * .45 && b.h <= h * .3
    // 黑边分界、画框等贯穿全宽/全高的细线不是水印
    && !(b.w >= w * .6 && b.h <= h * .04) && !(b.h >= h * .6 && b.w <= w * .04)
    // 台标每帧都是同一个形状；对白字幕位置固定但每句的字不同，边缘图案在帧间不相关
    && patternConsistency(grads, w, b) >= .55);
  return boxes.map(({ x, y, w: bw, h: bh }) => ({ kind: 'logo', x: x - 2, y: y - 2, w: bw + 4, h: bh + 4 }));
}

export function bandShift(a, b, w, y0, y1, maxShift) {
  let base = 0, edges = 0;
  for (let y = y0; y < y1; y++) for (let x = 0; x < w; x++) { const i = y * w + x; base += Math.abs(a[i] - b[i]); if (a[i] > 24) edges++; }
  const density = edges / ((y1 - y0) * w);
  if (density < .05 || base <= 0) return undefined;
  let best = { shift: 0, cost: Infinity };
  for (let s = -maxShift; s <= maxShift; s++) {
    let cost = 0, n = 0;
    for (let y = y0; y < y1; y++) for (let x = Math.max(0, -s); x < Math.min(w, w - s); x++) { cost += Math.abs(a[y * w + x] - b[y * w + x + s]); n++; }
    cost /= n;
    if (cost < best.cost) best = { shift: s, cost };
  }
  return { ...best, ratio: best.cost / (base / ((y1 - y0) * w)), density };
}

/** 上下边缘整体水平平移的横条 */
export function marqueeBands(pairs, w, h) {
  if (pairs.length < 4) return [];
  const band = Math.max(6, Math.round(h * .045)), step = Math.max(3, Math.round(band / 2)), maxShift = Math.round(w * .15);
  const zones = [[0, Math.round(h * .25)], [Math.round(h * .68), h]];
  const grads = pairs.map(({ a, b }) => ({ a: gradient(a, w, h), b: gradient(b, w, h) }));
  const panning = grads.map(({ a, b }) => {
    const mid = bandShift(a, b, w, Math.round(h * .4), Math.round(h * .6), maxShift);
    return mid && mid.shift !== 0 && mid.ratio < .7 ? mid.shift : undefined;
  });
  const hits = [];
  for (const [z0, z1] of zones) for (let y = z0 + 1; y + band <= z1 - 1; y += step) {
    const shifts = [];
    grads.forEach(({ a, b }, n) => {
      const r = bandShift(a, b, w, y, y + band, maxShift);
      if (r && Math.abs(r.shift) >= 2 && r.ratio < .85 && (panning[n] === undefined || Math.abs(panning[n] - r.shift) > 2)) shifts.push(r.shift);
    });
    // 滚动字幕匀速移动：多数采样点给出几乎相同的平移量，随机运动做不到
    const agree = Math.max(0, ...shifts.map((s) => shifts.filter((t) => Math.abs(t - s) <= 2).length));
    if (agree >= Math.max(3, Math.ceil(pairs.length * .4))) hits.push({ y0: y, y1: y + band });
  }
  const merged = [];
  for (const hit of hits) { const last = merged.at(-1); if (last && hit.y0 <= last.y1 + 1) last.y1 = Math.max(last.y1, hit.y1); else merged.push({ ...hit }); }
  // 平移量测在字形中部最稳定，上下各放宽约半个横条，覆盖完整字高
  const pad = Math.ceil(band * .6);
  return merged.filter((m) => m.y1 - m.y0 <= h * .2).map((m) => { const y = Math.max(0, m.y0 - pad); return { kind: 'marquee', x: 0, y, w, h: Math.min(h - y, m.y1 - m.y0 + 2 * pad), core: { y: m.y0, h: m.y1 - m.y0 } }; });
}

/**
 * 滚动广告通常隔一段时间才出现：编码每一段前用这一段的一对小图判断横条当前是否在水平平移，
 * 只在出现时模糊，其余时间保持原画。region 为源像素坐标。
 */
export function marqueeActive(pair, w, h, region, width, height) { return !!marqueeExtent(pair, w, h, region, width, height); }

/** 同上，并返回本段滚动文字的实际上下范围（源像素）：从横条向外逐行扩展，直到该行不再以同样速度平移 */
export function marqueeExtent(pair, w, h, region, width, height) {
  const a = gradient(pair.a, w, h), b = gradient(pair.b, w, h);
  const y0 = Math.max(1, Math.floor(region.y * h / height)), y1 = Math.min(h - 1, Math.ceil((region.y + region.h) * h / height));
  if (y1 - y0 < 3) return undefined;
  const maxShift = Math.round(w * .15), r = bandShift(a, b, w, y0, y1, maxShift);
  const mid = bandShift(a, b, w, Math.round(h * .4), Math.round(h * .6), maxShift);
  if (!r || Math.abs(r.shift) < 2 || r.ratio >= .85 || mid && mid.shift !== 0 && Math.abs(mid.shift - r.shift) <= 2 && mid.ratio < .7) return undefined;
  const moving = (y) => { const row = bandShift(a, b, w, y, y + 2, maxShift); return row && Math.abs(row.shift - r.shift) <= 2 && row.ratio < .85; };
  let top = y0, bottom = y1;
  const limit = Math.round(h * .08);
  for (let y = y0 - 2, gap = 0; y >= 1 && y0 - y <= limit; y -= 2) { if (moving(y)) { top = y; gap = 0; } else if (++gap > 1) break; }
  for (let y = y1, gap = 0; y + 2 < h && y - y1 <= limit; y += 2) { if (moving(y)) { bottom = y + 2; gap = 0; } else if (++gap > 1) break; }
  const sy = height / h, y = Math.max(1, Math.floor((top - 2) * sy));
  return { kind: 'marquee', x: region.x, y, w: region.w, h: Math.max(4, Math.min(Math.ceil((bottom - top + 4) * sy), height - y - 2)) };
}
/**
 * 本段画面上方的静止横幅广告：只在一段时间内出现、位置不动的宽幅文字（全片抽样难以抽到）。
 * frames 为本段内跨越约 7 秒的几帧灰度图。要求：画面整体在变化（排除静止镜头）、
 * 文字在所有帧同一位置且亮度不变、横向铺开超过画面四分之一（排除地名等短字幕）。
 * 只看画面上方 22%，对白字幕通常在下方。
 */
export function staticTextBands(frames, w, h) {
  if (frames.length < 3) return [];
  const first = frames[0], last = frames.at(-1);
  let change = 0; for (let i = 0; i < first.length; i++) change += Math.abs(first[i] - last[i]);
  if (change / first.length < 4) return [];
  const grads = frames.map((f) => gradient(f, w, h)), zone = Math.round(h * .22), rows = [];
  for (let y = 1; y < zone; y++) {
    let count = 0, minX = w, maxX = 0, runs = 0, inside = false;
    for (let x = 1; x < w - 1; x++) {
      const i = y * w + x;
      const hit = grads.every((g) => g[i] > 24) && frames.every((f) => Math.abs(f[i] - first[i]) < 14);
      if (hit) { count++; if (x < minX) minX = x; if (x > maxX) maxX = x; if (!inside) runs++; }
      inside = hit;
    }
    // 文字行：静止边缘密集（≥ 行宽 15%）且横向铺开；整行都是边缘的直线不算
    if (count >= w * .15 && count <= w * .8 && maxX - minX >= w * .25 && runs >= 3) rows.push(y);
  }
  const bands = [];
  for (const y of rows) { const last = bands.at(-1); if (last && y <= last.y1 + 1) last.y1 = y; else bands.push({ y0: y, y1: y }); }
  // 叠加文字是亮字配暗底，局部明暗差极大；月面、建筑等静止纹理是中间灰度（实测文字 35–53%，月面约 1%）
  const contrast = (b) => {
    let high = 0, n = 0;
    for (let y = b.y0; y <= b.y1; y++) for (let x = 1; x < w - 1; x++) {
      let max = 0, min = 255;
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) { const v = first[Math.min(h - 1, Math.max(0, y + dy)) * w + x + dx]; if (v > max) max = v; if (v < min) min = v; }
      n++; if (max - min >= 110) high++;
    }
    return high / n;
  };
  // 文字背后若是纯色（常见为画面上方黑边），返回该底色亮度，用底色填平比模糊更干净
  const backdrop = (y0, y1) => {
    const values = [];
    for (let y = y0; y <= y1; y++) for (let x = 1; x < w - 1; x++) {
      let max = 0, min = 255;
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) { const v = first[Math.min(h - 1, Math.max(0, y + dy)) * w + x + dx]; if (v > max) max = v; if (v < min) min = v; }
      if (max - min < 20) values.push(first[y * w + x]);
    }
    if (values.length < (y1 - y0 + 1) * (w - 2) * .4) return undefined;
    const mean = values.reduce((a, b) => a + b, 0) / values.length;
    const sd = Math.sqrt(values.reduce((a, b) => a + (b - mean) ** 2, 0) / values.length);
    return sd <= 8 ? Math.round(mean) : undefined;
  };
  // 黑边与画面的分界只占约 2 行，一行文字至少连续 4 行
  return bands.filter((b) => b.y1 - b.y0 + 1 >= 4 && b.y1 - b.y0 + 1 <= h * .1 && contrast(b) >= .12)
    .map((b) => {
      const y = Math.max(0, b.y0 - 3), bh = Math.min(h - y, b.y1 - b.y0 + 7), fill = backdrop(y, y + bh - 1);
      // 文字像素掩码（分析分辨率，膨胀 2 像素盖住笔画内部与描边）：只修补文字本身，周围画面保持原样
      const mask = new Uint8Array(w * h);
      for (let yy = y; yy < y + bh; yy++) for (let x = 1; x < w - 1; x++) {
        const i = yy * w + x;
        if (!(grads.every((g) => g[i] > 24) && frames.every((f) => Math.abs(f[i] - first[i]) < 14))) continue;
        for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) { const ty = yy + dy, tx = x + dx; if (ty >= y && ty < y + bh && tx >= 0 && tx < w) mask[ty * w + tx] = 255; }
      }
      return { kind: 'banner', x: 0, y, w, h: bh, mask, ...(fill === undefined ? {} : { fill }) };
    });
}
/** 分析坐标 → 源视频像素，裁到 delogo 要求的画面内部 */
export function toSource(regions, w, h, width, height) {
  const sx = width / w, sy = height / h;
  return regions.map((r) => {
    const x = Math.max(1, Math.floor(r.x * sx)), y = Math.max(1, Math.floor(r.y * sy));
    return { kind: r.kind, x, y, w: Math.max(4, Math.min(Math.ceil(r.w * sx), width - x - 2)), h: Math.max(4, Math.min(Math.ceil(r.h * sy), height - y - 2)), ...(r.fill === undefined ? {} : { fill: r.fill }) };
  }).filter((r) => r.x + r.w < width - 1 && r.y + r.h < height - 1).slice(0, 8);
}
/** 把分析分辨率的文字掩码放大为源分辨率、只含横幅这一条（region 的 y/h，源像素）的 PGM 灰度图 */
export function maskImage(mask, w, h, width, height, region = { y: 0, h: height }) {
  const header = Buffer.from(`P5\n${width} ${region.h}\n255\n`), body = Buffer.alloc(width * region.h);
  for (let y = 0; y < region.h; y++) {
    const row = Math.min(h - 1, Math.floor((region.y + y) * h / height)) * w;
    for (let x = 0; x < width; x++) body[y * width + x] = mask[row + Math.min(w - 1, Math.floor(x * w / width))];
  }
  return Buffer.concat([header, body]);
}
/** 台标用 delogo 按边框插值；整行的滚动字幕两端没有干净边框可插值，改为把该横条重度模糊后覆盖回去 */
export function delogoFilter(regions) {
  const parts = [];
  const blurred = (r) => (r.kind === 'marquee' || r.kind === 'banner') && !r.maskFile;
  regions.filter((r) => !blurred(r) && !r.maskFile).forEach((r) => parts.push(`delogo=x=${r.x}:y=${r.y}:w=${r.w}:h=${r.h}`));
  // 有文字掩码的横幅：整条按上下边界插值（delogo），再只在文字像素（掩码，边缘柔化）处换成插值结果，周围画面保持原样
  regions.filter((r) => r.maskFile).forEach((r, i) => parts.push(`split[tx${i}a][tx${i}b];[tx${i}b]delogo=x=1:y=${r.y}:w=${r.w - 2}:h=${r.h},crop=iw:${r.h}:0:${r.y}[tx${i}d];` +
    `movie=${r.maskFile},format=gray,gblur=sigma=2[tx${i}m];[tx${i}d][tx${i}m]alphamerge[tx${i}t];[tx${i}a][tx${i}t]overlay=0:${r.y}:format=auto`));
  const logos = parts.join(','), bands = regions.filter(blurred);
  if (!bands.length) return logos;
  // 纯色底：直接用底色填平，看不出处理痕迹。其余：模糊后叠回，上下 30% 渐变过渡，避免硬边色块
  const chain = bands.map((r, i) => {
    if (r.fill !== undefined) { const hex = r.fill.toString(16).padStart(2, '0'); return `drawbox=x=0:y=${r.y}:w=iw:h=${r.h}:color=0x${hex}${hex}${hex}@1:t=fill`; }
    return `split[ov${i}a][ov${i}b];[ov${i}b]crop=iw:${r.h}:0:${r.y},gblur=sigma=28:steps=3,eq=brightness=-0.06,format=yuva420p,geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':a='255*clip(min(Y,H-1-Y)/(H*0.3),0,1)'[ov${i}c];[ov${i}a][ov${i}c]overlay=0:${r.y}:format=auto`;
  }).join(',');
  return logos ? `${logos},${chain}` : chain;
}
export function detectOverlays(pairs, w, h, width, height) {
  const bands = marqueeBands(pairs, w, h);
  // 滚动字幕的底框边线是固定的，归入字幕条，不再单独当作台标
  // 台标与字幕条的取舍按字幕条的实测核心行判断，不含为覆盖字高而放宽的部分
  const cores = bands.map((m) => m.core);
  const logos = staticOverlays(pairs.map((p) => p.a), w, h).filter((b) => !cores.some((m) => Math.min(b.y + b.h, m.y + m.h) - Math.max(b.y, m.y) >= b.h * .5))
    .map((b) => { // 与字幕条部分重叠时去掉重叠的行，避免抹掉过大的区域
      for (const m of cores) {
        if (m.y <= b.y && m.y + m.h > b.y) { const cut = m.y + m.h - b.y; b = { ...b, y: b.y + cut, h: b.h - cut }; }
        else if (m.y > b.y && m.y < b.y + b.h) b = { ...b, h: m.y - b.y };
      }
      return b;
    }).filter((b) => b.h >= 4);
  return toSource([...logos, ...bands], w, h, width, height);
}
