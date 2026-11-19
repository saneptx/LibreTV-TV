'use client';

import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '@/lib/client-api';
import { resolveSource, useAppStore } from '@/lib/store';
import type { SearchResultItem, SourceConfig, VideoDetail } from '@/lib/types';
import { aggregateResults, type AggregatedGroup } from '@/components/video-card';
import { SmartImage } from '@/components/smart-image';
import { useSearchHistory } from '@/components/search-history';
import { buildWatchUrl, validateSourceUrl } from '@/lib/utils';
import styles from './tv.module.css';
import { getNativeHttpBridge } from '@/lib/native-http';
import { TvSourceSettings } from '@/components/tv-source-settings';

const FOCUS_SELECTOR = '[data-tv-focus]:not([disabled])';
const RESULT_PAGE_SIZE = 72;

function focusFirst(scope: ParentNode = document) {
  requestAnimationFrame(() => scope.querySelector<HTMLElement>(FOCUS_SELECTOR)?.focus());
}

/** 浏览器本身没有稳定的电视遥控器空间导航，用元素坐标选择当前方向最近的控件。 */
function useDpadNavigation(onBack: () => boolean, getScope: () => ParentNode | null) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' || event.key === 'BrowserBack') {
        if (onBack()) event.preventDefault();
        return;
      }
      if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)) return;

      const scope = getScope() ?? document;
      const active = document.activeElement as HTMLElement | null;
      if (active instanceof HTMLInputElement && ['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
      const nodes = [...scope.querySelectorAll<HTMLElement>(FOCUS_SELECTOR)].filter(
        (node) => node.offsetParent !== null
      );
      if (!active || !nodes.includes(active)) {
        focusFirst();
        event.preventDefault();
        return;
      }

      const from = active.getBoundingClientRect();
      const fx = from.left + from.width / 2;
      const fy = from.top + from.height / 2;
      const direction = event.key;
      const candidates = nodes
        .filter((node) => node !== active)
        .map((node) => {
          const rect = node.getBoundingClientRect();
          const x = rect.left + rect.width / 2;
          const y = rect.top + rect.height / 2;
          const dx = x - fx;
          const dy = y - fy;
          const valid =
            (direction === 'ArrowRight' && dx > 8) ||
            (direction === 'ArrowLeft' && dx < -8) ||
            (direction === 'ArrowDown' && dy > 8) ||
            (direction === 'ArrowUp' && dy < -8);
          const primary = direction === 'ArrowLeft' || direction === 'ArrowRight' ? Math.abs(dx) : Math.abs(dy);
          const secondary = direction === 'ArrowLeft' || direction === 'ArrowRight' ? Math.abs(dy) : Math.abs(dx);
          return { node, valid, score: primary + secondary * 2.4 };
        })
        .filter((entry) => entry.valid)
        .sort((a, b) => a.score - b.score);

      if (candidates[0]) {
        event.preventDefault();
        candidates[0].node.focus({ preventScroll: true });
        candidates[0].node.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onBack, getScope]);
}

