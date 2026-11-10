import { test } from 'node:test';
import assert from 'node:assert/strict';
import { audioFeatures, bestMatch, findInsertions, frameSeconds, RATE, NEEDLE_SECONDS } from './compare.mjs';

// 可复现的「音乐」：随机音高与包络不断变化的多音叠加
function music(seconds, seed) {
  let x = seed >>> 0; const rand = () => { x = (x + 0x6d2b79f5) >>> 0; let t = Math.imul(x ^ (x >>> 15), 1 | x); t ^= t + Math.imul(t ^ (t >>> 7), 61 | t); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  const out = new Float32Array(seconds * RATE); let tones = [];
  for (let i = 0; i < out.length; i++) {
    if (!tones.length || rand() < 1 / 1500) tones = Array.from({ length: 3 }, () => ({ f: 200 + rand() * 2500, a: rand() }));
    let v = 0; for (const t of tones) v += t.a * Math.sin(2 * Math.PI * t.f * i / RATE);
    out[i] = v / 3;
  }
  return out;
}
test('音频特征在长窗口中找到相同片段的位置，无关音频得分低', () => {
  const hay = music(30, 7), at = 11.3, needle = hay.slice(Math.round(at * RATE), Math.round((at + NEEDLE_SECONDS) * RATE)).map((v, i) => v * .6 + Math.sin(i) * .01);
  const match = bestMatch(audioFeatures(needle), audioFeatures(hay));
  assert(Math.abs(match.lag * frameSeconds - at) < .05, String(match.lag * frameSeconds)); assert(match.score > .8, String(match.score));
  const unrelated = bestMatch(audioFeatures(music(4, 99)), audioFeatures(hay));
  assert(unrelated.score < .3, String(unrelated.score));
});

/** 模型：当前来源在 ads 处多出插入段；参考来源可在 extra 处多出片段（例如参考自己的插入） */
function model({ ads = [], extra = [], base = 2 }) {
  return async (t, low, high) => {
    if (ads.some((a) => t + NEEDLE_SECONDS > a.start && t < a.start + a.duration)) return { none: true };
    const offset = base - ads.filter((a) => a.start + a.duration <= t).reduce((n, a) => n + a.duration, 0) + extra.filter((e) => e.at <= t).reduce((n, e) => n + e.duration, 0);
    return offset >= low && offset <= high ? { offset } : { none: true };
  };
}
const ends = async (measure, end) => [await measure(60, -400, 400), await measure(end, -400, 400)];
test('二分定位当前来源中多出来的插入段', async () => {
  const measure = model({ ads: [{ start: 1000, duration: 30 }, { start: 2000.5, duration: 17.6 }] });
  const [a, b] = await ends(measure, 2700);
  const found = await findInsertions(measure, 60, 2700, a, b);
  assert.equal(found.length, 2);
  assert(Math.abs(found[0].start - 1000) < 1 && Math.abs(found[0].duration - 30) < .01, JSON.stringify(found));
  assert(Math.abs(found[1].start - 2000.5) < 1 && Math.abs(found[1].duration - 17.6) < .01, JSON.stringify(found));
});
test('偏移不变或只有参考来源多出片段时不报告插入', async () => {
  const none = model({});
  assert.deepEqual(await findInsertions(none, 60, 2700, ...(await ends(none, 2700))), []);
  const referenceExtra = model({ extra: [{ at: 900, duration: 40 }] });
  assert.deepEqual(await findInsertions(referenceExtra, 60, 2700, ...(await ends(referenceExtra, 2700))), []);
});
