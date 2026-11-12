import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readdir, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createLibrary } from './library.mjs';

// 假处理器：12 秒影片、4 秒分段；记录处理过哪些分段，可在指定分段失败
function fakeProcessor(dir, { failAt } = {}) {
  const calls = [];
  return { calls, create: (session, input) => ({
    preparing: Promise.resolve(), duration: 12, chunkDuration: 4, timeMap: [{ start: 3, duration: 2 }], label: '电脑转码 · 1080P HEVC',
    info: { state: 'ready', processingInfo: { codec: 'hevc' }, output: { codec: 'hevc' }, input: { width: 1280, height: 720 } },
    async segment(n) { calls.push({ n, input }); if (n === failAt) throw new Error('片源拒绝访问（HTTP 403）'); const file = join(dir, `src-${n}.ts`); await writeFile(file, 'x'.repeat(100 + n)); return file; },
    close() {},
  }) };
}
const until = async (test, ms = 3000) => { const end = Date.now() + ms; while (Date.now() < end) { if (await test()) return; await new Promise((r) => setTimeout(r, 20)); } throw Error('等待超时'); };

test('下载整集到片库：逐段保存、完成后可按播放请求命中，删除清理文件', async (t) => {
  const dir = await mkdtemp(join(tmpdir(), 'libretv-library-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const processor = fakeProcessor(dir), library = createLibrary({ processor, dir: join(dir, 'lib'), network: () => ({}), retryDelayMs: 1 });
  await library.load();
  const input = { url: 'https://v.test/a.m3u8', superResolution: true, adFilter: true, overlayRemoval: true };
  const added = await library.add({ ...input, playlists: [{ url: 'x', text: '#EXTM3U' }] }, { title: '测试影片', subtitle: '第 1 集', meta: { vodId: '1' } });
  await until(() => library.list()[0].state === 'done');
  const [item] = library.list();
  assert.deepEqual([item.title, item.done, item.segments, item.bytes, item.duration], ['测试影片', 3, 3, 303, 12]);
  assert.equal(processor.calls.every((c) => c.input.position === 0 && c.input.playlists === undefined && c.input.repeatFilter), true, '下载使用电脑自己读取的列表');
  assert.equal(library.find(input)?.id, added.id);
  assert.equal(library.find({ ...input, superResolution: false }), undefined, '处理设置不同不命中');
  assert.equal((await library.add(input)).id, added.id, '重复下载复用同一条目');
  assert.equal(await library.remove(added.id), true);
  assert.deepEqual(await readdir(join(dir, 'lib')), []);
});

test('中断后重新载入继续下载，只处理缺失的分段；失败显示原因并可重试', async (t) => {
  const dir = await mkdtemp(join(tmpdir(), 'libretv-library-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const failing = fakeProcessor(dir, { failAt: 2 });
  let library = createLibrary({ processor: failing, dir: join(dir, 'lib'), network: () => ({}), retryDelayMs: 1 });
  await library.load();
  const added = await library.add({ url: 'https://v.test/b.m3u8' }, { title: '中断测试' });
  await until(() => library.list()[0].state === 'error');
  assert.match(library.list()[0].error, /HTTP 403/); assert.equal(library.list()[0].done, 2);
  library.close();
  const resumed = fakeProcessor(dir);
  library = createLibrary({ processor: resumed, dir: join(dir, 'lib'), network: () => ({}), retryDelayMs: 1 });
  await library.load();
  await until(() => library.list()[0]?.state === 'done');
  assert.deepEqual(resumed.calls.map((c) => c.n), [2], '只处理缺失的第 3 段');
  assert.equal(library.list()[0].id, added.id);
});

test('关闭服务时正在准备的下载不会被误记为完成，重新载入后继续', async (t) => {
  const dir = await mkdtemp(join(tmpdir(), 'libretv-library-')); t.after(() => rm(dir, { recursive: true, force: true }));
  let release; const gate = new Promise((r) => { release = r; });
  const stuck = { create: () => ({ preparing: gate, duration: undefined, info: { state: 'preparing' }, close() { release(); }, segment() { throw new Error('不应编码'); } }) };
  let library = createLibrary({ processor: stuck, dir: join(dir, 'lib'), network: () => ({}), retryDelayMs: 1 });
  await library.load(); await library.add({ url: 'https://v.test/c.m3u8' }, { title: '关闭测试' });
  await new Promise((r) => setTimeout(r, 20)); library.close(); await new Promise((r) => setTimeout(r, 20));
  assert.notEqual(library.list()[0].state, 'done');
  library = createLibrary({ processor: fakeProcessor(dir), dir: join(dir, 'lib'), network: () => ({}), retryDelayMs: 1 });
  await library.load(); await until(() => library.list()[0]?.state === 'done');
  assert.equal(library.list()[0].done, 3);
});

test('单段偶发失败自动重试，不中断整集下载', async (t) => {
  const dir = await mkdtemp(join(tmpdir(), 'libretv-library-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const processor = fakeProcessor(dir); let failures = 1;
  const create = processor.create;
  processor.create = (...args) => { const job = create(...args), segment = job.segment.bind(job); job.segment = async (n) => { if (n === 1 && failures-- > 0) throw new Error('aborted'); return segment(n); }; return job; };
  const library = createLibrary({ processor, dir: join(dir, 'lib'), network: () => ({}), retryDelayMs: 1 });
  await library.load(); await library.add({ url: 'https://v.test/d.m3u8' }, { title: '重试测试' });
  await until(() => library.list()[0].state === 'done', 6000);
  assert.equal(library.list()[0].done, 3);
});
