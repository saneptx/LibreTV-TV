import { db, MAX_HISTORY, MAX_SEARCH_HISTORY, notifyLibraryChange, onLibraryChange, SYNC_TABLES, type SyncTable } from './db';

/**
 * 观看历史、各集进度、搜索记录、片库收藏的多设备同步（以电脑服务为中心，不需要账号）。
 * 每条记录以自身的修改时间为准，新的覆盖旧的；删除通过删除记录同步。
 * 设备记住电脑给的版本号，每次只推送本机上次同步之后的修改、只拉取电脑上更新的部分。
 */
export interface SyncChange { table: SyncTable; key: string; at: number; value?: Record<string, unknown>; deleted?: true }
export interface SyncReply { epoch: string; reset: boolean; rev: number; more: boolean; changes: SyncChange[] }
export type SyncTransport = (body: { epoch?: string; since: number; changes: SyncChange[] }, signal?: AbortSignal) => Promise<SyncReply>;
interface SyncState { server: string; epoch?: string; rev: number; pushedAt: number }

const STATE_KEY = 'supertv-library-sync';
/** 单次推送的大小上限：电脑服务按 3 MB 收、原生网络通道按 2 MB 发 */
const PUSH_BYTES = 1.5 * 1024 * 1024, PUSH_COUNT = 1000;

const tableOf = (table: SyncTable) => db[table] as unknown as import('dexie').Table<Record<string, unknown>, string>;
const clockOf = (table: SyncTable, row: Record<string, unknown>) => Number(row[SYNC_TABLES[table].clock]) || 0;

function loadState(server: string): SyncState {
  try {
    const saved = JSON.parse(localStorage.getItem(STATE_KEY) || 'null') as SyncState | null;
    if (saved && saved.server === server && Number.isInteger(saved.rev)) return saved;
  } catch { /* 读不到就全量同步一次 */ }
  return { server, rev: 0, pushedAt: 0 };
}
function saveState(state: SyncState) { try { localStorage.setItem(STATE_KEY, JSON.stringify(state)); } catch { /* 下次重新全量同步 */ } }

/** 本机在 since 之后（不含）修改或删除的记录 */
export async function collectChanges(since: number): Promise<SyncChange[]> {
  const changes: SyncChange[] = [];
  for (const table of Object.keys(SYNC_TABLES) as SyncTable[]) {
    const { key, clock } = SYNC_TABLES[table];
    const rows = await tableOf(table).where(clock).above(since).toArray();
    for (const row of rows) changes.push({ table, key: String(row[key]), at: clockOf(table, row), value: row });
  }
  for (const t of await db.tombstones.where('at').above(since).toArray()) changes.push({ table: t.table, key: t.key, at: t.at, deleted: true });
  return changes;
}

/** 合并其他设备的修改，返回实际改动的条数 */
export async function applyChanges(changes: SyncChange[]): Promise<number> {
  let applied = 0;
  await db.transaction('rw', [db.history, db.progress, db.searchHistory, db.favorites, db.tombstones], async () => {
    for (const c of changes) {
      if (!(c.table in SYNC_TABLES) || typeof c.key !== 'string') continue;
      const table = tableOf(c.table), id = `${c.table}:${c.key}`;
      const [local, tomb] = await Promise.all([table.get(c.key), db.tombstones.get(id)]);
      if (c.deleted) {
        if (local && clockOf(c.table, local) > c.at) continue;
        if (local) { await table.delete(c.key); applied++; }
        if (!tomb || tomb.at < c.at) await db.tombstones.put({ id, table: c.table, key: c.key, at: c.at });
        continue;
      }
      if (!c.value || local && clockOf(c.table, local) >= c.at || tomb && tomb.at >= c.at) continue;
      await table.put({ ...c.value, [SYNC_TABLES[c.table].key]: c.key });
      if (tomb) await db.tombstones.delete(id);
      applied++;
    }
    // 本机只保留最近的若干条（只删本机，不记删除）
    for (const [table, cap] of [['history', MAX_HISTORY], ['searchHistory', MAX_SEARCH_HISTORY]] as const) {
      const count = await tableOf(table).count();
      if (count > cap) await tableOf(table).bulkDelete(await tableOf(table).orderBy('timestamp').limit(count - cap).primaryKeys());
    }
  });
  return applied;
}

