import { sourceCache, sourceIdentity } from './source-cache';
import type { SearchResponse, SearchResultItem, SourceConfig } from './types';

export interface SourceCandidate {
  source: SourceConfig;
  result: SearchResultItem;
  ok?: boolean;
  ms?: number;
  episodes?: number;
  error?: string;
}
interface Snapshot { rows: SourceCandidate[] }
export interface DiscoveryUpdate extends Snapshot { cached: boolean; refreshing: boolean }
const keyFor = (title: string, sources: SourceConfig[], filter: boolean) => JSON.stringify([
  'discovery', title.trim(), sources.map(sourceIdentity).sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b))), filter,
]);
const check = (signal?: AbortSignal) => { if (signal?.aborted) throw new DOMException('已取消', 'AbortError'); };

/** 面板只缓存检查结论/集数；播放时依然按详情接口的短 TTL 获取有效地址。 */
export async function discoverSources(options: {
  title: string; sources: SourceConfig[]; filterAdult: boolean; signal?: AbortSignal; forceRefresh?: boolean;
  search: (force: boolean) => Promise<SearchResponse>;
  inspect: (item: SearchResultItem, source: SourceConfig, force: boolean) => Promise<Pick<SourceCandidate, 'ok' | 'ms' | 'episodes' | 'error'>>;
  onSnapshot: (snapshot: DiscoveryUpdate) => void;
  onTesting?: () => void;
  onWarning?: (message: string) => void;
}): Promise<SourceCandidate[]> {
  const { title, sources, filterAdult, signal, onSnapshot, forceRefresh = false } = options;
  check(signal);
  const key = keyFor(title, sources, filterAdult);
  const cached = sourceCache.get<Snapshot>(key);
  if (cached) {
    onSnapshot({ ...cached.value, cached: true, refreshing: cached.stale || forceRefresh });
    if (!cached.stale && !forceRefresh) return cached.value.rows;
  }
  try {
    const { list, failures = [] } = await options.search(forceRefresh);
    check(signal);
    // 一次断网不应抹掉此前可用的来源；保持旧列表，下一次打开仍会重试。
    if (!list.length && failures.length === sources.length && sources.length) throw new Error('来源请求全部失败，请检查网络或刷新来源');
    const rows: SourceCandidate[] = sources.flatMap(source => {
      const hits = list.filter(item => item.sourceKey === source.key);
      const result = hits.find(item => item.name.trim() === title.trim()) ?? hits.find(item => item.name.trim().startsWith(title.trim()));
      return result ? [{ source, result }] : [];
    });
    options.onTesting?.();
    let next = 0;
    await Promise.all(Array.from({ length: Math.min(4, rows.length) }, async () => {
      while (next < rows.length) {
        check(signal);
        const row = rows[next++];
        try { Object.assign(row, await options.inspect(row.result, row.source, forceRefresh)); }
        catch (error) { check(signal); Object.assign(row, { ok: false, error: error instanceof Error ? error.message : '片源暂不可用' }); }
      }
    }));
    check(signal);
    if (cached?.value.rows.some(row => row.ok) && !rows.some(row => row.ok) && failures.length) throw new Error('刷新未获取到可用来源，暂时保留缓存');
    const hasUsable = rows.some(row => row.ok);
    sourceCache.set(key, { rows }, hasUsable ? 5 * 60_000 : 30_000, hasUsable ? 24 * 60 * 60_000 : 30_000);
    onSnapshot({ rows, cached: false, refreshing: false });
    return rows;
  } catch (error) {
    check(signal);
    if (!cached) throw error;
    onSnapshot({ ...cached.value, cached: true, refreshing: false });
    options.onWarning?.(error instanceof Error ? error.message : '刷新失败，暂时使用缓存');
    return cached.value.rows;
  }
}

export function updateSourceCandidate(title: string, sources: SourceConfig[], filter: boolean, row: SourceCandidate) {
  const key = keyFor(title, sources, filter), cached = sourceCache.get<Snapshot>(key);
  if (!cached) return;
  cached.value.rows = cached.value.rows.map(old => old.source.key === row.source.key && old.result.vodId === row.result.vodId ? row : old);
  // 单源重测不能延长其余来源的有效期。
  sourceCache.set(key, cached.value, Math.max(0, 5 * 60_000 - cached.ageMs), Math.max(0, 24 * 60 * 60_000 - cached.ageMs));
}
