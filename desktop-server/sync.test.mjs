import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createSyncStore, MAX_CHANGES } from './sync.mjs';

const T = Date.now();
const fav = (key, at, extra = {}) => ({ table: 'favorites', key, at, value: { key, card: { name: key.split('|')[0] }, addedAt: at, updatedAt: at }, ...extra });

test('两台设备交换修改：新的覆盖旧的，拉取只取版本号之后的', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'sync-'));
  try {
    const store = createSyncStore({ file: join(dir, 'sync.json'), saveDelayMs: 0 });
    await store.load();
    const tv = store.exchange({ since: 0, changes: [fav('繁花|2023', 100)] });
    assert.equal(tv.reset, true); assert.equal(tv.accepted, 1); assert.equal(tv.changes.length, 1);
    const phone = store.exchange({ epoch: tv.epoch, since: 0, changes: [fav('繁花|2023', 50), fav('庆余年|2019', 120)] });
    assert.equal(phone.accepted, 1, '旧的收藏不能覆盖新的');
    assert.deepEqual(phone.changes.map((c) => [c.key, c.at]), [['繁花|2023', 100], ['庆余年|2019', 120]]);
    const again = store.exchange({ epoch: tv.epoch, since: tv.rev, changes: [] });
    assert.deepEqual(again.changes.map((c) => c.key), ['庆余年|2019']);
    await store.flush();
    const saved = JSON.parse(await readFile(join(dir, 'sync.json'), 'utf8'));
    assert.equal(Object.keys(saved.records).length, 2);
    const reopened = createSyncStore({ file: join(dir, 'sync.json') });
    await reopened.load();
    assert.equal(reopened.summary().epoch, tv.epoch);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('删除也会同步；同一时间删除优先，重复推送不算修改', async () => {
  const store = createSyncStore({ file: join(tmpdir(), 'never-written.json'), saveDelayMs: 60_000 });
  const first = store.exchange({ since: 0, changes: [fav('繁花|2023', T)] });
  const removed = store.exchange({ epoch: first.epoch, since: first.rev, changes: [{ table: 'favorites', key: '繁花|2023', at: T, deleted: true }] });
  assert.equal(removed.accepted, 1);
  assert.deepEqual(removed.changes, [{ table: 'favorites', key: '繁花|2023', at: T, deleted: true }]);
  const echo = store.exchange({ epoch: first.epoch, since: removed.rev, changes: [fav('繁花|2023', T)] });
  assert.equal(echo.accepted, 0);
  const readded = store.exchange({ epoch: first.epoch, since: removed.rev, changes: [fav('繁花|2023', T + 100)] });
  assert.equal(readded.accepted, 1); assert.equal(readded.changes[0].value.card.name, '繁花');
});

test('拉取分页，并拒绝格式不对的修改', () => {
  const store = createSyncStore({ file: join(tmpdir(), 'never-written.json'), saveDelayMs: 60_000 });
  const batch = Array.from({ length: MAX_CHANGES }, (_, i) => ({ table: 'progress', key: `env_0_1_${i}`, at: 10 + i, value: { key: `env_0_1_${i}`, position: 1, duration: 2, updatedAt: 10 + i } }));
  const a = store.exchange({ since: 0, changes: batch });
  store.exchange({ epoch: a.epoch, since: a.rev, changes: [{ table: 'searchHistory', key: '繁花', at: 5, value: { text: '繁花', timestamp: 5 } }] });
  const page1 = store.exchange({ epoch: a.epoch, since: 0 });
  assert.equal(page1.more, true); assert.equal(page1.changes.length, MAX_CHANGES);
  const page2 = store.exchange({ epoch: a.epoch, since: page1.rev });
  assert.equal(page2.more, false); assert.deepEqual(page2.changes.map((c) => c.key), ['繁花']);
  for (const bad of [{ table: 'settings', key: 'x', at: 1, value: {} }, { table: 'history', key: '', at: 1, value: {} }, { table: 'history', key: 'a', at: 1, deleted: true, value: {} }, { table: 'history', key: 'a', at: 1, value: { big: 'x'.repeat(20000) } }])
    assert.throws(() => store.exchange({ since: 0, changes: [bad] }), /格式无效/);
});
