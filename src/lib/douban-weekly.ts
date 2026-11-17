import type { DoubanItem } from './types';
import { hotListPath, parseHotList, SIXTY_FALLBACK, type HotListId } from './hot-list-core';

export { HOT_LIST_IDS, isHotListId, doubanWeeklyToItem, baiduTeleplayToItem, type HotListId } from './hot-list-core';
import { fetchUpstream, getCache, setCache } from './fetch-utils';

/**
 * 影视热榜聚合（经 60s API，免 key，https://github.com/vikiboss/60s）。
 * 豆瓣周榜走其 rexxar 移动端接口（需伪装 iPhone UA），百度热播剧榜解析 top.baidu.com，
 * 均由 60s 代为抓取；这里只做服务端转发 + 内存缓存 + 字段归一。
 * 默认走社区实例 crystelf.top，失败自动回退官方公共实例（每日额度有限，
 * 数据中心出口 IP 常被限流，Vercel 上不可依赖）；可用 60S_API_BASE 指向自部署实例。
 */

const CACHE_TTL = 60 * 60 * 1000;
const UA = 'LibreTV (+https://github.com/LibreSpark/LibreTV)';

const FALLBACK_BASE = SIXTY_FALLBACK;

function apiBase(): string {
  return (process.env['60S_API_BASE'] || 'https://60s.crystelf.top').replace(/\/+$/, '');
}

async function fetchJson(path: string): Promise<unknown> {
  const primary = apiBase();
  // 主实例失败（限流/宕机/网络不通）时回退官方实例；两者相同则只试一次
  const bases = primary === FALLBACK_BASE ? [primary] : [primary, FALLBACK_BASE];

  let lastError: unknown;
  for (const base of bases) {
    try {
      const res = await fetchUpstream(`${base}${path}`, {
        timeoutMs: 8000,
        headers: { 'User-Agent': UA, Accept: 'application/json' },
      });
      if (!res.ok) throw new Error(`60s API 响应异常 (${res.status})`);
      return await res.json();
    } catch (err) {
      lastError = err;
    }
  }
  throw lastError instanceof Error ? lastError : new Error('60s API 请求失败');
}

export async function fetchHotList(id: HotListId): Promise<DoubanItem[]> {
  const cacheKey = `hot-list:${id}`;
  const cached = getCache<DoubanItem[]>(cacheKey);
  if (cached) return cached;

  const items: DoubanItem[] = parseHotList(id, await fetchJson(hotListPath(id)));

  setCache(cacheKey, items, CACHE_TTL);
  return items;
}
