import type { DoubanItem } from './types';

/**
 * 影视热榜与主推剧照的纯解析逻辑：不依赖 Node，网页服务端和安卓应用内（设备直连）共用。
 */

/** 榜单标识：豆瓣五个周榜 + 百度热播剧榜 */
export const HOT_LIST_IDS = [
  'douban_movie_weekly',
  'douban_tv_chinese',
  'douban_tv_global',
  'douban_show_chinese',
  'douban_show_global',
  'baidu_teleplay',
] as const;

export type HotListId = (typeof HOT_LIST_IDS)[number];

export function isHotListId(id: string): id is HotListId {
  return (HOT_LIST_IDS as readonly string[]).includes(id);
}

export const DOUBAN_WEEKLY_CATEGORY: Partial<Record<HotListId, string>> = {
  douban_movie_weekly: 'movie',
  douban_tv_chinese: 'tv_chinese',
  douban_tv_global: 'tv_global',
  douban_show_chinese: 'show_chinese',
  douban_show_global: 'show_global',
};

// —— 60s API 原始结构（只取用到的字段） ——

export interface DoubanWeeklyRaw {
  id?: number | string;
  title?: string;
  rating?: number;
  cover_proxy?: string;
  cover?: string;
}

export interface BaiduTeleplayRaw {
  title?: string;
  cover?: string | null;
}

export interface SixtyResponse<T> {
  code?: number;
  data?: T;
}

/** 豆瓣周榜条目 → DoubanItem；缺标题或封面的脏数据丢弃 */
export function doubanWeeklyToItem(raw: DoubanWeeklyRaw, isTv: boolean): DoubanItem | undefined {
  // 原生 cover 是 img*.doubanio.com，命中图片代理的豆瓣白名单并带 Referer 伪装，
  // 可稳定加载；cover_proxy（doubanio.viki.moe 公共镜像）限流严重，仅作兜底
  const cover = raw.cover || raw.cover_proxy;
  if (!raw.id || !raw.title || !cover) return undefined;
  return {
    id: String(raw.id),
    title: raw.title,
    cover,
    rating: raw.rating && raw.rating > 0 ? String(raw.rating) : undefined,
    isTv,
  };
}

/** 百度热播剧条目 → DoubanItem；无评分，海报为空的条目丢弃 */
export function baiduTeleplayToItem(raw: BaiduTeleplayRaw, index: number): DoubanItem | undefined {
  if (!raw.title || !raw.cover) return undefined;
  return {
    // 百度榜无稳定 id，用榜单内序号保证 React key 唯一
    id: `baidu_${index}`,
    title: raw.title,
    cover: raw.cover,
    isTv: true,
  };
}

/** 60s API 的榜单应答 → 条目列表；数据异常或为空时抛错 */
export function parseHotList(id: HotListId, raw: unknown): DoubanItem[] {
  const data = (raw as SixtyResponse<unknown>)?.data;
  if (!Array.isArray(data)) throw new Error('榜单数据异常');
  const items = (DOUBAN_WEEKLY_CATEGORY[id]
    ? (data as DoubanWeeklyRaw[]).map((entry) => doubanWeeklyToItem(entry, id !== 'douban_movie_weekly'))
    : (data as BaiduTeleplayRaw[]).map((entry, i) => baiduTeleplayToItem(entry, i))
  ).filter((item): item is DoubanItem => item !== undefined);
  if (items.length === 0) throw new Error('榜单数据为空');
  return items;
}

export const hotListPath = (id: HotListId) => (DOUBAN_WEEKLY_CATEGORY[id] ? `/v2/douban/weekly/${DOUBAN_WEEKLY_CATEGORY[id]}` : '/v2/baidu/teleplay');

interface PhotoRaw { image?: { large?: { url?: unknown; width?: unknown; height?: unknown } } }

/** 按接口给出的顺序（热度）取第一张宽度足够、比例接近 3:2～21:9 的横图 */
export function pickBackdrop(photos: unknown): string | null {
  if (!Array.isArray(photos)) return null;
  for (const photo of photos as PhotoRaw[]) {
    const large = photo?.image?.large;
    if (!large || typeof large.url !== 'string' || typeof large.width !== 'number' || typeof large.height !== 'number') continue;
    const ratio = large.width / large.height;
    if (large.width < 1200 || ratio < 1.4 || ratio > 2.5) continue;
    try {
      const url = new URL(large.url);
      if (url.protocol === 'https:' && /^img\d+\.doubanio\.com$/.test(url.hostname)) return url.href;
    } catch { /* 地址无效，看下一张 */ }
  }
  return null;
}

export const BACKDROP_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';
export const backdropUrl = (id: string, isTv: boolean) => `https://m.douban.com/rexxar/api/v2/${isTv ? 'tv' : 'movie'}/${id}/photos?start=0&count=30`;
export const SIXTY_BASE = 'https://60s.crystelf.top', SIXTY_FALLBACK = 'https://60s.viki.moe';