export default function TvPage() {
  const router = useRouter();
  const store = useAppStore();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [standalone, setStandalone] = useState(false);
  const closeSettings = useCallback(() => { setSettingsOpen(false); requestAnimationFrame(() => searchInput.current?.focus()); }, []);
  const [query, setQuery] = useState('');
  const [searched, setSearched] = useState('');
  const [items, setItems] = useState<SearchResultItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('输入片名，按遥控器确认键搜索');
  const [selectedGroup, setSelectedGroup] = useState<AggregatedGroup | null>(null);
  const [selectedItem, setSelectedItem] = useState<SearchResultItem | null>(null);
  const [detail, setDetail] = useState<VideoDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  const [visibleCount, setVisibleCount] = useState(RESULT_PAGE_SIZE);
  const searchHistory = useSearchHistory('');
  const searchInput = useRef<HTMLInputElement>(null);
  const searchAbort = useRef<AbortController | null>(null);
  const lastFocus = useRef<HTMLElement | null>(null);

  const sources = useMemo(() => {
    const seen = new Set<string>();
    return store.selectedKeys
      .map((key) => resolveSource(store, key))
      .filter((source): source is SourceConfig => {
        if (!source || !validateSourceUrl(source.url) || seen.has(source.url)) return false;
        seen.add(source.url);
        return true;
      });
  }, [store]);

  const groups = useMemo(() => aggregateResults(items), [items]);
  const visibleGroups = useMemo(() => groups.slice(0, visibleCount), [groups, visibleCount]);

  const openGroup = useCallback((group: AggregatedGroup) => {
    lastFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setSelectedGroup(group);
    setSelectedItem(group.items[0] ?? null);
  }, []);

  const closePanel = useCallback(() => {
    if (!selectedGroup) return false;
    setSelectedGroup(null);
    setSelectedItem(null);
    setDetail(null);
    setDetailError('');
    requestAnimationFrame(() => lastFocus.current?.focus({ preventScroll: true }));
    return true;
  }, [selectedGroup]);
  const dialogScope = useCallback(() => document.querySelector<HTMLElement>('[data-tv-dialog]'), []);
  const onBack = useCallback(() => {
    if (settingsOpen) { closeSettings(); return true; }
    return closePanel();
  }, [settingsOpen, closeSettings, closePanel]);
  useDpadNavigation(onBack, dialogScope);
  useEffect(() => {
    setStandalone(Boolean(getNativeHttpBridge()));
    const open = () => setSettingsOpen(true);
    window.addEventListener('libretv-tv-settings', open);
    return () => window.removeEventListener('libretv-tv-settings', open);
  }, []);

  // 投影仪 WebView 会把确认键用于原生焦点而不触发网页 button 的 click。原生壳
  // 发送该事件后，直接依据当前焦点执行操作，避免依赖不稳定的模拟点击。
  useEffect(() => {
    const activateFocused = () => {
      const element = document.activeElement as HTMLElement | null;
      const groupKey = element?.dataset.tvGroupKey;
      if (groupKey) {
        const group = groups.find((candidate) => candidate.key === groupKey);
        if (group) openGroup(group);
      }
    };
    window.addEventListener('libretv-tv-activate', activateFocused);
    return () => window.removeEventListener('libretv-tv-activate', activateFocused);
  }, [groups, openGroup]);

  useEffect(() => {
    requestAnimationFrame(() => searchInput.current?.focus());
    return () => searchAbort.current?.abort();
  }, []);

  useEffect(() => {
    if (!selectedItem) return;
    const source = resolveSource(store, selectedItem.sourceKey, {
      name: selectedItem.sourceName,
      url: selectedItem.sourceUrl,
    });
    if (!source) {
      setDetailError('找不到对应点播源');
      return;
    }
    const controller = new AbortController();
    setDetail(null);
    setDetailError('');
    setDetailLoading(true);
    api
      .detail(selectedItem.vodId, source, controller.signal)
      .then(setDetail)
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setDetailError(error instanceof Error ? error.message : '详情加载失败');
      })
      .finally(() => setDetailLoading(false));
    return () => controller.abort();
  }, [selectedItem, store]);

  useEffect(() => {
    if (selectedGroup) focusFirst(document.querySelector(`.${styles.dialog}`) ?? document);
  }, [selectedGroup, selectedItem, detail]);

  const search = async (text: string) => {
    const keyword = text.trim().slice(0, 100);
    if (!keyword) {
      setMessage('请输入片名');
      searchInput.current?.focus();
      return;
    }
    if (sources.length === 0) {
      setMessage('没有启用的点播源，请先在桌面版设置中配置');
      return;
    }
    searchAbort.current?.abort();
    const controller = new AbortController();
    searchAbort.current = controller;
    setQuery(keyword);
    searchHistory.record(keyword);
    setLoading(true);
    setSearched(keyword);
    setItems([]);
    setVisibleCount(RESULT_PAGE_SIZE);
    setMessage(`正在搜索 ${sources.length} 个来源…`);
    const streamed = new Map<string, SearchResultItem>();
    try {
      const result = await api.search(keyword, sources, store.yellowFilter, {
        signal: controller.signal,
        onSource: (outcome) => {
          for (const item of outcome.list) streamed.set(`${item.sourceKey}:${item.vodId}`, item);
          setItems([...streamed.values()]);
        },
      });
      setItems(result.list);
      setMessage(
        result.list.length > 0
          ? `找到 ${aggregateResults(result.list).length} 部影片${result.failures.length ? ` · ${result.failures.length} 个源失败` : ''}`
          : '没有找到相关影片'
      );
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return;
      setMessage(error instanceof Error ? error.message : '搜索失败');
    } finally {
      if (!controller.signal.aborted) setLoading(false);
    }
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void search(query);
  };

  const play = (index: number) => {
    if (!selectedItem) return;
    router.push(
      `${buildWatchUrl({
        sourceKey: selectedItem.sourceKey,
        vodId: selectedItem.vodId,
        index,
        title: selectedItem.name,
        sourceUrl: selectedItem.sourceUrl,
      })}&tv=1`
    );
  };

  return (
    <main className={styles.page}>
      {settingsOpen && <TvSourceSettings onClose={closeSettings} />}
      <header className={styles.header}>
        <div>
          <span className={styles.eyebrow}>{standalone ? store.playbackMode === 'desktop' ? '电脑串流 · ANDROID TV' : '独立运行 · ANDROID TV' : 'TV MODE'}</span>
          <h1>SuperTV</h1>
        </div>
        <div className={styles.sourceCount}>{sources.length} 个来源已启用
          {standalone && <button data-tv-focus type="button" className={styles.close} style={{ marginLeft: '2vw' }} onClick={() => setSettingsOpen(true)}>片源管理</button>}
        </div>
      </header>

      <form className={styles.search} onSubmit={submit}>
        <input
          ref={searchInput}
          data-tv-focus
          data-tv-search-input
          autoFocus
          aria-label="搜索影片"
          placeholder="搜索电影、剧集或动漫"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <button data-tv-focus type="submit" disabled={loading}>
          {loading ? '搜索中…' : '搜索'}
        </button>
      </form>

      {searchHistory.matches.length > 0 && (
        <section className={styles.history} aria-label="搜索历史">
          <div className={styles.historyHeader}>
            <h2>最近搜索</h2>
            <button
              data-tv-focus
              type="button"
              className={styles.clearHistory}
              onClick={() => {
                void searchHistory.clearAll().then(() => searchInput.current?.focus({ preventScroll: true }));
              }}
            >
              清空历史
            </button>
          </div>
          <div className={styles.historyItems}>
            {searchHistory.matches.map((entry) => (
              <button
                data-tv-focus
                data-tv-history={entry.text}
                type="button"
                className={styles.historyItem}
                key={entry.text}
                title={entry.text}
                onClick={() => { void search(entry.text); }}
              >
                {entry.text}
              </button>
            ))}
          </div>
        </section>
      )}

      <section className={styles.status} aria-live="polite">
        <strong>{searched || '电视模式'}</strong>
        <span>{message}</span>
      </section>

      {groups.length > 0 ? (
        <>
          <section className={styles.grid} aria-label="搜索结果">
            {visibleGroups.map((group) => (
              <button
                data-tv-focus
                data-tv-group-key={group.key}
                data-tv-watch-url={`${buildWatchUrl({
                  sourceKey: group.items[0].sourceKey,
                  vodId: group.items[0].vodId,
                  index: 0,
                  title: group.items[0].name,
                  sourceUrl: group.items[0].sourceUrl,
                })}&tv=1`}
                type="button"
                className={styles.card}
                key={group.key}
                onClick={() => openGroup(group)}
              >
                <div className={styles.poster}>
                  {group.pic ? (
                    <SmartImage
                      url={group.pic}
                      mode={store.imageProxyMode}
                      customProxy={store.customImageProxy}
                      alt=""
                      className={styles.posterImage}
                    />
                  ) : (
                    <span className={styles.posterFallback}>▶</span>
                  )}
                  {group.items.length > 1 && <span className={styles.badge}>{group.items.length} 源</span>}
                </div>
                <span className={styles.cardTitle}>{group.name}</span>
                <span className={styles.cardMeta}>{[group.year, group.remarks].filter(Boolean).join(' · ') || '点击查看'}</span>
              </button>
            ))}
          </section>
          {visibleGroups.length < groups.length && (
            <button
              data-tv-focus
              type="button"
              className={styles.loadMore}
              onClick={() => setVisibleCount((count) => count + RESULT_PAGE_SIZE)}
            >
              显示更多（还有 {groups.length - visibleGroups.length} 部）
            </button>
          )}
        </>
      ) : (
        <section className={styles.empty}>
          <div className={styles.remote}>＋</div>
          <p>用方向键移动焦点，按确认键选择</p>
        </section>
      )}

      {selectedGroup && selectedItem && (
        <div className={styles.overlay} role="presentation" onClick={(event) => event.target === event.currentTarget && closePanel()}>
          <section data-tv-dialog className={styles.dialog} role="dialog" aria-modal="true" aria-label={`${selectedGroup.name} 详情`}>
            <header className={styles.dialogHeader}>
              <div>
                <span className={styles.eyebrow}>SELECT EPISODE</span>
                <h2>{selectedGroup.name}</h2>
              </div>
              <button data-tv-focus type="button" className={styles.close} onClick={closePanel}>关闭</button>
            </header>

            {selectedGroup.items.length > 1 && (
              <div className={styles.sources} aria-label="选择来源">
                {selectedGroup.items.map((item) => (
                  <button
                    data-tv-focus
                    type="button"
                    key={`${item.sourceKey}:${item.vodId}`}
                    className={item === selectedItem ? styles.sourceActive : ''}
                    onClick={() => setSelectedItem(item)}
                  >
                    {item.sourceName}
                  </button>
                ))}
              </div>
            )}

            <div className={styles.detailBody}>
              {detailLoading && <p className={styles.notice}>正在加载剧集…</p>}
              {detailError && <p className={styles.error}>{detailError}</p>}
              {detail && (
                <>
                  <p className={styles.description}>{detail.videoInfo.desc || detail.videoInfo.remarks || '选择一集开始播放'}</p>
                  <div className={styles.episodes}>
                    {detail.episodes.map((_, index) => (
                      <button data-tv-focus type="button" key={index} onClick={() => play(index)}>
                        {index + 1}
                      </button>
                    ))}
                  </div>
                </>
              )}
            </div>
          </section>
        </div>
      )}
    </main>
  );
}
