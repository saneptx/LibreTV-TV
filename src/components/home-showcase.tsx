'use client';

import { useEffect, useRef, useState, type ReactNode } from 'react';
import Link from 'next/link';
import { useQueries } from '@tanstack/react-query';
import { useLiveQuery } from 'dexie-react-hooks';
import { api } from '@/lib/client-api';
import { db, favoriteKeyOf, toggleFavorite } from '@/lib/db';
import { useAppStore } from '@/lib/store';
import { buildWatchUrl, cn, formatTime } from '@/lib/utils';
import type { DoubanItem } from '@/lib/types';
import { SmartImage } from './smart-image';
import { RecommendSection } from './douban-section';
import { useToast } from './toast';

/**
 * 首页陈列（与电视端 SuperTV 首页同一结构）：主推大图 → 继续观看 → 各榜单的横向海报行。
 * 榜单数据沿用「影视热榜」；设置里选了豆瓣分类或 Bangumi 时，海报行换成对应的筛选视图。
 */
const RAILS = [
  { id: 'douban_tv_chinese', title: '剧集热播榜 Top 10', kind: '剧集', ranked: true },
  { id: 'douban_movie_weekly', title: '电影热播榜 Top 10', kind: '电影', ranked: true },
  { id: 'douban_tv_global', title: '海外剧集', kind: '剧集', ranked: false },
  { id: 'douban_show_chinese', title: '国内综艺', kind: '综艺', ranked: false },
  { id: 'douban_show_global', title: '海外综艺', kind: '综艺', ranked: false },
  { id: 'baidu_teleplay', title: '百度热播剧', kind: '剧集', ranked: false },
] as const;

export function HomeShowcase({ onPick }: { onPick: (title: string) => void }) {
  const doubanEnabled = useAppStore((s) => s.doubanEnabled);
  const recommendSource = useAppStore((s) => s.recommendSource);
  const hot = doubanEnabled && recommendSource === 'hot-list';
  const lists = useQueries({
    queries: RAILS.map((rail) => ({
      queryKey: ['hot-list', rail.id],
      queryFn: ({ signal }: { signal: AbortSignal }) => api.hotList(rail.id, signal),
      enabled: hot,
      staleTime: 30 * 60_000,
    })),
  });
  const firstLoaded = lists.findIndex((q) => (q.data?.items.length ?? 0) > 0);
  const featured = firstLoaded >= 0 ? lists[firstLoaded].data!.items.filter((item) => item.cover).slice(0, 8) : [];

  return (
    <div className="space-y-10">
      {hot && (featured.length > 0
        ? <Hero items={featured} kind={RAILS[firstLoaded].kind} onPick={onPick} />
        : lists.some((q) => q.isLoading) && <div className="relative left-1/2 w-screen -translate-x-1/2 -mt-[5.5rem] h-[78vh] min-h-[520px] max-h-[820px] bg-card animate-pulse" />)}
      <ContinueRail />
      {hot && RAILS.map((rail, i) => {
        const query = lists[i], items = query.data?.items ?? [];
        if (!query.isLoading && items.length === 0) return null;
        return (
          <Rail key={rail.id} title={rail.title}>
            {query.isLoading
              ? Array.from({ length: 8 }).map((_, n) => <PosterSkeleton key={n} />)
              : (rail.ranked ? items.slice(0, 10) : items).map((item, n) => (
                <PosterCard key={item.id} title={item.title} cover={item.cover} rank={rail.ranked ? n + 1 : undefined}
                  badge={!rail.ranked && item.rating ? `豆瓣 ${item.rating}` : undefined} meta={rail.ranked ? [item.rating ? `${rail.kind} · 豆瓣 ${item.rating}` : rail.kind] : undefined}
                  onClick={() => onPick(item.title)} />
              ))}
          </Rail>
        );
      })}
      {doubanEnabled && !hot && <RecommendSection onPick={onPick} />}
    </div>
  );
}

/** 豆瓣海报换成大图，整幅铺开时不至于太糊 */
const largeCover = (url: string) => url.replace('/m_ratio_poster/', '/l_ratio_poster/').replace('/s_ratio_poster/', '/l_ratio_poster/');
const ROTATE_MS = 6000;

