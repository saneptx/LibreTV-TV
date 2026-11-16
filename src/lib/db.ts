import Dexie, { type EntityTable } from 'dexie';
import type { SourceConfig } from './types';
// 仅类型引入：store.ts 运行时会引用本模块，反向只取类型不会形成运行时循环
import type { LiveProbeEntry } from './store';
import { PERSIST_KEY, flushPendingPersist } from './persist-storage';

/**
 * IndexedDB 持久化（替代旧版 localStorage 数据库）：
 * - 观看历史不再冗余存储全集 URL 列表（旧版长剧 × 50 条极易撑爆 5MB 配额），
 *   只存定位信息（sourceKey+vodId+index），进入播放页时按需重新拉详情，
 *   顺带免费获得「剧集更新同步」能力。
 * - v2：直播测活缓存从 localStorage（随 zustand persist 整体重写）迁入独立表，
 *   高频写入不再拖累设置快照的序列化。
 */

export interface HistoryEntry {
  /** 主键：`${sourceKey}_${vodId}` */
  id: string;
  sourceKey: string;
  sourceUrl?: string;
  vodId: string;
  title: string;
  pic?: string;
  episodeIndex: number;
  totalEpisodes: number;
  playbackPosition: number;
  duration: number;
  timestamp: number;
}

export interface ProgressEntry {
  key: string; // `${sourceKey}_${vodId}_${episodeIndex}`
  position: number;
  duration: number;
  updatedAt: number;
}

export interface SearchHistoryEntry {
  text: string;
  timestamp: number;
}

export interface SegmentMetaEntry {
  /** Cache Storage 中的 key（归一化的分片绝对地址，或同源代理地址） */
  key: string;
  /** 所属剧集：`${source}:${vodId}:${episodeIndex}`，用于按集淘汰 */
  episodeKey: string;
  /** 片段序号（1 基，便于排查） */
  index: number;
  bytes: number;
  /** 预取时记录的真实耗时（毫秒），loader 命中缓存时合成进 hls.js stats 防 ABR 误判 */
  costMs: number;
  lastAccess: number;
}

/** 片库收藏：一张影片卡片（片名、海报、年份和它的各个来源），主键 `${片名}|${年份}` */
export interface FavoriteEntry {
  key: string;
  card: Record<string, unknown>;
  addedAt: number;
  updatedAt: number;
}

/** 删除记录：同步时告诉其他设备这条已删，`id` 为 `${表名}:${主键}` */
export interface TombstoneEntry {
  id: string;
  table: SyncTable;
  key: string;
  at: number;
}

/** 参与多设备同步的表与各自的主键、修改时间字段 */
export const SYNC_TABLES = {
  history: { key: 'id', clock: 'timestamp' },
  progress: { key: 'key', clock: 'updatedAt' },
  searchHistory: { key: 'text', clock: 'timestamp' },
  favorites: { key: 'key', clock: 'updatedAt' },
} as const;
export type SyncTable = keyof typeof SYNC_TABLES;

export type DownloadStatus = 'waiting' | 'downloading' | 'paused' | 'completed' | 'error';

export interface DownloadTaskEntry {
  id: string;
  /** 播放页 m3u8 地址（直连或代理形式，入队时的原样） */
  url: string;
  title: string;
  /** 输出格式 */
  format: 'TS' | 'MP4';
  status: DownloadStatus;
  finished: number;
  total: number;
  errorNum: number;
  createdAt: number;
  updatedAt: number;
}

export const db = new Dexie('libretv') as Dexie & {
  history: EntityTable<HistoryEntry, 'id'>;
  progress: EntityTable<ProgressEntry, 'key'>;
  searchHistory: EntityTable<SearchHistoryEntry, 'text'>;
  liveProbe: EntityTable<LiveProbeEntry & { url: string }, 'url'>;
  segmentMeta: EntityTable<SegmentMetaEntry, 'key'>;
  downloads: EntityTable<DownloadTaskEntry, 'id'>;
  favorites: EntityTable<FavoriteEntry, 'key'>;
  tombstones: EntityTable<TombstoneEntry, 'id'>;
};

