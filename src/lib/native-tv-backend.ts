import { discoverSources, updateSourceCandidate } from './source-discovery';
import { sourceCache } from './source-cache';
import { standaloneDetail, standaloneSearch, standaloneStatus, standaloneProbeQuality, mapConcurrent } from './standalone-api';
import { useAppStore, resolveSource } from './store';
import { addSearchHistory, clearSearchHistory, db, saveProgress, updateHistoryProgress, upsertHistory } from './db';
import { aggregateResults } from './result-groups';
import { qualityKey, qualityLabel, isQualityEntryFresh } from './quality';
import { flushPendingPersist } from './persist-storage';
import { syncSourceSubscription } from './subscription-sync';
import { addDesktopDownload, closeDesktopSession, compareOnDesktop, listDesktopDownloads, removeDesktopDownload, resolvePlaybackDelivery, sanitizeDecoders, testDesktopConnection, sourcePosition, sourceDuration, type RemovedInterval, type DesktopSession } from './desktop-playback';
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
  fetchPrefix: async (url, maxBytes, signal, range) => {
    const res = await nativeFetch(url, { signal, timeoutMs: 4000, maxBytes, prefix: true, headers: range ? { Range: `bytes=0-${maxBytes - 1}` } : undefined });
    // 不支持 Range 的服务器会返回整个文件（200），前缀读取照样截断
    return res.ok ? new Uint8Array(await res.arrayBuffer()).subarray(0, maxBytes) : undefined;
  },
  decrypt: async (body, key, iv) => decodeNativeBytes((await nativeRpc('decrypt', { body: encodeNativeBytes(body), key: encodeNativeBytes(key), iv: encodeNativeBytes(iv) })).body),
};
/** 当前及最近播放的段组指纹；标记广告和重新打开时不必再联网建指纹 */
const adAnalyses = new Map<string, { media: MediaAnalysis[]; at: number }>();
async function analyzeAds(url: string, signal: AbortSignal): Promise<MediaAnalysis[]> {
  // 电脑串流时插播广告的时间轴检测交给电脑（网络快得多），设备只做编码比对和学习库
  const timeline = useAppStore.getState().playbackMode !== 'desktop', key = `${timeline ? 'timeline' : 'codec'}|${url}`;
  const cached = adAnalyses.get(key);
  // 列表里的签名地址和插入的广告都会变，缓存只覆盖本次观看（含标记后重开）
  if (cached && Date.now() - cached.at < 10 * 60_000) return cached.media;
  const media = await analyzePlaylist(url, adTransport, signal, 5000, { timeline });
  if (signal.aborted) return media;
  adAnalyses.delete(key);
  if (adAnalyses.size >= 20) adAnalyses.delete(adAnalyses.keys().next().value!);
  adAnalyses.set(key, { media, at: Date.now() });
  return media;
}
/** 交给 Media3 的分析原文；总量受原生消息上限约束，超出时让播放器自行下载 */
function analyzedPlaylists(media: MediaAnalysis[]): { url: string; text: string }[] {
  const list = media.map(({ url, text }) => ({ url, text }));
  return list.reduce((n, p) => n + p.text.length, 0) <= 1536 * 1024 ? list : [];
}
/** 实测码率（kbps）单独缓存：画质条目的合并规则会让播放实测覆盖探测结果，不能把码率放进去 */
const bitrateKey = (sourceKey: string, vodId: string) => `bitrate|${qualityKey(sourceKey, vodId)}`;
/** 0 表示探测过但测不出码率，同样是有效结论，避免反复探测 */
function cachedBitrate(sourceKey: string, vodId: string): number | undefined {
  const value = sourceCache.get<number>(bitrateKey(sourceKey, vodId))?.value;
  return Number.isFinite(value) && value! > 0 ? value : undefined;
}
const bitrateProbed = (sourceKey: string, vodId: string) => sourceCache.get<number>(bitrateKey(sourceKey, vodId)) !== undefined;
function qualityText(height?: number, bitrate?: number): string {
  const label = qualityLabel(height);
  return [label, bitrate ? `${(bitrate / 1000).toFixed(1)}M` : ''].filter(Boolean).join(' · ');
}
/** 同一来源的探测只跑一次：选片预取与确认选源共用，结果写缓存 */
const probing = new Map<string, Promise<{ height?: number; bitrate?: number; error?: string; cached: boolean }>>();
function probeShared(item: SearchResultItem) {
  const key = qualityKey(item.sourceKey, item.vodId);
  let task = probing.get(key);
  if (!task) {
    // 独立的取消信号：确认时只等待有限时间，未完成的探测继续在后台跑完并缓存，供下次使用
    task = probeSource(item, new AbortController().signal).finally(() => { probing.delete(key); sourceCache.flush(); flushPendingPersist(); });
    probing.set(key, task);
  }
  return task;
}
/** 探测一个来源的画质与码率并写入缓存；有效缓存直接返回 */
async function probeSource(item: SearchResultItem, signal: AbortSignal, forceRefresh = false) {
  const source = sourceFor(item), key = qualityKey(source.key, item.vodId);
  const cached = useAppStore.getState().qualityMap[key];
  const bitrate = cachedBitrate(source.key, item.vodId);
  if (!forceRefresh && cached && isQualityEntryFresh(cached) && (bitrateProbed(source.key, item.vodId) || cached.error)) return { height: cached.height, bitrate, error: cached.error, cached: true };
  const quality = await standaloneProbeQuality({ source, id: item.vodId }, signal);
  useAppStore.getState().setQuality({ [key]: { height: quality.height, error: quality.error, from: 'probe', timestamp: Date.now() } });
  if (!quality.error) sourceCache.set(bitrateKey(source.key, item.vodId), quality.bitrate || 0, 6 * 3600_000, 24 * 3600_000);
  return { height: useAppStore.getState().qualityMap[key]?.height ?? quality.height, bitrate: quality.bitrate, error: quality.error, cached: false };
}