/** 宽屏（sm 及以上）才需要横版剧照 */
function useWide() {
  const [wide, setWide] = useState(false);
  useEffect(() => {
    const query = window.matchMedia('(min-width: 640px)'), update = () => setWide(query.matches);
    update();
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);
  return wide;
}

/**
 * 主推：整幅大图铺满首屏上半部分，片名、类型和操作压在大图下缘，下面一排圆点切换。
 * 手机上用竖版海报；宽屏上换成这部作品的横版剧照，拿不到剧照时才用海报的上半部分顶替。
 */
function Hero({ items: all, kind, onPick }: { items: DoubanItem[]; kind: string; onPick: (title: string) => void }) {
  const mode = useAppStore((s) => s.imageProxyMode);
  const customProxy = useAppStore((s) => s.customImageProxy);
  const { toast } = useToast();
  const [index, setIndex] = useState(0);
  const [leaving, setLeaving] = useState<string>();
  const [paused, setPaused] = useState(false);
  const favorites = useLiveQuery(() => db.favorites.toCollection().primaryKeys(), []) as string[] | undefined;
  const wide = useWide();
  const backdrops = useQueries({
    queries: all.map((it) => ({
      queryKey: ['backdrop', it.id],
      queryFn: ({ signal }: { signal: AbortSignal }) => api.backdrop(it.id, it.isTv !== false, signal),
      enabled: wide && /^\d+$/.test(it.id),
      staleTime: 6 * 60 * 60_000,
      retry: false,
    })),
  });
  // 宽屏上只主推有横版剧照的作品（竖版海报铺不满宽屏）；一张剧照都没有时才退回海报
  const withBackdrop = all.map((it, n) => ({ ...it, backdrop: backdrops[n].data?.backdrop || undefined, pending: backdrops[n].isLoading }));
  const usable = withBackdrop.filter((it) => it.backdrop || it.pending);
  const items = !wide ? withBackdrop.map((it) => ({ ...it, backdrop: undefined, pending: false })) : usable.length ? usable : withBackdrop;
  const count = items.length, current = Math.min(index, count - 1), item = items[current];
  const go = (n: number) => { if (n === current) return; setLeaving(item.id); setIndex(n); };
  useEffect(() => {
    if (paused || count < 2) return;
    const timer = window.setTimeout(() => go((current + 1) % count), ROTATE_MS);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current, paused, count]);
  // 页面不在前台时停止轮播；鼠标停在大图上不暂停（大图占了大半屏，鼠标几乎总在上面）
  useEffect(() => {
    const update = () => setPaused(document.hidden);
    update();
    document.addEventListener('visibilitychange', update);
    return () => document.removeEventListener('visibilitychange', update);
  }, []);
  const card = { name: item.title, pic: item.cover, year: '' };
  const saved = !!favorites?.includes(favoriteKeyOf(card));
  const toggle = async () => {
    const now = await toggleFavorite(card);
    toast(now ? `已把「${item.title}」加入片库` : `已把「${item.title}」移出片库`, 'success');
  };
  return (
    <section aria-label="本周热门" aria-roledescription="carousel"
      className="relative left-1/2 w-screen -translate-x-1/2 -mt-[5.5rem] h-[78vh] min-h-[520px] max-h-[820px] overflow-hidden">
      {items.map((it, n) => (
        // 当前一张居中，上一张向左滑出，其余的不带动画直接候在右侧
        <div key={it.id} aria-hidden className={cn('absolute inset-0 will-change-transform motion-reduce:transition-none',
          n === current ? 'translate-x-0 transition-transform duration-700 ease-out' : it.id === leaving ? '-translate-x-full transition-transform duration-700 ease-out' : 'translate-x-full invisible')}>
          {/* 只加载当前和下一张，避免一次请求全部大图 */}
          {(n === current || n === (current + 1) % count || it.id === leaving) && (
            it.backdrop
              ? <SmartImage key="backdrop" url={it.backdrop} mode={mode} customProxy={customProxy} alt="" loading="eager" className="absolute inset-0 w-full h-full object-cover object-[center_25%]" />
              // 剧照还在取的时候先不放图，免得海报闪一下再换成剧照
              : !it.pending && <SmartImage key="poster" url={largeCover(it.cover)} mode={mode} customProxy={customProxy} alt="" loading="eager" className="absolute inset-0 w-full h-full object-cover object-[center_20%]" />
          )}
        </div>
      ))}
      <div aria-hidden className="absolute inset-0 bg-gradient-to-t from-page from-20% via-page/75 via-40% to-transparent to-65% sm:from-5% sm:via-page/50 sm:via-35%" />
      <div aria-hidden className="absolute inset-x-0 top-0 h-32 bg-gradient-to-b from-page/80 to-transparent" />
      <div aria-hidden className="hidden sm:block absolute inset-0 bg-gradient-to-r from-page/60 via-page/10 to-transparent" />

      <div className="absolute inset-x-0 bottom-0 pb-6 text-center sm:text-left">
        <div className="max-w-7xl w-full mx-auto px-4 sm:px-8 flex flex-col items-center sm:items-start">
          <h2 className="text-4xl sm:text-6xl font-extrabold tracking-tight text-content line-clamp-2">{item.title}</h2>
          <p className="mt-3 text-sm sm:text-base text-content/80">{kind}{item.rating ? ` · 豆瓣 ${item.rating}` : ''} · 本周热门</p>
          <div className="mt-5 flex items-center gap-3">
            <button type="button" className="btn-primary px-8 py-3 text-base font-semibold" onClick={() => onPick(item.title)}>查找片源</button>
            <button type="button" onClick={() => void toggle()} aria-pressed={saved} aria-label={saved ? `把「${item.title}」移出片库` : `把「${item.title}」加入片库`} title={saved ? '已在片库' : '加入片库'}
              className="w-12 h-12 flex items-center justify-center rounded-full bg-chip/80 text-content backdrop-blur hover:bg-hover transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent">
              <svg viewBox="0 0 24 24" className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round">
                {saved ? <path d="M5 12.5l4.5 4.5L19 7.5" /> : <path d="M12 5v14M5 12h14" />}
              </svg>
            </button>
          </div>
          {count > 1 && (
            <div className="mt-6 flex items-center gap-2" role="tablist" aria-label="切换主推影片">
              {items.map((it, n) => (
                <button key={it.id} type="button" role="tab" aria-selected={n === current} aria-label={it.title} onClick={() => go(n)}
                  className={cn('h-2 rounded-full transition-all duration-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent', n === current ? 'w-7 bg-content' : 'w-2 bg-content/35 hover:bg-content/60')} />
              ))}
            </div>
          )}
        </div>
      </div>
    </section>
  );
}

/** 继续观看：最近看过的影片，点一下从上次的位置接着看 */
function ContinueRail() {
  const history = useLiveQuery(() => db.history.orderBy('timestamp').reverse().limit(20).toArray(), []);
  if (!history?.length) return null;
  return (
    <Rail title="继续观看" hint={`最近观看 ${history.length} 部`}>
      {history.map((entry) => {
        const percent = entry.duration > 0 ? Math.min(100, (entry.playbackPosition / entry.duration) * 100) : 0;
        return (
          <PosterCard
            key={entry.id}
            title={entry.title}
            cover={entry.pic}
            badge="继续观看"
            progress={percent}
            meta={[
              entry.totalEpisodes > 1 ? `第 ${entry.episodeIndex + 1} / ${entry.totalEpisodes} 集` : undefined,
              entry.duration > 0 ? `已看 ${formatTime(entry.playbackPosition)} / ${formatTime(entry.duration)}` : undefined,
            ].filter((line): line is string => !!line)}
            href={buildWatchUrl({ sourceKey: entry.sourceKey, vodId: entry.vodId, index: entry.episodeIndex, title: entry.title, sourceUrl: entry.sourceUrl })}
          />
        );
      })}
    </Rail>
  );
}

/** 横向海报行：标题 + 说明，可左右滚动；桌面端悬停时两侧出现翻页按钮 */
export function Rail({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  const scroller = useRef<HTMLDivElement>(null);
  const [edges, setEdges] = useState({ left: false, right: false });
  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const update = () => setEdges({ left: el.scrollLeft > 8, right: el.scrollLeft + el.clientWidth < el.scrollWidth - 8 });
    update();
    el.addEventListener('scroll', update, { passive: true });
    const observer = new ResizeObserver(update);
    observer.observe(el);
    for (const child of Array.from(el.children)) observer.observe(child);
    return () => { el.removeEventListener('scroll', update); observer.disconnect(); };
  }, [children]);
  const page = (direction: 1 | -1) => scroller.current?.scrollBy({ left: direction * scroller.current.clientWidth * 0.85, behavior: 'smooth' });
  return (
    <section aria-label={title} className="group/rail relative">
      <div className="flex items-baseline gap-3 mb-3">
        <h2 className="text-xl sm:text-2xl font-bold tracking-tight text-content">{title}</h2>
        {hint && <p className="text-xs text-faint">{hint}</p>}
      </div>
      {/* 上下留白给悬停放大和描边，负边距抵消，不影响行距 */}
      <div ref={scroller} className="flex gap-4 overflow-x-auto scroll-smooth snap-x py-2 -my-2 px-1 -mx-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
        {children}
      </div>
      {(['left', 'right'] as const).map((side) => edges[side] && (
        <button key={side} type="button" aria-label={side === 'left' ? '向左翻' : '向右翻'} onClick={() => page(side === 'left' ? -1 : 1)}
          className={cn('hidden md:flex absolute top-[42%] -translate-y-1/2 w-10 h-10 items-center justify-center rounded-full bg-surface/90 border border-line text-content shadow-lg backdrop-blur',
            'opacity-0 group-hover/rail:opacity-100 focus-visible:opacity-100 transition-opacity hover:bg-hover', side === 'left' ? '-left-3' : '-right-3')}>
          <svg viewBox="0 0 24 24" className={cn('w-4 h-4', side === 'left' && 'rotate-180')} fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round"><path d="M9 5l7 7-7 7" /></svg>
        </button>
      ))}
    </section>
  );
}