db.version(1).stores({
  history: 'id, timestamp',
  progress: 'key, updatedAt',
  searchHistory: 'text, timestamp',
});

// v2 仅新增表；Dexie 会自动继承低版本的表结构
db.version(2).stores({
  liveProbe: 'url',
});

// v3：视频片段缓存元数据（Cache Storage 存字节、这里存 LRU 索引）与离线下载任务
db.version(3).stores({
  segmentMeta: 'key, episodeKey, lastAccess',
  downloads: 'id, createdAt',
});

// v4：片库收藏（原先只存在电视端本机）与删除记录，供电脑服务在多设备间同步
db.version(4).stores({
  favorites: 'key, updatedAt',
  tombstones: 'id, at',
});

/** 本地数据变化（含同步写入）时通知订阅者：同步模块据此尽快推送，界面据此刷新 */
type ChangeListener = (origin: 'local' | 'sync') => void;
const changeListeners = new Set<ChangeListener>();
export function onLibraryChange(listener: ChangeListener): () => void { changeListeners.add(listener); return () => changeListeners.delete(listener); }
export function notifyLibraryChange(origin: 'local' | 'sync' = 'local'): void { changeListeners.forEach((l) => { try { l(origin); } catch { /* 订阅者异常不影响写入 */ } }); }

async function bury(table: SyncTable, keys: string[]): Promise<void> {
  if (keys.length === 0) return;
  const at = Date.now();
  await db.tombstones.bulkPut(keys.map((key) => ({ id: `${table}:${key}`, table, key, at })));
}
/** 重新写入时去掉旧的删除记录，否则下次推送会把刚恢复的记录再删掉 */
async function unbury(table: SyncTable, key: string): Promise<void> {
  await db.tombstones.delete(`${table}:${key}`);
}

export const MAX_HISTORY = 100;
export const MAX_SEARCH_HISTORY = 10;

export async function upsertHistory(entry: Omit<HistoryEntry, 'id'>): Promise<void> {
  const id = `${entry.sourceKey}_${entry.vodId}`;
  const existing = await db.history.get(id);
  const merged: HistoryEntry = {
    ...existing,
    ...entry,
    id,
    playbackPosition: entry.playbackPosition > 10 ? entry.playbackPosition : existing?.playbackPosition ?? 0,
    duration: entry.duration || existing?.duration || 0,
    timestamp: Date.now(),
  };
  await db.history.put(merged);
  await unbury('history', id);
  notifyLibraryChange();
  // 淘汰最旧记录（只删本机，不记删除：其他设备可以保留更长的历史）
  const count = await db.history.count();
  if (count > MAX_HISTORY) {
    const oldest = await db.history.orderBy('timestamp').limit(count - MAX_HISTORY).toArray();
    await db.history.bulkDelete(oldest.map((o) => o.id));
  }
}

export async function updateHistoryProgress(
  sourceKey: string,
  vodId: string,
  position: number,
  duration: number
): Promise<void> {
  const id = `${sourceKey}_${vodId}`;
  const existing = await db.history.get(id);
  if (!existing) return;
  if (Math.abs(existing.playbackPosition - position) < 2 && Math.abs(existing.duration - duration) < 2) return;
  await db.history.update(id, {
    playbackPosition: position,
    duration,
    timestamp: Date.now(),
  });
  notifyLibraryChange();
}

export async function removeHistory(sourceKey: string, vodId: string): Promise<void> {
  const id = `${sourceKey}_${vodId}`;
  await db.history.delete(id);
  await bury('history', [id]);
  notifyLibraryChange();
}