const COMPARE_KEY = 'libretv-compare-v1';
type Comparison = { intervals: RemovedInterval[]; references: number; at: number };
function comparisons(): Record<string, Comparison> {
  try { const value = JSON.parse(localStorage.getItem(COMPARE_KEY) || '{}'); return value && typeof value === 'object' ? value : {}; } catch { return {}; }
}
function savedComparison(url: string): Comparison | undefined {
  const entry = comparisons()[url];
  return entry && Date.now() - entry.at < 7 * 24 * 3600_000 && Array.isArray(entry.intervals) ? entry : undefined;
}
function saveComparison(url: string, entry: Comparison) {
  const all = Object.entries({ ...comparisons(), [url]: entry }).sort((a, b) => b[1].at - a[1].at).slice(0, 300);
  try { localStorage.setItem(COMPARE_KEY, JSON.stringify(Object.fromEntries(all))); } catch { /* 存储满时本次结果仍生效 */ }
}
/** 同一集的其他来源：集数一致，优先选不同 CDN 主机（同一上游的备用域名往往插着同样的广告）。
 * 多给几个候选：部分 CDN 会拒绝电脑访问（403/404），电脑依次尝试，凑够两个可用参考即停。 */
async function referenceEpisodes(item: SearchResultItem, episodes: string[], index: number, signal: AbortSignal): Promise<string[]> {
  const primary = episodes[index], host = (() => { try { return new URL(primary).host; } catch { return ''; } })();
  const found = await standaloneSearch(item.name, nativeSources(), useAppStore.getState().yellowFilter, { signal });
  const candidates = found.list.filter((r) => r.name.trim() === item.name.trim() && r.sourceKey !== item.sourceKey).slice(0, 16);
  const urls: { url: string; sameHost: boolean }[] = [];
  for (const candidate of candidates) {
    if (urls.filter((u) => !u.sameHost).length >= 4) break;
    try {
      const detail = await standaloneDetail(candidate.vodId, sourceFor(candidate), signal);
      const url = detail.episodes.length === episodes.length ? detail.episodes[index] : undefined;
      if (url && url !== primary && /\.m3u8([?#]|$)/i.test(url) && !urls.some((u) => u.url === url)) urls.push({ url, sameHost: (() => { try { return new URL(url).host === host; } catch { return true; } })() });
    } catch (error) { if (signal.aborted) throw error; }
  }
  return urls.sort((a, b) => Number(a.sameHost) - Number(b.sameHost)).slice(0, 4).map((u) => u.url);
}
async function compareEpisode(item: SearchResultItem, index: number, signal: AbortSignal, onProgress?: (message: string) => void): Promise<Comparison & { cached?: boolean }> {
  const store = useAppStore.getState(), source = sourceFor(item);
  const detail = await standaloneDetail(item.vodId, source, signal);
  const url = detail.episodes[index];
  if (!url) throw new Error('找不到这一集');
  const saved = savedComparison(url);
  if (saved) return { ...saved, cached: true };
  onProgress?.('正在寻找同一集的其他来源');
  const references = await referenceEpisodes(item, detail.episodes, index, signal);
  if (!references.length) throw new Error('没有找到同一集的其他来源');
  const analyzed = await analyzeAds(url, signal), ads = decideAds(analyzed, loadLearnedAds());
  const result = await compareOnDesktop({ desktopServerUrl: store.desktopServerUrl || '', desktopPairingToken: store.desktopPairingToken || '' },
    { primary: { url, playlists: analyzedPlaylists(analyzed) }, references, adFilter: store.adFilter, adSegments: boundedLines([...ads.extraLines, ...ads.heuristicLines]) }, signal, onProgress);
  const entry = { ...result, at: Date.now() };
  saveComparison(url, entry);
  return entry;
}
/** 看当前集时在后台比对下一集；同一时间只跑一个 */
let prefetching: AbortController | undefined;
function prefetchComparison(item: SearchResultItem, index: number) {
  if (prefetching) return;
  const controller = new AbortController(); prefetching = controller;
  void compareEpisode(item, index, controller.signal).catch(() => undefined).finally(() => { if (prefetching === controller) prefetching = undefined; });
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
    adFilter: store.adFilter, learnedAds: loadLearnedAds().length, desktopConfigured: !!(store.desktopServerUrl && store.desktopPairingToken), superResolution: store.superResolution !== false, overlayRemoval: store.overlayRemoval !== false, autoplayNext: store.autoplayNext,
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
          // 电脑片库里的影片在直连模式下也走电脑，直接读取已处理好的整集
          const settings = args.fromLibrary === true ? { ...useAppStore.getState(), playbackMode: 'desktop' as const } : useAppStore.getState();
          const resolved = await resolvePlaybackDelivery(detail.episodes[index], settings, signal, { position, adFilter,
            adSegments: ads ? boundedLines([...ads.extraLines, ...ads.heuristicLines]) : undefined, playlists: analyzedPlaylists(analyzed), decoders: sanitizeDecoders(args.decoders), onProgress: (value) => progress('processing-progress', value) });
          const playback = ads ? { ...resolved, adSegments: boundedLines(ads.extraLines), playlists: resolved.delivery === 'direct' ? analyzedPlaylists(analyzed) : [], adSummary: { codecGroups: ads.codecGroups, learnedGroups: ads.learnedGroups, repeatGroups: ads.repeatGroups, timelineGroups: ads.timelineGroups } } : resolved;
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
                const bitrate = cachedBitrate(row.source.key, row.result.vodId);
                progress('source-result', { item: row.result, ok: row.ok, ms: row.ms, episodes: row.episodes, error: row.error,
                  height: quality?.height, bitrate, quality: qualityText(quality?.height, bitrate) || undefined });
              }
              progress('sources-tested', { count: rows.length, cached, refreshing });
            },
            onWarning: message => progress('cache-warning', { message }),
          });
          const matches = rows.filter(row => row.ok).map(row => ({ source: row.source, item: row.result }));
          await mapConcurrent(matches, 3, async ({ item }) => {
            const quality = await probeSource(item, signal, forceRefresh);
            if (quality.cached) return;
            progress('quality-result', { item, height: quality.height, bitrate: quality.bitrate, quality: qualityText(quality.height, quality.bitrate) || '未知', error: quality.error });
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
        case 'desktop-downloads': {
          const store = useAppStore.getState();
          if (!store.desktopServerUrl || !store.desktopPairingToken) { value = { available: false, items: [] }; break; }
          value = { available: true, items: await listDesktopDownloads({ desktopServerUrl: store.desktopServerUrl, desktopPairingToken: store.desktopPairingToken }, signal) };
          break;
        }
        case 'download-desktop': {
          // 电脑后台按当前处理设置把整集处理好保存到电脑片库
          const store = useAppStore.getState();
          if (!store.desktopServerUrl || !store.desktopPairingToken) throw new Error('请先在片源管理中填写电脑地址和配对码');
          const item = args.item as unknown as SearchResultItem, source = sourceFor(item);
          const detail = await standaloneDetail(item.vodId, source, signal);
          const index = Math.min(Math.max(0, Number(args.index) || 0), detail.episodes.length - 1);
          const episode = detail.episodes.length > 1 ? `第 ${index + 1} 集` : '电影';
          value = await addDesktopDownload({ desktopServerUrl: store.desktopServerUrl, desktopPairingToken: store.desktopPairingToken }, {
            url: detail.episodes[index], superResolution: store.superResolution !== false, adFilter: store.adFilter, overlayRemoval: store.overlayRemoval !== false,
            decoders: sanitizeDecoders(args.decoders), title: item.name, subtitle: `${episode} · ${item.sourceName || source.name}`,
            meta: { sourceKey: item.sourceKey, sourceName: item.sourceName || source.name, sourceUrl: item.sourceUrl, vodId: item.vodId, index, name: item.name, pic: item.pic || detail.videoInfo.cover },
          }, signal);
          break;
        }
        case 'delete-download': {
          const store = useAppStore.getState();
          await removeDesktopDownload({ desktopServerUrl: store.desktopServerUrl || '', desktopPairingToken: store.desktopPairingToken || '' }, String(args.id));
          value = { removed: true };
          break;
        }
        case 'prefetch-bitrates': {
          // 遥控器停在影片卡片上时预先探测各来源码率，按确认键时多半已有结果
          for (const item of (Array.isArray(args.items) ? args.items as unknown as SearchResultItem[] : []).slice(0, 12)) void probeShared(item).catch(() => undefined);
          break;
        }
        case 'best-source': {
          // 遥控器确认影片卡片：比较各来源的实测码率，选码率最高、可用的来源；最多等 6 秒，未测完的按未知处理
          const items = (Array.isArray(args.items) ? args.items as unknown as SearchResultItem[] : []).slice(0, 12);
          if (!items.length) throw new Error('没有可播放的来源');
          const scores: { item: SearchResultItem; height?: number; bitrate?: number; error?: string }[] = [];
          let timer: ReturnType<typeof setTimeout> | undefined;
          await Promise.race([
            Promise.all(items.map((item) => probeShared(item).then((r) => { scores.push({ item, ...r }); }, () => undefined))),
            new Promise<void>((resolve) => { timer = setTimeout(resolve, 6000); signal.addEventListener('abort', () => resolve(), { once: true }); }),
          ]);
          clearTimeout(timer);
          if (signal.aborted) break;
          const usable = scores.filter((s) => !s.error && (s.bitrate || s.height));
          usable.sort((a, b) => (b.bitrate || 0) - (a.bitrate || 0) || (b.height || 0) - (a.height || 0));
          const best = usable[0];
          value = { item: best?.item || items[0], bitrate: best?.bitrate, height: best?.height, quality: qualityText(best?.height, best?.bitrate), compared: scores.length, total: items.length };
          break;
        }
        case 'compare-ads': {
          const store = useAppStore.getState();
          if (!store.adFilter || !store.desktopServerUrl || !store.desktopPairingToken) { value = { available: false }; break; }
          const item = args.item as unknown as SearchResultItem, index = Math.max(0, Number(args.index) || 0);
          const result = await compareEpisode(item, index, signal, (message) => progress('compare-progress', { message }));
          if (signal.aborted) break;
          const count = (await standaloneDetail(item.vodId, sourceFor(item), signal)).episodes.length;
          if (index + 1 < count) prefetchComparison(item, index + 1);
          value = { available: true, ...result };
          break;
        }
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
          if (typeof args.overlayRemoval === 'boolean') patch.overlayRemoval = args.overlayRemoval;
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