/** 海报卡片：圆角海报、悬停或聚焦时放大并出现白色描边（与电视端的选中态一致） */
export function PosterCard({ title, cover, badge, rank, meta, progress, onClick, href, className }: {
  title: string; cover?: string; badge?: string; rank?: number; meta?: string[]; progress?: number; onClick?: () => void; href?: string; className?: string;
}) {
  const mode = useAppStore((s) => s.imageProxyMode);
  const customProxy = useAppStore((s) => s.customImageProxy);
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [cover]);
  const body = (
    <>
      <div className="relative aspect-[2/3] rounded-2xl overflow-hidden bg-chip ring-0 ring-accent transition-all duration-200 group-hover/card:ring-2 group-hover/card:scale-[1.03] group-focus-visible/card:ring-2 group-focus-visible/card:scale-[1.03]">
        {cover && !failed
          ? <SmartImage url={cover} mode={mode} customProxy={customProxy} alt="" className="w-full h-full object-cover" onExhausted={() => setFailed(true)} />
          : <div className="w-full h-full flex items-center justify-center px-3 text-center text-sm text-faint">{title}</div>}
        {rank !== undefined && (
          <>
            <div aria-hidden className="absolute inset-x-0 top-0 h-20 bg-gradient-to-b from-black/60 to-transparent" />
            <span className="absolute top-1 left-3 text-4xl font-extrabold text-white drop-shadow-md">{rank}</span>
          </>
        )}
        {badge && <span className="absolute top-2 left-2 rounded-full bg-black/65 px-2 py-0.5 text-[11px] font-medium text-white backdrop-blur-sm">{badge}</span>}
        {progress !== undefined && progress > 0 && (
          <div className="absolute inset-x-2.5 bottom-2.5 h-1 rounded-full bg-white/25 overflow-hidden"><div className="h-full bg-white" style={{ width: `${progress}%` }} /></div>
        )}
      </div>
      <p className="mt-2.5 text-sm font-medium text-content line-clamp-1" title={title}>{title}</p>
      {meta?.map((line) => <p key={line} className="mt-0.5 text-xs text-faint line-clamp-1">{line}</p>)}
    </>
  );
  const classes = cn('group/card block shrink-0 snap-start w-[132px] sm:w-[168px] text-left focus-visible:outline-none', className);
  return href
    ? <Link href={href} className={classes}>{body}</Link>
    : <button type="button" className={classes} onClick={onClick}>{body}</button>;
}

function PosterSkeleton() {
  return (
    <div className="shrink-0 w-[132px] sm:w-[168px]">
      <div className="aspect-[2/3] rounded-2xl bg-chip animate-pulse" />
      <div className="h-3.5 mt-2.5 w-3/4 rounded bg-chip animate-pulse" />
    </div>
  );
}
