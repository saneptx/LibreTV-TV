import bundledSources from '../../source.json';
import { cmsRequestHeaders, filterAdultResults, filterRelevantResults, normalizeTitle, parseDetail, parseDetailPageHtml, parseSearchList } from './cms-parser';
import { decodeNativeBytes, encodeNativeBytes, nativeFetch, nativeRpc } from './native-http';
import { createQualityProbe } from './quality-probe-core';
import { parseSubscriptionJson, parseSubscriptionPayload } from './tvbox-parser';
import { sourceCache, searchCacheKey } from './source-cache';
import type { AuthStatusResponse, SearchResponse, SourceConfig, SourceSearchOutcome, VideoDetail } from './types';

export function standaloneStatus(): AuthStatusResponse {
  return {
    passwordRequired: false, verified: true, version: `${process.env.APP_VERSION || 'dev'} / Android 0.3.0`,
    defaultSources: bundledSources.map((s, i) => ({ ...s, key: `env_${i}`, url: s.url.trim().replace(/\/+$/, '') })),
    defaultLiveSources: [], defaultSubscriptions: [], defaultRecommendSource: null, defaultImageMode: null,
  };
}

/** 有界并发，避免投影仪同时发起数十次 DNS/TLS 和拖慢播放。中止后不再启动后续任务。 */
export async function mapConcurrent<T, R>(items: T[], concurrency: number, task: (item: T) => Promise<R>, signal?: AbortSignal): Promise<R[]> {
  const result: R[] = new Array(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (next < items.length) {
      if (signal?.aborted) throw new DOMException('已取消', 'AbortError');
      const index = next++;
      result[index] = await task(items[index]);
    }
  }));
  return result;
}

function cmsUrl(source: SourceConfig, params: Record<string, string>): string {
  const url = new URL(source.url);
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('无效的源地址');
  for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value);
  return url.href;
}
async function json(url: string, signal?: AbortSignal): Promise<unknown> {
  const res = await nativeFetch(url, { headers: cmsRequestHeaders(), signal, timeoutMs: 8000 });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

function aggregate(outcomes: SourceSearchOutcome[], wd: string, filterAdult: boolean): SearchResponse {
  const seen = new Set<string>();
  const exact = normalizeTitle(wd);
  const list = filterRelevantResults(filterAdultResults(outcomes.flatMap((o) => o.list), filterAdult), wd)
    .filter((item) => { const key = `${item.sourceKey}:${item.vodId}`; if (seen.has(key)) return false; seen.add(key); return true; })
    .sort((a, b) => Number(normalizeTitle(b.name) === exact) - Number(normalizeTitle(a.name) === exact) || a.name.localeCompare(b.name, 'zh-Hans-CN') || a.sourceName.localeCompare(b.sourceName, 'zh-Hans-CN'));
  return { list, failures: outcomes.filter((o) => !o.ok).map((o) => ({ sourceKey: o.sourceKey, error: o.error || '请求失败', timedOut: o.timedOut })) };
}

export async function standaloneSearch(wd: string, sources: SourceConfig[], filterAdult: boolean, opts?: { forceRefresh?: boolean; signal?: AbortSignal; onSource?: (outcome: SourceSearchOutcome) => void }): Promise<SearchResponse> {
  if (!wd.trim() || wd.length > 100) throw new Error('搜索关键词无效');
  if (!sources.length) throw new Error('请至少选择一个点播源');
  const outcomes = await mapConcurrent(sources.slice(0, 50), 6, async (source) => {
    if (opts?.signal?.aborted) throw new DOMException('已取消', 'AbortError');
    const cacheKey = searchCacheKey(wd, source, filterAdult);
    const cached = !opts?.forceRefresh && sourceCache.get<SourceSearchOutcome>(cacheKey);
    if (cached && !cached.stale) { opts?.onSource?.(cached.value); return cached.value; }
    const start = Date.now();
    const controller = new AbortController();
    const abort = () => controller.abort();
    opts?.signal?.addEventListener('abort', abort, { once: true });
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, 10000);
    let outcome: SourceSearchOutcome;
    try {
      const page = (pg: number) => json(cmsUrl(source, { ac: 'videolist', wd, pg: String(pg) }), controller.signal);
      const first = await page(1);
      const rawPages = Number((first as { pagecount?: unknown })?.pagecount ?? 1);
      const pages = Number.isFinite(rawPages) ? Math.min(5, Math.max(1, Math.floor(rawPages))) : 1;
      const list = parseSearchList(first, source);
      for (let pg = 2; pg <= pages; pg++) {
        try { list.push(...parseSearchList(await page(pg), source)); }
        catch { if (controller.signal.aborted) break; }
      }
      outcome = { sourceKey: source.key, ok: true, list, ms: Date.now() - start };
    } catch (error) {
      if (opts?.signal?.aborted) throw new DOMException('已取消', 'AbortError');
      outcome = { sourceKey: source.key, ok: false, list: [], ms: Date.now() - start, timedOut, error: timedOut ? '请求超时' : error instanceof Error ? error.message : '请求失败' };
    } finally { clearTimeout(timer); opts?.signal?.removeEventListener('abort', abort); }
    if (opts?.signal?.aborted) throw new DOMException('已取消', 'AbortError');
    outcome.list = aggregate([outcome], wd, filterAdult).list;
    sourceCache.set(cacheKey, outcome, outcome.ok ? 10 * 60_000 : 30_000);
    opts?.onSource?.(outcome);
    return outcome;
  }, opts?.signal);
  return aggregate(outcomes, wd, filterAdult);
}

