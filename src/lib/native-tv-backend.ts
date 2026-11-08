import { discoverSources, updateSourceCandidate } from './source-discovery';
import { sourceCache } from './source-cache';
import { standaloneDetail, standaloneSearch, standaloneStatus, standaloneProbeQuality, mapConcurrent } from './standalone-api';
import { useAppStore, resolveSource } from './store';
import { addSearchHistory, clearSearchHistory, db, saveProgress, updateHistoryProgress, upsertHistory } from './db';
import { aggregateResults } from './result-groups';
import { qualityKey, qualityLabel, isQualityEntryFresh } from './quality';
import { flushPendingPersist } from './persist-storage';
import { syncSourceSubscription } from './subscription-sync';
import { closeDesktopSession, resolvePlaybackDelivery, testDesktopConnection, sourcePosition, sourceDuration, type RemovedInterval, type DesktopSession } from './desktop-playback';
import { analyzePlaylist, clearLearnedAds, decideAds, learnAdAt, loadLearnedAds, saveLearnedAd, type AdTransport, type MediaAnalysis } from './ad-analysis';
import { decodeNativeBytes, encodeNativeBytes, nativeFetch, nativeRpc } from './native-http';
import type { SearchResultItem, SourceConfig } from './types';

interface BackendRequest { id: string; op: string; args?: Record<string, unknown> }
interface BackendWindow extends Window {
  LibreTvNativeUi?: { postMessage(message: string): void };
  LibreTvBackend?: { request(request: BackendRequest): void };
}
const castWindow = () => window as BackendWindow;

const adTransport: AdTransport = {
  fetchText: async (url, signal) => {
    const res = await nativeFetch(url, { signal, timeoutMs: 5000 });
    return res.ok ? { text: await res.text(), url: res.url || url } : undefined;
  },
  fetchPrefix: async (url, maxBytes, signal) => {
    const res = await nativeFetch(url, { signal, timeoutMs: 4000, maxBytes, prefix: true });
    return res.ok ? new Uint8Array(await res.arrayBuffer()) : undefined;
  },
  decrypt: async (body, key, iv) => decodeNativeBytes((await nativeRpc('decrypt', { body: encodeNativeBytes(body), key: encodeNativeBytes(key), iv: encodeNativeBytes(iv) })).body),
};
/** 当前及最近播放的段组指纹；标记广告和重新打开时不必再联网建指纹 */
const adAnalyses = new Map<string, { media: MediaAnalysis[]; at: number }>();
async function analyzeAds(url: string, signal: AbortSignal): Promise<MediaAnalysis[]> {
  const cached = adAnalyses.get(url);
  // 列表里的签名地址和插入的广告都会变，缓存只覆盖本次观看（含标记后重开）
  if (cached && Date.now() - cached.at < 10 * 60_000) return cached.media;
  const media = await analyzePlaylist(url, adTransport, signal);
  if (signal.aborted) return media;
  adAnalyses.delete(url);
  if (adAnalyses.size >= 20) adAnalyses.delete(adAnalyses.keys().next().value!);
  adAnalyses.set(url, { media, at: Date.now() });
  return media;
}
/** 交给 Media3 的分析原文；总量受原生消息上限约束，超出时让播放器自行下载 */
function analyzedPlaylists(media: MediaAnalysis[]): { url: string; text: string }[] {
  const list = media.map(({ url, text }) => ({ url, text }));
  return list.reduce((n, p) => n + p.text.length, 0) <= 1536 * 1024 ? list : [];
}
/** 电脑会话请求体有上限；超出时只少删，不影响播放 */
function boundedLines(lines: string[]): string[] {
  const result: string[] = [];
  let bytes = 0;
  for (const line of lines) {
    if (result.length >= 400 || line.length > 2048 || (bytes += line.length + 4) > 48 * 1024) break;
    result.push(line);
  }
  return result;
}

