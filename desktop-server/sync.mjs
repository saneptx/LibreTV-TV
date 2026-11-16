// 多设备资料同步：观看历史、各集进度、搜索记录、片库收藏以电脑服务为中心保存。
// 不需要账号：持有配对码的设备就是同一个家庭。每条记录带修改时间，新的覆盖旧的（删除也是一条记录）。
// 电脑给每次被接受的修改分配递增版本号，设备记住拉到的版本号，下次只取更新的部分。
import { readFile, writeFile, rename, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';

export const SYNC_TABLES = ['history', 'progress', 'searchHistory', 'favorites'];
/** 每张表在电脑上最多保留的条数，超出时淘汰最旧的（删除记录 90 天后清掉） */
const CAPS = { history: 500, progress: 5000, searchHistory: 50, favorites: 3000 };
const TOMBSTONE_MS = 90 * 86400_000;
export const MAX_CHANGES = 1000;
/** 单次回复的大小上限：电视/手机原生网络通道最多收 4 MB */
export const MAX_PAGE_BYTES = 2 * 1024 * 1024;

const validChange = (c) => c && typeof c === 'object' && SYNC_TABLES.includes(c.table) && typeof c.key === 'string' && c.key.length > 0 && c.key.length <= 600
  && Number.isFinite(c.at) && c.at > 0 && (c.deleted === true ? c.value === undefined : c.value && typeof c.value === 'object' && !Array.isArray(c.value) && JSON.stringify(c.value).length <= 16384);

export function createSyncStore({ file = '.desktop-server/sync.json', now = Date.now, saveDelayMs = 500 } = {}) {
  const path = resolve(file);
  let state = { epoch: randomBytes(8).toString('hex'), rev: 0, records: {} };
  let timer, writing = Promise.resolve();
  const id = (table, key) => `${table}\u0000${key}`;

  async function load() {
    try {
      const saved = JSON.parse(await readFile(path, 'utf8'));
      if (saved && typeof saved.epoch === 'string' && Number.isInteger(saved.rev) && saved.records && typeof saved.records === 'object') state = saved;
    } catch (error) { if (error.code !== 'ENOENT') console.error('同步资料读取失败，将重新开始：' + error.message); }
  }
  function persist() {
    clearTimeout(timer);
    timer = setTimeout(() => {
      const text = JSON.stringify(state);
      writing = writing.then(async () => {
        await mkdir(dirname(path), { recursive: true });
        await writeFile(path + '.part', text, { mode: 0o600 }); await rename(path + '.part', path);
      }).catch((error) => console.error('同步资料保存失败：' + error.message));
    }, saveDelayMs);
    timer.unref?.();
  }
  function prune() {
    const cutoff = now() - TOMBSTONE_MS;
    for (const [k, r] of Object.entries(state.records)) if (r.deleted && r.at < cutoff) delete state.records[k];
    for (const table of SYNC_TABLES) {
      const live = Object.entries(state.records).filter(([, r]) => r.table === table && !r.deleted);
      if (live.length <= CAPS[table]) continue;
      live.sort((a, b) => a[1].at - b[1].at);
      for (const [k] of live.slice(0, live.length - CAPS[table])) delete state.records[k];
    }
  }

  /**
   * 设备推送本地修改并取回其他设备的修改。
   * since：设备上次拿到的版本号；epoch 不一致（电脑资料被重置或换了电脑）时设备应从 0 全量同步。
   * 返回 rev 之后的修改，每次最多 MAX_CHANGES 条，more 为真时设备用新的 rev 继续拉取。
   */
  function exchange({ epoch, since = 0, changes = [] }) {
    if (!Array.isArray(changes) || changes.length > MAX_CHANGES || !changes.every(validChange)) throw Object.assign(new Error('同步数据格式无效'), { status: 400 });
    if (!Number.isInteger(since) || since < 0) throw Object.assign(new Error('同步版本号无效'), { status: 400 });
    const reset = epoch !== state.epoch;
    const from = reset ? 0 : since;
    let accepted = 0;
    for (const c of changes) {
      const k = id(c.table, c.key), existing = state.records[k];
      // 新的覆盖旧的；时间相同时删除优先，保证各设备结果一致
      if (existing && (existing.at > c.at || existing.at === c.at && (existing.deleted || !c.deleted))) continue;
      state.records[k] = { table: c.table, key: c.key, at: c.at, ...(c.deleted ? { deleted: true } : { value: c.value }), rev: ++state.rev };
      accepted++;
    }
    if (accepted) { prune(); persist(); }
    const pending = Object.values(state.records).filter((r) => r.rev > from).sort((a, b) => a.rev - b.rev);
    const page = [];
    let bytes = 0;
    for (const r of pending) {
      bytes += JSON.stringify(r).length;
      if (page.length >= MAX_CHANGES || page.length && bytes > MAX_PAGE_BYTES) break;
      page.push(r);
    }
    return {
      epoch: state.epoch, reset, accepted,
      rev: page.length < pending.length ? page[page.length - 1].rev : state.rev,
      more: page.length < pending.length,
      changes: page.map(({ rev, ...r }) => r),
    };
  }
  async function flush() { if (timer) { clearTimeout(timer); timer = undefined; const text = JSON.stringify(state); await writing; await mkdir(dirname(path), { recursive: true }); await writeFile(path + '.part', text, { mode: 0o600 }); await rename(path + '.part', path); } }
  return { load, exchange, flush, summary: () => ({ epoch: state.epoch, rev: state.rev, records: Object.keys(state.records).length }) };
}