export async function clearAllHistory(): Promise<void> {
  // 一并清进度表：否则从历史重新打开时可能取到已清空的旧进度
  const [history, progress] = await Promise.all([db.history.toCollection().primaryKeys(), db.progress.toCollection().primaryKeys()]);
  await Promise.all([db.history.clear(), db.progress.clear()]);
  await Promise.all([bury('history', history), bury('progress', progress)]);
  notifyLibraryChange();
}

export function progressKeyOf(sourceKey: string, vodId: string, episodeIndex: number): string {
  return `${sourceKey}_${vodId}_${episodeIndex}`;
}

export async function saveProgress(sourceKey: string, vodId: string, episodeIndex: number, position: number, duration: number): Promise<void> {
  if (!duration || position < 1) return;
  await db.progress.put({
    key: progressKeyOf(sourceKey, vodId, episodeIndex),
    position,
    duration,
    updatedAt: Date.now(),
  });
  await unbury('progress', progressKeyOf(sourceKey, vodId, episodeIndex));
  notifyLibraryChange();
}

export async function clearProgress(sourceKey: string, vodId: string, episodeIndex: number): Promise<void> {
  const key = progressKeyOf(sourceKey, vodId, episodeIndex);
  await db.progress.delete(key);
  await bury('progress', [key]);
  notifyLibraryChange();
}

export async function addSearchHistory(text: string): Promise<void> {
  const t = text.trim().slice(0, 50);
  if (!t) return;
  await db.searchHistory.put({ text: t, timestamp: Date.now() });
  await unbury('searchHistory', t);
  notifyLibraryChange();
  const count = await db.searchHistory.count();
  if (count > MAX_SEARCH_HISTORY) {
    const oldest = await db.searchHistory.orderBy('timestamp').limit(count - MAX_SEARCH_HISTORY).toArray();
    await db.searchHistory.bulkDelete(oldest.map((o) => o.text));
  }
}

export async function removeSearchHistory(text: string): Promise<void> {
  await db.searchHistory.delete(text);
  await bury('searchHistory', [text]);
  notifyLibraryChange();
}

export async function clearSearchHistory(): Promise<void> {
  const keys = await db.searchHistory.toCollection().primaryKeys();
  await db.searchHistory.clear();
  await bury('searchHistory', keys);
  notifyLibraryChange();
}

/** 撤销「清空搜索记录」：按原时间戳回填，保持原有顺序 */
export async function restoreSearchHistory(entries: SearchHistoryEntry[]): Promise<void> {
  if (entries.length === 0) return;
  // 用新时间戳回填才能盖过刚同步出去的删除记录；相对顺序保持不变
  const now = Date.now(), newest = Math.max(...entries.map((e) => e.timestamp));
  await db.searchHistory.bulkPut(entries.map((e) => ({ ...e, timestamp: now - (newest - e.timestamp) })));
  await db.tombstones.bulkDelete(entries.map((e) => `searchHistory:${e.text}`));
  notifyLibraryChange();
}

// —— 片库收藏 ——

export function favoriteKeyOf(card: Record<string, unknown>): string {
  return `${String(card.name ?? '').trim()}|${String(card.year ?? '')}`;
}

export async function listFavorites(): Promise<FavoriteEntry[]> {
  return (await db.favorites.toArray()).sort((a, b) => b.addedAt - a.addedAt);
}

/** 切换收藏，返回切换后是否在片库里 */
export async function toggleFavorite(card: Record<string, unknown>): Promise<boolean> {
  const key = favoriteKeyOf(card);
  if (!key.split('|')[0]) throw new Error('影片缺少片名');
  if (await db.favorites.get(key)) {
    await db.favorites.delete(key);
    await bury('favorites', [key]);
    notifyLibraryChange();
    return false;
  }
  const now = Date.now();
  await db.favorites.put({ key, card, addedAt: now, updatedAt: now });
  await unbury('favorites', key);
  notifyLibraryChange();
  return true;
}

