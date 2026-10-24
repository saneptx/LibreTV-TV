import type { SourceConfig } from './types';

const STORAGE_KEY = 'libretv-source-cache-v1';
const MAX_BYTES = 1024 * 1024;
interface Entry { key: string; json: string; at: number; freshUntil: number; expires: number }
type StorageLike = Pick<Storage, 'getItem' | 'setItem'>;

/** 有界、可持久化的业务缓存；不保存影片的实际播放地址。存储不可用时降级到内存。 */
export function createSourceCache(storage: () => StorageLike | undefined = () => globalThis.localStorage, now = () => Date.now()) {
  const entries = new Map<string, Entry>();
  let loaded = false, timer: ReturnType<typeof setTimeout> | undefined;
  const prune = () => {
    for (const [key, entry] of entries) if (entry.expires <= now()) entries.delete(key);
    let size = [...entries.values()].reduce((sum, entry) => sum + JSON.stringify(entry).length * 2, 0);
    for (const [key, entry] of entries) {
      if (size <= MAX_BYTES && entries.size <= 250) break;
      entries.delete(key); size -= JSON.stringify(entry).length * 2;
    }
  };
  const load = () => {
    if (loaded) return;
    loaded = true;
    try {
      const raw = storage()?.getItem(STORAGE_KEY);
      if (raw && raw.length * 2 <= MAX_BYTES + 4096) {
        const parsed: unknown = JSON.parse(raw);
        if (Array.isArray(parsed)) for (const entry of parsed) {
          if (entry && typeof entry.key === 'string' && typeof entry.json === 'string' &&
              [entry.at, entry.freshUntil, entry.expires].every(Number.isFinite) && entry.expires > now()) entries.set(entry.key, entry);
        }
      }
    } catch { /* 损坏/禁用的存储不能阻断搜索 */ }
    prune();
  };
  const flush = () => {
    clearTimeout(timer); timer = undefined;
    if (!loaded) return;
    prune();
    try { storage()?.setItem(STORAGE_KEY, JSON.stringify([...entries.values()])); } catch { /* 配额不足时仍可复用内存缓存 */ }
  };
  return {
    get<T>(key: string): { value: T; stale: boolean; ageMs: number } | undefined {
      load();
      const entry = entries.get(key);
      if (!entry) return;
      if (entry.expires <= now()) { entries.delete(key); return; }
      try {
        const value = JSON.parse(entry.json) as T;
        entries.delete(key); entries.set(key, entry);
        return { value, stale: entry.freshUntil <= now(), ageMs: Math.max(0, now() - entry.at) };
      } catch { entries.delete(key); }
    },
    set(key: string, value: unknown, ttlMs: number, retainMs = ttlMs) {
      load();
      const json = JSON.stringify(value);
      if (!json || json.length * 2 > 256 * 1024) return;
      entries.delete(key);
      entries.set(key, { key, json, at: now(), freshUntil: now() + ttlMs, expires: now() + Math.max(ttlMs, retainMs) });
      prune(); clearTimeout(timer); timer = setTimeout(flush, 250);
    },
    clear() { load(); entries.clear(); flush(); },
    flush,
  };
}
export const sourceCache = createSourceCache();
export const sourceIdentity = (source: SourceConfig) => [source.key, source.name, source.url, source.detail || '', source.isAdult === true];
export const searchCacheKey = (query: string, source: SourceConfig, filterAdult: boolean) => JSON.stringify(['search', query.trim(), sourceIdentity(source), filterAdult]);
if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', sourceCache.flush);
  document.addEventListener('visibilitychange', () => { if (document.hidden) sourceCache.flush(); });
}