const details = new Map<string, { detail: VideoDetail; at: number }>();
export async function standaloneDetail(id: string, source: SourceConfig, signal?: AbortSignal, opts?: { forceRefresh?: boolean }): Promise<VideoDetail> {
  if (!/^[\w-]+$/.test(id)) throw new Error('无效的视频 ID');
  if (signal?.aborted) throw new DOMException('已取消', 'AbortError');
  const key = `${source.key}|${source.name}|${source.url}|${source.detail}|${id}`;
  const cached = details.get(key);
  if (!opts?.forceRefresh && cached && Date.now() - cached.at < 60000) return cached.detail;
  let resolved: VideoDetail | undefined;
  try { resolved = parseDetail(await json(cmsUrl(source, { ac: 'videolist', ids: id }), signal), source); }
  catch (error) { if (signal?.aborted) throw error; }
  if (!resolved?.episodes.length) {
    const root = (source.detail || new URL(source.url).origin).replace(/\/+$/, '');
    const res = await nativeFetch(`${root}/index.php/vod/detail/id/${id}.html`, { headers: cmsRequestHeaders(), signal });
    if (res.ok) resolved = parseDetailPageHtml(await res.text(), source);
  }
  if (!resolved?.episodes.length) throw new Error('未找到播放资源');
  if (signal?.aborted) throw new DOMException('已取消', 'AbortError');
  if (details.size >= 100) details.delete(details.keys().next().value!);
  details.set(key, { detail: resolved, at: Date.now() });
  return resolved;
}

export const standaloneProbeQuality = createQualityProbe({
  fetchUpstream: (url, options) => nativeFetch(url, { ...options, maxBytes: 96 * 1024, prefix: true }),
  checkUpstreamAllowed: async (url) => ({ ok: /^https?:\/\//i.test(url), reason: '无效的源地址' }),
  decryptAes128CbcPrefix: async (buf, key, iv) => decodeNativeBytes((await nativeRpc('decrypt', {
    body: encodeNativeBytes(buf), key: encodeNativeBytes(key), iv: encodeNativeBytes(iv),
  })).body),
});

export async function standaloneSourceList(url: string) {
  const res = await nativeFetch(url);
  if (!res.ok) throw new Error(`订阅地址返回 HTTP ${res.status}`);
  return parseSubscriptionPayload(parseSubscriptionJson(await res.text()));
}