function batches(changes: SyncChange[]): SyncChange[][] {
  const out: SyncChange[][] = [[]];
  let bytes = 0;
  for (const c of changes) {
    const size = JSON.stringify(c).length;
    if (size > 16000) continue; // 电脑会拒收的超大记录（海报列表异常等）不推送
    const last = out[out.length - 1];
    if (last.length >= PUSH_COUNT || last.length && bytes + size > PUSH_BYTES) { out.push([]); bytes = 0; }
    out[out.length - 1].push(c); bytes += size;
  }
  return out;
}

/** 与电脑同步一次：先推送本机修改，再把电脑上的更新拉完 */
export async function syncOnce(server: string, transport: SyncTransport, signal?: AbortSignal): Promise<{ pushed: number; pulled: number }> {
  let state = loadState(server);
  // 本机资料库是空的、同步进度却显示同步过：资料库被系统清掉或重建了（浏览器数据损坏、清除数据等）。
  // 从头再拉一遍，把其他设备上还留着的记录取回来。
  if (state.rev !== 0 && !(await Promise.all([...(Object.keys(SYNC_TABLES) as SyncTable[]).map((t) => tableOf(t).count()), db.tombstones.count()])).some(Boolean)) state = { server, rev: 0, pushedAt: 0 };
  const startedAt = Date.now();
  let queue = batches(await collectChanges(state.pushedAt));
  let pushed = 0, pulled = 0, resent = false;
  for (;;) {
    const changes = queue.shift() ?? [];
    const reply = await transport({ epoch: state.epoch, since: state.rev, changes }, signal);
    pushed += changes.length;
    // 电脑上的资料是新的（重置过或换了电脑）：本机全部重新推送一遍
    if (reply.reset && state.pushedAt > 0 && !resent) { resent = true; queue = batches(await collectChanges(0)); }
    pulled += await applyChanges(reply.changes);
    state = { ...state, epoch: reply.epoch, rev: reply.rev };
    if (!queue.length && !reply.more) break;
  }
  state.pushedAt = startedAt - 1;
  saveState(state);
  if (pulled) notifyLibraryChange('sync');
  return { pushed, pulled };
}

export interface LibrarySyncStatus { enabled: boolean; syncing: boolean; lastSyncAt?: number; lastError?: string }

/**
 * 后台同步：启动时一次，本机有修改 3 秒后一次，之后每分钟一次。没配电脑服务时什么都不做。
 * server() 返回当前电脑服务地址（未配置返回空），transport 负责带配对码请求 /v1/sync。
 */
export function startLibrarySync(server: () => string, transport: SyncTransport, intervalMs = 60_000) {
  const status: LibrarySyncStatus = { enabled: false, syncing: false };
  let timer: ReturnType<typeof setTimeout> | undefined, running: Promise<void> | undefined, again = false, stopped = false;
  const controller = new AbortController();
  const schedule = (delay: number) => { clearTimeout(timer); if (!stopped) timer = setTimeout(() => { void run(); }, delay); };
  const run = (): Promise<void> => {
    if (running) { again = true; return running; }
    const address = server();
    status.enabled = !!address;
    if (stopped) return Promise.resolve();
    if (!address) { schedule(intervalMs); return Promise.resolve(); }
    status.syncing = true;
    running = syncOnce(address, transport, controller.signal)
      .then(() => { status.lastSyncAt = Date.now(); status.lastError = undefined; })
      .catch((error: unknown) => { status.lastError = error instanceof Error ? error.message : '同步失败'; })
      .finally(() => { status.syncing = false; running = undefined; schedule(again ? 1000 : intervalMs); again = false; });
    return running;
  };
  const unsubscribe = onLibraryChange((origin) => { if (origin === 'local') schedule(3000); });
  schedule(0);
  return {
    status,
    /** 立即同步（例如刚改了电脑服务地址） */
    now: () => run(),
    stop: () => { stopped = true; clearTimeout(timer); unsubscribe(); controller.abort(); },
  };
}
