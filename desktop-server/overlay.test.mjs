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
test('本段才出现的静止横幅广告：亮字暗底、位置不动、背景在变；黑边分界和静止纹理不算', async () => {
  const { staticTextBands } = await import('./overlay.mjs');
  // 画面整体随时间变化；第 6–15 行是一条横跨 60% 宽度的亮字横幅（黑底白字），四帧不变
  const frame = (n, { banner = true, line = false, texture = false } = {}) => {
    const f = new Uint8Array(w * h);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      let v = (n * 41 + x * 3 + y * 2) % 200 + 20;
      if (banner && y >= 6 && y < 16 && x >= 40 && x < 232) v = ((x * 5 + y * 3) % 11 < 5) ? 235 : 15;
      if (line && y < 8) v = 0;
      if (texture && y >= 6 && y < 16) v = 110 + ((x * 7 + y * 13) % 9) * 6;
      f[y * w + x] = v;
    }
    return f;
  };
  const run = (opts) => staticTextBands([0, 1, 2, 3].map((n) => frame(n, opts)), w, h);
  const found = run();
  assert.equal(found.length, 1, found.length); assert(found[0].y <= 6 && found[0].y + found[0].h >= 16, found[0].y);
  // 文字像素掩码只落在横幅行内，且覆盖文字区域
  const { mask } = found[0];
  assert(mask[10 * w + 120] === 255, '文字区域在掩码内');
  assert(mask[40 * w + 120] === 0 && mask[10 * w + 5] === 0, '横幅外的画面不修补');
  const { maskImage, delogoFilter } = await import('./overlay.mjs');
  const image = maskImage(mask, w, h, w * 2, h * 2, { y: 10, h: 20 });
  assert.equal(image.length, `P5\n${w * 2} 20\n255\n`.length + w * 2 * 20);
  assert.match(delogoFilter([{ kind: 'banner', x: 1, y: 1, w, h: 10, maskFile: '/tmp/m.pgm' }]), /delogo=x=1:y=1:w=\d+:h=10,crop=iw:10:0:1.*movie=\/tmp\/m\.pgm.*alphamerge.*overlay=0:1/);
  assert.match(delogoFilter([{ kind: 'banner', x: 0, y: 1, w, h: 10, fill: 16 }]), /^drawbox=.*color=0x101010@1:t=fill$/);
  assert.deepEqual(run({ banner: false, line: true }), [], '黑边分界线');
  assert.deepEqual(run({ banner: false, texture: true }), [], '中间灰度的静止纹理');
  const still = [0, 0, 0, 0].map((n) => frame(n));
  assert.deepEqual(staticTextBands(still, w, h), [], '整个画面静止时不判定');
});