export function nativeSources(): SourceConfig[] {
  const store = useAppStore.getState(), seen = new Set<string>();
  return store.selectedKeys.map((key) => resolveSource(store, key)).filter((source): source is SourceConfig => {
    if (!source || seen.has(source.url) || store.yellowFilter && source.isAdult === true) return false;
    seen.add(source.url); return true;
  });
}
function sourceFor(item: SearchResultItem): SourceConfig {
  const source = resolveSource(useAppStore.getState(), item.sourceKey, { name: item.sourceName, url: item.sourceUrl });
  if (!source) throw new Error('找不到对应片源，请在片源管理中检查配置');
  return source;
}
function config() {
  const store = useAppStore.getState();
  return {
    sources: [...store.envSources, ...store.customAPIs].filter((s) => !store.yellowFilter || !s.isAdult),
    selectedKeys: store.selectedKeys, enabled: nativeSources().length, subscriptions: store.subscriptions,
    adFilter: store.adFilter, learnedAds: loadLearnedAds().length, superResolution: store.superResolution !== false, autoplayNext: store.autoplayNext,
    playbackMode: store.playbackMode || 'direct', desktopServerUrl: store.desktopServerUrl || '', desktopPairingToken: store.desktopPairingToken || '',
  };
}
async function home() {
  const [history, searches] = await Promise.all([
    db.history.orderBy('timestamp').reverse().limit(100).toArray(),
    db.searchHistory.orderBy('timestamp').reverse().limit(10).toArray(),
  ]);
  return { config: config(), history, searches };
}

