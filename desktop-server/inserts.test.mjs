import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AD_DISTANCE, chooseInsert, dropWindow, mediaSegments, thumbDistance, thumbFeatures, timelineDrops } from './inserts.mjs';

// 正片每片 4 秒；在 at 处之前插一段被改成与正片衔接时间戳的广告（爱情公寓3 的插法）
const playlist = (count) => ['#EXTM3U', ...Array.from({ length: count }, (_, i) => `#EXTINF:4,\nc${i}.ts`), '#EXT-X-ENDLIST'].join('\n');
const segments = mediaSegments(playlist(200), 'https://cdn.test/v/index.m3u8');

test('时间戳规则没处理的偏移下降：位置与下降量；已删除的插入不再返回', () => {
  const groups = [{ start: 0, pts: 1.4, lines: ['a'] }, { start: 360, pts: 361.4, lines: ['b'] }, { start: 384, pts: 366.4, lines: ['c'] }, { start: 900, pts: 882.4, lines: ['d'] }];
  assert.deepEqual(timelineDrops(groups).map((d) => [d.at, d.drop.toFixed(1)]), [[384, '19.0']]);
  assert.deepEqual(timelineDrops(groups, new Set(['c'])), []);
});

test('画面差异：同一画面为 0，纯色差异最大', () => {
  const solid = (r, g, b) => Uint8Array.from({ length: 64 * 36 * 3 }, (_, i) => [r, g, b][i % 3]);
  assert.equal(thumbDistance(thumbFeatures(solid(20, 20, 20)), thumbFeatures(solid(20, 20, 20))), 0);
  assert.ok(thumbDistance(thumbFeatures(solid(20, 20, 20)), thumbFeatures(solid(230, 40, 230))) > AD_DISTANCE);
});

test('选紧挨下降点、长度接近下降量、对比最明显的一串；正片场景本身差异偏高时也能认出', () => {
  const drop = { at: 96 * 4, after: 89 * 4, drop: 19 };
  const { inside, refs } = dropWindow(segments, drop);
  assert.ok(inside.includes(90) && inside.includes(96) && refs.length === 6);
  const scores = [];
  for (const i of inside) scores[i] = i >= 91 && i <= 95 ? (i === 93 ? .68 : .9) : .55;
  assert.deepEqual(chooseInsert(segments, inside, scores, drop), { from: 91, to: 95, length: 20 });
});

test('不紧挨下降点、长度不符或对比不够时不判定', () => {
  const drop = { at: 96 * 4, after: 89 * 4, drop: 19 };
  const { inside } = dropWindow(segments, drop);
  const far = []; for (const i of inside) far[i] = i >= 86 && i <= 90 ? .9 : .3;
  assert.equal(chooseInsert(segments, inside, far, drop), undefined, '离下降点太远');
  const short = []; for (const i of inside) short[i] = i === 95 ? .95 : .3;
  assert.equal(chooseInsert(segments, inside, short, drop), undefined, '只有 4 秒');
  const flat = []; for (const i of inside) flat[i] = i >= 91 && i <= 95 ? .78 : .62;
  assert.equal(chooseInsert(segments, inside, flat, drop), undefined, '与两侧对比不够');
  const missing = []; for (const i of inside) missing[i] = i >= 91 && i <= 95 ? .9 : .3; delete missing[93];
  assert.equal(chooseInsert(segments, inside, missing, drop), undefined, '片段里有读不到画面的分片');
});

test('开头插播：广告从第 0 片开始、在下降点结束，有画面证据时可以删', () => {
  const drop = { at: 5 * 4, after: 0, drop: 20 };
  const { inside, refs } = dropWindow(segments, drop);
  assert.ok(refs.every((i) => i > inside.at(-1)), '只有后面的参照');
  const scores = []; for (const i of inside) scores[i] = i < 5 ? .95 : .3;
  assert.deepEqual(chooseInsert(segments, inside, scores, drop), { from: 0, to: 4, length: 20 });
});