/** 迁入旧版电视端本机收藏：已有或已被删除过的不覆盖，返回迁入条数 */
export async function importFavorites(cards: Record<string, unknown>[]): Promise<number> {
  let added = 0;
  for (const card of cards.slice(0, 2000)) {
    const key = favoriteKeyOf(card);
    if (!key.split('|')[0] || await db.favorites.get(key) || await db.tombstones.get(`favorites:${key}`)) continue;
    const addedAt = Number(card.addedAt) || Date.now();
    const { addedAt: _drop, ...rest } = card;
    await db.favorites.put({ key, card: rest, addedAt, updatedAt: addedAt });
    added++;
  }
  if (added) notifyLibraryChange();
  return added;
}

// —— 直播测活缓存（TTL 过滤由调用方负责，本层只管存取） ——

export async function loadLiveProbeResults(): Promise<Record<string, LiveProbeEntry>> {
  const rows = await db.liveProbe.toArray();
  const out: Record<string, LiveProbeEntry> = {};
  for (const r of rows) out[r.url] = r;
  return out;
}

export async function saveLiveProbeResults(entries: Record<string, LiveProbeEntry>): Promise<void> {
  const rows = Object.entries(entries).map(([url, e]) => ({ ...e, url }));
  if (rows.length > 0) await db.liveProbe.bulkPut(rows);
}

export async function clearLiveProbeResultsDb(): Promise<void> {
  await db.liveProbe.clear();
}

// —— 配置导入导出（兼容旧版 LibreTV-Settings JSON 结构的导出格式） ——

export async function exportConfig(): Promise<string> {
  // 导出前 flush：把节流缓冲中的最新设置落盘，避免导出的是 800ms 前的旧快照
  flushPendingPersist();
  const history = await db.history.toArray();
  const settings = localStorage.getItem(PERSIST_KEY);

  const data: Record<string, unknown> = {
    viewingHistory: JSON.stringify(history),
  };
  if (settings) data[PERSIST_KEY] = settings;

  return JSON.stringify({
    name: 'LibreTV-Settings',
    time: Date.now().toString(),
    cfgVer: '2.0.0',
    data,
  });
}

export async function importConfig(content: string): Promise<void> {
  const config = JSON.parse(content) as {
    name?: string;
    data?: Record<string, string>;
  };
  if (config.name !== 'LibreTV-Settings') throw new Error('配置文件格式不正确');
  const data = config.data || {};

  if (typeof data[PERSIST_KEY] === 'string') {
    // 关键：先 flush 节流缓冲中的旧状态再写导入配置，否则缓冲会在
    // 之后 flush 时用导入前的旧状态覆盖刚写入的配置（导入静默丢失）
    flushPendingPersist();
    localStorage.setItem(PERSIST_KEY, data[PERSIST_KEY]);
  }

  if (typeof data['viewingHistory'] === 'string') {
    // 兼容旧版历史结构（含全集 URL 列表），只迁移定位信息
    const legacy = JSON.parse(data['viewingHistory']) as Record<string, unknown>[];
    for (const item of legacy.slice(0, MAX_HISTORY)) {
      const sourceKey = String(item.sourceCode || item.sourceName || '');
      const vodId = String(item.vod_id || '');
      const title = String(item.title || '未知视频');
      if (!sourceKey || !vodId) continue;
      const totalEpisodes = Array.isArray(item.episodes) ? item.episodes.length : 0;
      await upsertHistory({
        sourceKey,
        vodId,
        title,
        episodeIndex: typeof item.episodeIndex === 'number' ? item.episodeIndex : 0,
        totalEpisodes,
        playbackPosition: typeof item.playbackPosition === 'number' ? item.playbackPosition : 0,
        duration: typeof item.duration === 'number' ? item.duration : 0,
        timestamp: typeof item.timestamp === 'number' ? item.timestamp : Date.now(),
      });
    }
  }
}

export type { SourceConfig };