/** 原生 UI 与既有业务层的版本化接口。取消和 request id 隔离搜索/面板迟到的结果。 */
export function installNativeTvBackend(): () => void {
  if (!castWindow().LibreTvNativeUi) return () => {};
  const controllers = new Map<string, AbortController>();
  let disposed = false;
  const emit = (event: Record<string, unknown>) => { if (!disposed) castWindow().LibreTvNativeUi?.postMessage(JSON.stringify({ version: 1, ...event })); };
  const request = async (request: BackendRequest) => {
    if (disposed || !request.id || !request.op) return;
    const { id, op, args = {} } = request;
    if (op === 'cancel') { controllers.get(String(args.id))?.abort(); return; }
    const controller = new AbortController(); controllers.set(id, controller);
    const signal = controller.signal;
    const progress = (event: string, value: unknown) => { if (!signal.aborted) emit({ id, event, value }); };
    try {
      let value: unknown;
      switch (op) {
        case 'home': value = await home(); break;
        case 'clear-search-history': await clearSearchHistory(); value=await home(); break;
        case 'config': value = config(); break;
        case 'search': {
          const wd = String(args.query || '').trim();
          await addSearchHistory(wd);
          progress('search-history', await home());
          const sources = nativeSources();
          const outcomes: SearchResultItem[] = [];
          let completed = 0, failures = 0, lastEmit = 0;
          const result = await standaloneSearch(wd, sources, useAppStore.getState().yellowFilter, {
            signal, onSource: (outcome) => {
              completed++; if (!outcome.ok) failures++;
              outcomes.push(...outcome.list);
              if (Date.now() - lastEmit >= 250 || completed === sources.length) {
                lastEmit = Date.now();
                progress('search-progress', { groups: aggregateResults(outcomes).slice(0, 300), completed, total: sources.length, failures });
              }
              useAppStore.getState().recordSourceHealth([outcome]);
            },
          });
          sourceCache.flush(); flushPendingPersist();
          value = { groups: aggregateResults(result.list).slice(0, 300), completed, total: sources.length, failures, done: true };
          break;
        }
        case 'detail': {
          const item = args.item as unknown as SearchResultItem;
          value = { item, detail: await standaloneDetail(item.vodId, sourceFor(item), signal) };
          break;
        }
        case 'open-playback': {
          const item = args.item as unknown as SearchResultItem;
          const source = sourceFor(item);
          const detail = await standaloneDetail(item.vodId, source, signal);
          const index = Math.min(Math.max(0, Number(args.index) || 0), detail.episodes.length - 1);
          const saved = await db.progress.get(`${source.key}_${item.vodId}_${index}`);
          const history = saved ? undefined : await db.history.get(`${source.key}_${item.vodId}`);
          const resume = saved || (history?.episodeIndex === index ? { position: history.playbackPosition, duration: history.duration } : undefined);
          const position = typeof args.position === 'number' ? Math.max(0, args.position)
            : resume && (resume.duration <= 0 || resume.position < resume.duration - 15) ? Math.max(0, resume.position) : 0;
          if (signal.aborted) break;
          const adFilter = useAppStore.getState().adFilter;
          const analyzed = adFilter ? await analyzeAds(detail.episodes[index], signal) : [];
          const ads = adFilter ? decideAds(analyzed, loadLearnedAds()) : undefined;
          if (signal.aborted) break;
          const resolved = await resolvePlaybackDelivery(detail.episodes[index], useAppStore.getState(), signal, { position, adFilter,
            adSegments: ads ? boundedLines([...ads.extraLines, ...ads.heuristicLines]) : undefined, onProgress: (value) => progress('processing-progress', value) });
          const playback = ads ? { ...resolved, adSegments: boundedLines(ads.extraLines), playlists: resolved.delivery === 'direct' ? analyzedPlaylists(analyzed) : [], adSummary: { codecGroups: ads.codecGroups, learnedGroups: ads.learnedGroups, repeatGroups: ads.repeatGroups } } : resolved;
          const playbackPosition = playback.position ?? position;
          if (signal.aborted) { void closeDesktopSession(playback.desktopSession); break; }
          try {
            await upsertHistory({ sourceKey: source.key, sourceUrl: source.url, vodId: item.vodId, title: item.name,
              pic: item.pic || detail.videoInfo.cover, episodeIndex: index, totalEpisodes: detail.episodes.length,
              playbackPosition: position, duration: resume?.duration || 0, timestamp: Date.now() });
            value = { item, source, detail, index, position: playbackPosition, playback, config: config() };
          } catch (error) { void closeDesktopSession(playback.desktopSession); throw error; }
          if (signal.aborted) void closeDesktopSession(playback.desktopSession);
          break;
        }
        case 'save-progress': {
          const sourceKey = String(args.sourceKey), vodId = String(args.vodId), index = Number(args.index);
          const position = Number(args.position), duration = Number(args.duration);
          if (![index, position, duration].every(Number.isFinite) || index < 0 || position < 0 || duration <= 0) break;
          const removals = Array.isArray(args.timeMap) ? args.timeMap as RemovedInterval[] : [];
          const originalPosition = sourcePosition(position, removals), originalDuration = sourceDuration(duration, removals);
          await Promise.all([saveProgress(sourceKey, vodId, index, originalPosition, originalDuration), updateHistoryProgress(sourceKey, vodId, originalPosition, originalDuration)]);
          break;
        }
        case 'played-quality': {
          const height = Number(args.height);
          if (Number.isFinite(height) && height > 0) useAppStore.getState().setQuality({
            [qualityKey(String(args.sourceKey), String(args.vodId))]: { height, from: 'play', timestamp: Date.now() },
          });
          break;
        }
        case 'switch-sources': {
          const title = String(args.title).trim(), sources = nativeSources();
          const forceRefresh = args.refresh === true;
          const filterAdult = useAppStore.getState().yellowFilter;
          const rows = await discoverSources({ title, sources, filterAdult, signal, forceRefresh,
            search: (forceRefresh) => standaloneSearch(title, sources, filterAdult, { signal, forceRefresh }),
            inspect: async (item, source, forceRefresh) => {
              const started = performance.now();
              const detail = await standaloneDetail(item.vodId, source, signal, { forceRefresh });
              return { ok: true, ms: Math.round(performance.now() - started), episodes: detail.episodes.length };
            },
            onSnapshot: ({ rows, cached, refreshing }) => {
              progress('candidates', rows.map(({ result: item }) => ({ item })));
              for (const row of rows) {
                const entry = useAppStore.getState().qualityMap[qualityKey(row.source.key, row.result.vodId)];
                const quality = entry && isQualityEntryFresh(entry) ? entry : undefined;
                progress('source-result', { item: row.result, ok: row.ok, ms: row.ms, episodes: row.episodes, error: row.error,
                  height: quality?.height, quality: qualityLabel(quality?.height) });
              }
              progress('sources-tested', { count: rows.length, cached, refreshing });
            },
            onWarning: message => progress('cache-warning', { message }),
          });
          const matches = rows.filter(row => row.ok).map(row => ({ source: row.source, item: row.result }));
          await mapConcurrent(matches, 3, async ({ source, item }) => {
            const cached = useAppStore.getState().qualityMap[qualityKey(source.key, item.vodId)];
            if (!forceRefresh && cached && isQualityEntryFresh(cached)) return;
            const quality = await standaloneProbeQuality({ source, id: item.vodId }, signal);
            useAppStore.getState().setQuality({ [qualityKey(source.key, item.vodId)]: { height: quality.height, error: quality.error, from: 'probe', timestamp: Date.now() } });
            const height=useAppStore.getState().qualityMap[qualityKey(source.key,item.vodId)]?.height ?? quality.height;
            progress('quality-result', { item, height, quality: qualityLabel(height) || '未知', error: quality.error });
          }, signal);
          sourceCache.flush(); flushPendingPersist();
          value = { count: rows.length };
          break;
        }
        case 'retest-source': {
          const item = args.item as unknown as SearchResultItem, started = performance.now();
          const source = sourceFor(item);
          const detail = await standaloneDetail(item.vodId, source, signal, { forceRefresh: true });
          updateSourceCandidate(item.name, nativeSources(), useAppStore.getState().yellowFilter,
            { result: item, source, ok: true, ms: Math.round(performance.now() - started), episodes: detail.episodes.length });
          const entry=useAppStore.getState().qualityMap[qualityKey(item.sourceKey,item.vodId)];
          const cached=entry && isQualityEntryFresh(entry) ? entry : undefined;
          value = { item, ok: true, ms: Math.round(performance.now() - started), episodes: detail.episodes.length,
            height: cached?.height, quality: qualityLabel(cached?.height) };
          break;
        }
        case 'mark-ad': {
          const url = String(args.url || ''), position = Number(args.position);
          if (!/^https?:\/\//i.test(url) || !Number.isFinite(position) || position < 0) throw new Error('无效的播放位置');
          const { group, entry } = await learnAdAt(await analyzeAds(url, signal), position, adTransport, signal);
          const learned = saveLearnedAd({ ...entry, title: String(args.title || '').slice(0, 80) || undefined });
          value = { start: group.start, end: group.start + group.duration, learnedAds: learned.length, config: config() };
          break;
        }
        case 'clear-learned-ads': clearLearnedAds(); value = config(); break;
        case 'toggle-source': useAppStore.getState().toggleSourceSelected(String(args.key)); flushPendingPersist(); value = config(); break;
        case 'add-source': {
          const url = new URL(String(args.url).trim());
          if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error('请输入有效的 HTTP/HTTPS 地址');
          useAppStore.getState().addCustomApi({ name: String(args.name || '').trim() || url.hostname, url: url.href.replace(/\/+$/, '') });
          flushPendingPersist(); value = config(); break;
        }
        case 'sync-subscription': value = await syncSourceSubscription(String(args.url)); flushPendingPersist(); break;
        case 'test-desktop': value = await testDesktopConnection({ desktopServerUrl: String(args.desktopServerUrl || ''), desktopPairingToken: String(args.desktopPairingToken || '') }, signal); break;
        case 'close-desktop': void closeDesktopSession(args.session as unknown as DesktopSession); break;
        case 'settings': {
          const patch: Partial<import('./store').AppSettings> = {};
          if (args.playbackMode === 'direct' || args.playbackMode === 'desktop') patch.playbackMode = args.playbackMode;
          if (typeof args.desktopServerUrl === 'string') patch.desktopServerUrl = args.desktopServerUrl.trim();
          if (typeof args.desktopPairingToken === 'string') patch.desktopPairingToken = args.desktopPairingToken.trim();
          if (typeof args.superResolution === 'boolean') patch.superResolution = args.superResolution;
          if (typeof args.adFilter === 'boolean') patch.adFilter = args.adFilter;
          if (typeof args.autoplayNext === 'boolean') patch.autoplayNext = args.autoplayNext;
          useAppStore.getState().updateSettings(patch); flushPendingPersist(); value = config(); break;
        }
        default: throw new Error('未知的原生界面请求');
      }
      if (!signal.aborted) emit({ id, event: 'result', value: value ?? null });
    } catch (error) {
      if (!signal.aborted) emit({ id, event: 'error', error: error instanceof Error ? error.message : '操作失败' });
    } finally { controllers.delete(id); }
  };
  castWindow().LibreTvBackend = { request: (r) => { void request(r); } };
  void (async () => {
    try {
      await useAppStore.persist.rehydrate();
      if (disposed) return;
      useAppStore.getState().setEnvSources(standaloneStatus().defaultSources);
      emit({ event: 'ready', value: await home() });
    } catch (error) { emit({ event: 'fatal', error: error instanceof Error ? error.message : '本地数据初始化失败' }); }
  })();
  return () => { disposed = true; controllers.forEach((c) => c.abort()); controllers.clear(); delete castWindow().LibreTvBackend; sourceCache.flush(); flushPendingPersist(); };
}
