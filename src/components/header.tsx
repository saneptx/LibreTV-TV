'use client';

import Link from 'next/link';
import { Suspense, useEffect, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { ThemeToggle } from './theme';
import { SourceManagerDrawer } from './source-manager';
import { HistoryPanel } from './history-panel';
import { requestShowDownloadManager } from './download-manager';
import { Icon } from './icon';
import { SearchHistoryDropdown, useSearchHistory } from './search-history';
import { useQuery } from '@tanstack/react-query';
import { api, STATUS_QUERY_KEY } from '@/lib/client-api';
import { cn } from '@/lib/utils';
import { getNativeHttpBridge } from '@/lib/native-http';

/** 顶部导航：Logo、搜索框（首页外）、历史、设置 */
/** overlay：首页顶栏悬浮在主推海报上，滚动后才出现底色 */
export function Header(props: { showSearch?: boolean; overlay?: boolean }) {
  // 内部读取地址栏参数，需要 Suspense 边界（静态预渲染的页面也能用）
  return <Suspense><HeaderBar {...props} /></Suspense>;
}

function HeaderBar({ showSearch = false, overlay = false }: { showSearch?: boolean; overlay?: boolean }) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const update = () => setScrolled(window.scrollY > 24);
    update();
    window.addEventListener('scroll', update, { passive: true });
    return () => window.removeEventListener('scroll', update);
  }, []);
  const searching = pathname === '/' && (params.has('s') || params.has('search'));
  const [query, setQuery] = useState('');
  // 与首页搜索框共用同一套「最近搜索」下拉逻辑
  const searchHistory = useSearchHistory(query);
  // 这台电脑配置了串流服务时才显示电脑片库入口
  const { data: status } = useQuery({ queryKey: STATUS_QUERY_KEY, queryFn: () => api.status() });

  const submitSearch = (text: string) => {
    const q = text.trim().slice(0, 100);
    if (!q) return;
    searchHistory.close();
    router.push(`/?s=${encodeURIComponent(q)}`, { scroll: false });
    // 顶栏搜索一并写入最近搜索（此前只有首页会记录）
    searchHistory.record(q);
  };

  const pickHistory = (text: string) => {
    setQuery(text);
    submitSearch(text);
  };

  const [inApp, setInApp] = useState(false);
  useEffect(() => setInApp(Boolean(getNativeHttpBridge())), []);
  const tabs = [
    { href: '/', label: '首页', icon: 'home' as const, active: pathname === '/' && !searching },
    { href: '/library', label: '片库', icon: 'plus' as const, active: pathname === '/library' },
    ...(status?.librarySync ? [{ href: '/downloads', label: '已下载', icon: 'download' as const, active: pathname === '/downloads' }] : []),
    // 安卓应用内没有网页服务端，直播依赖的接口不可用
    ...(inApp ? [] : [{ href: '/live', label: '直播', icon: 'bolt' as const, active: pathname === '/live' }]),
  ];

  return (
    <>
      <header className={cn("sticky top-0 z-40 transition-colors duration-300", overlay && !scrolled ? "bg-transparent" : "bg-page/80 backdrop-blur-xl")}>
        <div className="max-w-7xl mx-auto px-4 sm:px-8 h-16 flex items-center gap-3">
          <Link href="/" aria-label="SuperTV 首页" className="flex items-center gap-2 shrink-0">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/icons/logo.svg" alt="" className="w-7 h-7 rounded-lg border border-line" />
            <span className="hidden sm:inline text-lg font-bold tracking-tight text-content">SuperTV</span>
          </Link>

          {showSearch && (
            <form
              className="flex-1 max-w-xl hidden sm:block"
              onSubmit={(e) => {
                e.preventDefault();
                submitSearch(query);
              }}
            >
              <div ref={searchHistory.containerRef} className="relative">
                <input
                  className={cn(
                    'input w-full h-9',
                    // 展开时：上圆角与外框沿用聚焦样式，底边改为内部分隔线，与下拉拼成同一面板
                    searchHistory.visible &&
                      'rounded-b-none border-accent border-b-line bg-surface-raised focus-visible:ring-0'
                  )}
                  aria-label="搜索影片"
                  placeholder="搜索影片..."
                  value={query}
                  maxLength={100}
                  onChange={(e) => {
                    setQuery(e.target.value);
                    searchHistory.resetActive();
                  }}
                  onFocus={searchHistory.onFocus}
                  onKeyDown={(e) => searchHistory.onKeyDown(e, pickHistory)}
                  role="combobox"
                  aria-expanded={searchHistory.visible}
                  aria-controls="header-search-history"
                  aria-autocomplete="list"
                  aria-activedescendant={
                    searchHistory.visible && searchHistory.activeIndex >= 0
                      ? `header-search-history-${searchHistory.activeIndex}`
                      : undefined
                  }
                />
                {searchHistory.visible && (
                  <SearchHistoryDropdown
                    id="header-search-history"
                    matches={searchHistory.matches}
                    activeIndex={searchHistory.activeIndex}
                    onPick={pickHistory}
                    onRemove={searchHistory.remove}
                    onClearAll={searchHistory.clearAll}
                  />
                )}
              </div>
            </form>
          )}


          <nav aria-label="主导航" className="hidden md:flex items-center gap-0.5 rounded-full bg-chip/70 backdrop-blur-xl p-1 mx-auto">
            {tabs.map((tab) => <HeaderLink key={tab.href} href={tab.href} active={tab.active}>{tab.label}</HeaderLink>)}
          </nav>
          <div className="flex-1 md:hidden" />

          <div className="flex items-center gap-1.5 shrink-0">
            <Link href="/?search=1" aria-label="搜索" title="搜索" className="hidden md:flex w-9 h-9 items-center justify-center rounded-full bg-chip/80 text-muted hover:text-content hover:bg-hover transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/50">
              <Icon name="search" />
            </Link>
            <ThemeToggle />
            <IconButton label="观看历史" onClick={() => setHistoryOpen(true)}>
              <Icon name="clock" />
            </IconButton>
            <IconButton label="下载管理" onClick={requestShowDownloadManager}>
              <Icon name="download" />
            </IconButton>
            <IconButton label="设置" onClick={() => setSettingsOpen(true)}>
              <Icon name="gear" />
            </IconButton>
          </div>
        </div>
      </header>

      {/* 手机宽度：底部悬浮标签栏 */}
      <nav aria-label="主导航" className="md:hidden fixed z-40 inset-x-3 bottom-3 flex items-stretch gap-1 rounded-[28px] bg-surface/75 backdrop-blur-2xl border border-line p-1.5 shadow-2xl shadow-black/40 pb-[max(0.375rem,env(safe-area-inset-bottom))]">
        {[...tabs, { href: '/?search=1', label: '搜索', icon: 'search' as const, active: searching }].map((tab) => (
          <Link key={tab.href} href={tab.href} aria-current={tab.active ? 'page' : undefined}
            className={cn('flex-1 flex flex-col items-center gap-0.5 rounded-[22px] py-1.5 text-[11px] font-medium transition-colors', tab.active ? 'bg-hover text-content' : 'text-muted')}>
            <Icon name={tab.icon} className="w-5 h-5" />
            {tab.label}
          </Link>
        ))}
      </nav>

      <SourceManagerDrawer open={settingsOpen} onClose={() => setSettingsOpen(false)} />
      <HistoryPanel open={historyOpen} onClose={() => setHistoryOpen(false)} />
    </>
  );
}

function HeaderLink({ href, active, children }: { href: string; active: boolean; children: React.ReactNode }) {
  return (
    <Link
      href={href}
      className={cn(
        'px-4 py-1.5 rounded-full text-sm font-medium transition-colors whitespace-nowrap',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/50',
        active ? 'bg-accent text-on-accent' : 'text-muted hover:text-content'
      )}
    >
      {children}
    </Link>
  );
}

function IconButton({ label, onClick, children }: { label: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      className="w-9 h-9 flex items-center justify-center rounded-full bg-chip/80 text-muted hover:text-content hover:bg-hover transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/50"
      title={label}
      aria-label={label}
      onClick={onClick}
    >
      {children}
    </button>
  );
}
