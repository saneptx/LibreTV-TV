import { fetchUpstream, getCache, setCache } from './fetch-utils';
import { BACKDROP_UA as UA, backdropUrl, pickBackdrop } from './hot-list-core';

export { pickBackdrop } from './hot-list-core';

/**
 * 主推大图用的横版剧照：豆瓣榜单只给竖版海报，宽屏上铺不满，
 * 这里向豆瓣移动端接口要这部作品的剧照，挑一张够大的横图。拿不到时返回 null，界面退回海报。
 */
const HIT_TTL = 12 * 60 * 60 * 1000, MISS_TTL = 30 * 60 * 1000;
export async function fetchBackdrop(id: string, isTv: boolean): Promise<string | null> {
  const cacheKey = `backdrop:${isTv ? 'tv' : 'movie'}:${id}`;
  const cached = getCache<{ url: string | null }>(cacheKey);
  if (cached) return cached.url;
  let url: string | null = null;
  try {
    const res = await fetchUpstream(backdropUrl(id, isTv), {
      timeoutMs: 8000,
      headers: { 'User-Agent': UA, Referer: 'https://m.douban.com/', Accept: 'application/json' },
    });
    if (res.ok) url = pickBackdrop(((await res.json()) as { photos?: unknown }).photos);
  } catch { /* 豆瓣不通时当作没有剧照 */ }
  setCache(cacheKey, { url }, url ? HIT_TTL : MISS_TTL);
  return url;
}
