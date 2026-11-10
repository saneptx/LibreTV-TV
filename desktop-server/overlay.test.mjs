import { test } from 'node:test';
import assert from 'node:assert/strict';
import { marqueeActive, marqueeBands } from './overlay.mjs';
const w = 320, h = 180;
// 平滑背景上一条有字形纹理的横条；moving 为两帧间的水平平移量
function pair(seed, moving, withBand = true) {
  const frame = (offset) => {
    const f = new Uint8Array(w * h);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      let v = 60 + ((seed * 37) % 100) + x * .2 + y * .1;
      if (withBand && y >= 20 && y < 32) v = ((x + offset) * 7 + seed) % 23 < 9 ? 230 : 30;
      f[y * w + x] = Math.max(0, Math.min(255, v));
    }
    return f;
  };
  return { a: frame(0), b: frame(moving) };
}
test('滚动字幕横条：多数采样点同速平移才算，静止横条不算', () => {
  const moving = Array.from({ length: 8 }, (_, i) => pair(i + 1, 8));
  const bands = marqueeBands(moving, w, h);
  assert.equal(bands.length, 1, JSON.stringify(bands));
  assert(bands[0].y <= 20 && bands[0].y + bands[0].h >= 32, JSON.stringify(bands[0]));
  assert.deepEqual(marqueeBands(Array.from({ length: 8 }, (_, i) => pair(i + 1, 0)), w, h), []);
});
test('逐段判断：本段横条在滚动才模糊，没有广告的段落保持原画', () => {
  const region = { kind: 'marquee', x: 1, y: 60, w: 1277, h: 80 }; // 1280×720 源坐标，覆盖分析图第 15–35 行
  assert.equal(marqueeActive(pair(3, 8), w, h, region, 1280, 720), true);
  assert.equal(marqueeActive(pair(3, 0), w, h, region, 1280, 720), false);
  assert.equal(marqueeActive(pair(3, 8, false), w, h, region, 1280, 720), false);
});
