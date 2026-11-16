import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { sourceCache } from './source-cache';
import { installNativeTvBackend, nativeSources } from './native-tv-backend';

const mock = vi.hoisted(() => {
  const sources = [{ key: 'env_0', name: '源一', url: 'https://one.test/api' }, { key: 'env_1', name: '源二', url: 'https://two.test/api' }];
  return {
    sources, state: {
      envSources: sources, customAPIs: [] as { key: string; name: string; url: string; isAdult?: boolean }[], selectedKeys: ['env_0', 'env_1'], yellowFilter: true,
      subscriptions: [], adFilter: true, autoplayNext: true, qualityMap: {}, setEnvSources: vi.fn(), recordSourceHealth: vi.fn(), setQuality: vi.fn(),
      playbackMode: 'direct' as 'direct' | 'desktop', desktopServerUrl: '', desktopPairingToken: '', updateSettings: vi.fn(),
    },
    detail: vi.fn(), search: vi.fn(), clearSearch: vi.fn(), probe: vi.fn(), saved: vi.fn(), historyGet: vi.fn(), saveProgress: vi.fn(), updateHistory: vi.fn(), upsertHistory: vi.fn(),
  };
});
vi.mock('./store', () => ({
  useAppStore: { getState: () => mock.state, persist: { rehydrate: async () => {} } },
  resolveSource: (_store: unknown, key: string, fallback?: { url?: string; name?: string }) => mock.sources.find((s) => s.key === key) || (fallback?.url ? { key, ...fallback } : undefined),
}));
vi.mock('./standalone-api', () => ({ standaloneStatus: () => ({ defaultSources: mock.sources }), standaloneDetail: mock.detail, standaloneSearch: mock.search, standaloneProbeQuality: mock.probe, mapConcurrent: async (items: unknown[], _limit: number, run: (item: unknown) => Promise<void>) => { for (const item of items) await run(item); } }));
vi.mock('./db', () => ({
  db: { history: { get: mock.historyGet, orderBy: () => ({ reverse: () => ({ limit: () => ({ toArray: async () => [] }) }) }) }, searchHistory: { orderBy: () => ({ reverse: () => ({ limit: () => ({ toArray: async () => [] }) }) }) }, progress: { get: mock.saved } },
  listFavorites: async () => [], toggleFavorite: vi.fn(), importFavorites: vi.fn(), onLibraryChange: () => () => {},
  addSearchHistory: vi.fn(), clearSearchHistory: mock.clearSearch, saveProgress: mock.saveProgress, updateHistoryProgress: mock.updateHistory, upsertHistory: mock.upsertHistory,
}));
vi.mock('./library-sync', () => ({ startLibrarySync: () => ({ status: { enabled: false, syncing: false }, now: async () => {}, stop: () => {} }) }));
vi.mock('./persist-storage', () => ({ flushPendingPersist: vi.fn() }));
vi.mock('./subscription-sync', () => ({ syncSourceSubscription: vi.fn() }));

type Message = { event: string; id?: string; value?: Record<string, unknown> };
type BridgeWindow = { LibreTvNativeUi: { postMessage(message: string): void }; LibreTvBackend?: { request(request: { id: string; op: string; args?: object }): void } };
let browser: BridgeWindow;
let messages: Message[];
let release: (() => void) | undefined;
beforeEach(() => {
  vi.clearAllMocks(); sourceCache.clear(); messages = [];
  mock.historyGet.mockReset();
  mock.state.qualityMap = {};
  mock.state.setQuality.mockImplementation((patch: object) => Object.assign(mock.state.qualityMap, patch));
  mock.state.selectedKeys = ['env_0', 'env_1']; mock.state.customAPIs = [];
  mock.state.playbackMode = 'direct'; mock.state.desktopServerUrl = ''; mock.state.desktopPairingToken = '';
  mock.state.updateSettings.mockImplementation((patch: object) => Object.assign(mock.state, patch));
  browser = { LibreTvNativeUi: { postMessage: (message) => messages.push(JSON.parse(message)) } };
  vi.stubGlobal('window', browser);
});
afterEach(() => { release?.(); release = undefined; vi.unstubAllGlobals(); });
async function boot() { release = installNativeTvBackend(); await vi.waitFor(() => expect(messages.some((m) => m.event === 'ready')).toBe(true)); }
const item = { sourceKey: 'env_0', sourceUrl: 'https://one.test/api', sourceName: '源一', vodId: '42', name: '测试剧集' };

describe('原生电视业务协议', () => {
  it('切换电脑串流不改变片源勾选，未配置电脑也可正常搜索', async () => {
    const network = vi.fn().mockRejectedValue(new Error('电脑不在线'));
    vi.stubGlobal('fetch', network);
    await boot();
    browser.LibreTvBackend!.request({ id: 'desktop-mode', op: 'settings', args: { playbackMode: 'desktop' } });
    await vi.waitFor(() => expect(messages.find((m) => m.id === 'desktop-mode')?.value).toMatchObject({ playbackMode: 'desktop', selectedKeys: ['env_0', 'env_1'], enabled: 2 }));
    mock.search.mockResolvedValueOnce({ list: [item] });
    browser.LibreTvBackend!.request({ id: 'desktop-search', op: 'search', args: { query: item.name } });
    await vi.waitFor(() => expect(messages.some((m) => m.id === 'desktop-search' && m.event === 'result')).toBe(true));
    expect(mock.search).toHaveBeenCalledWith(item.name, mock.sources, true, expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(messages.find((m) => m.id === 'desktop-search' && m.event === 'result')?.value).toMatchObject({ total: 2, done: true, groups: [expect.objectContaining({ name: item.name })] });
    expect(network).not.toHaveBeenCalled();
    browser.LibreTvBackend!.request({ id: 'direct-mode', op: 'settings', args: { playbackMode: 'direct' } });
    await vi.waitFor(() => expect(messages.find((m) => m.id === 'direct-mode')?.value).toMatchObject({ playbackMode: 'direct', selectedKeys: ['env_0', 'env_1'], enabled: 2 }));
  });
  it('确认影片时比较各来源实测码率，选码率最高且可用的来源，探测结果复用', async () => {
    const other = { ...item, sourceKey: 'env_1', sourceUrl: 'https://two.test/api', sourceName: '源二', vodId: '7' }, broken = { ...item, vodId: '9' };
    mock.probe.mockImplementation(async ({ source, id }: { source: { key: string }; id: string }) => id === '9' ? { sourceKey: source.key, vodId: id, error: 'HTTP 403' }
      : { sourceKey: source.key, vodId: id, height: 1080, bitrate: source.key === 'env_1' ? 4200 : 1800 });
    await boot();
    browser.LibreTvBackend!.request({ id: 'best', op: 'best-source', args: { items: [item, other, broken] } });
    await vi.waitFor(() => expect(messages.find((m) => m.id === 'best' && m.event === 'result')?.value).toMatchObject({ item: { sourceKey: 'env_1' }, bitrate: 4200, quality: '1080P · 4.2M', compared: 3 }));
    browser.LibreTvBackend!.request({ id: 'again', op: 'best-source', args: { items: [item, other] } });
    await vi.waitFor(() => expect(messages.find((m) => m.id === 'again' && m.event === 'result')?.value).toMatchObject({ item: { sourceKey: 'env_1' } }));
    expect(mock.probe).toHaveBeenCalledTimes(3);
  });
  it('超分设置独立保存，不改变广告过滤和播放模式', async () => {
    await boot();
    browser.LibreTvBackend!.request({ id: 'sr-off', op: 'settings', args: { superResolution: false } });
    await vi.waitFor(() => expect(messages.find(m => m.id === 'sr-off')?.value).toMatchObject({ superResolution: false, adFilter: true, playbackMode: 'direct' }));
    expect(mock.state.updateSettings).toHaveBeenCalledWith({ superResolution: false });
    browser.LibreTvBackend!.request({ id: 'sr-on', op: 'settings', args: { superResolution: true } });
    await vi.waitFor(() => expect(messages.find(m => m.id === 'sr-on')?.value).toMatchObject({ superResolution: true }));
  });
  it('从现有数据库启动，保持原来的源标识和启用设置', async () => {
    await boot();
    expect(messages.find((m) => m.event === 'ready')?.value).toMatchObject({ config: { enabled: 2, selectedKeys: ['env_0', 'env_1'] } });
    mock.state.selectedKeys = ['env_0', 'env_0', 'missing'];
    expect(nativeSources()).toEqual([mock.sources[0]]);
  });
  it('按集恢复进度，临近片尾的旧进度从头播放', async () => {
    await boot();
    mock.detail.mockResolvedValue({ episodes: ['https://cdn.test/1.m3u8', 'https://cdn.test/2.m3u8'], videoInfo: {} });
    mock.saved.mockResolvedValue({ position: 120, duration: 1000 });
    browser.LibreTvBackend!.request({ id: 'open-1', op: 'open-playback', args: { item, index: 1 } });
    await vi.waitFor(() => expect(messages.find((m) => m.id === 'open-1')?.value).toMatchObject({ index: 1, position: 120 }));
    expect(mock.saved).toHaveBeenCalledWith('env_0_42_1');
    mock.saved.mockResolvedValue({ position: 998, duration: 1000 });
    browser.LibreTvBackend!.request({ id: 'open-2', op: 'open-playback', args: { item, index: 0 } });
    await vi.waitFor(() => expect(messages.find((m) => m.id === 'open-2')?.value).toMatchObject({ index: 0, position: 0 }));
  });
  it('历史影片续播优先使用该集进度，进度表缺失时回退到同集观看记录', async () => {
    await boot();
    mock.detail.mockResolvedValue({ episodes: ['https://cdn.test/1.m3u8', 'https://cdn.test/2.m3u8'], videoInfo: {} });
    mock.saved.mockResolvedValue(undefined);
    mock.historyGet.mockResolvedValue({ episodeIndex: 1, playbackPosition: 333, duration: 1000 });
    browser.LibreTvBackend!.request({ id: 'history-resume', op: 'open-playback', args: { item, index: 1 } });
    await vi.waitFor(() => expect(messages.find(m => m.id === 'history-resume')?.value).toMatchObject({ index: 1, position: 333 }));
    expect(mock.historyGet).toHaveBeenCalledWith('env_0_42');
    mock.historyGet.mockClear(); mock.saved.mockResolvedValue({ position: 350, duration: 1000 });
    browser.LibreTvBackend!.request({ id: 'latest-progress', op: 'open-playback', args: { item, index: 1 } });
    await vi.waitFor(() => expect(messages.find(m => m.id === 'latest-progress')?.value).toMatchObject({ position: 350 }));
    expect(mock.historyGet).not.toHaveBeenCalled();
  });
  it('历史记录的集数不同或已经看完时从头播放，不串用其他集的进度', async () => {
    await boot();
    mock.detail.mockResolvedValue({ episodes: ['https://cdn.test/1.m3u8', 'https://cdn.test/2.m3u8'], videoInfo: {} });
    mock.saved.mockResolvedValue(undefined);
    mock.historyGet.mockResolvedValue({ episodeIndex: 1, playbackPosition: 333, duration: 1000 });
    browser.LibreTvBackend!.request({ id: 'other-episode', op: 'open-playback', args: { item, index: 0 } });
    await vi.waitFor(() => expect(messages.find(m => m.id === 'other-episode')?.value).toMatchObject({ index: 0, position: 0 }));
    mock.historyGet.mockResolvedValue({ episodeIndex: 1, playbackPosition: 998, duration: 1000 });
    browser.LibreTvBackend!.request({ id: 'finished-history', op: 'open-playback', args: { item, index: 1 } });
    await vi.waitFor(() => expect(messages.find(m => m.id === 'finished-history')?.value).toMatchObject({ index: 1, position: 0 }));
  });
  it('旧观看记录没有总时长时仍能恢复已知进度', async () => {
    await boot();
    mock.detail.mockResolvedValue({ episodes: ['https://cdn.test/1.m3u8'], videoInfo: {} });
    mock.saved.mockResolvedValue(undefined); mock.historyGet.mockResolvedValue({ episodeIndex: 0, playbackPosition: 90, duration: 0 });
    browser.LibreTvBackend!.request({ id: 'legacy-history', op: 'open-playback', args: { item, index: 0 } });
    await vi.waitFor(() => expect(messages.find(m => m.id === 'legacy-history')?.value).toMatchObject({ position: 90 }));
  });
  it('片源搜索尚未完成时已刷新搜索历史，慢来源不会阻塞首页历史', async () => {
    await boot();
    let finish!: (value: object) => void;
    mock.search.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
    browser.LibreTvBackend!.request({ id: 'history-now', op: 'search', args: { query: '007' } });
    await vi.waitFor(() => expect(messages.find((m) => m.id === 'history-now')?.event).toBe('search-history'));
    expect(messages.some((m) => m.id === 'history-now' && m.event === 'result')).toBe(false);
    finish({ list: [] });
    await vi.waitFor(() => expect(messages.some((m) => m.id === 'history-now' && m.event === 'result')).toBe(true));
  });
  it('换源只恢复新来源自己的进度，不沿用旧来源的时间位置', async () => {
    await boot();
    mock.detail.mockResolvedValue({ episodes: ['https://cdn.test/1.m3u8'], videoInfo: {} });
    mock.saved.mockResolvedValue({ position: 25, duration: 900 });
    const next = { ...item, sourceKey: 'env_1', sourceName: '源二', vodId: '99' };
    browser.LibreTvBackend!.request({ id: 'switch', op: 'open-playback', args: { item: next, index: 0 } });
    await vi.waitFor(() => expect(messages.find((m) => m.id === 'switch')?.value).toMatchObject({ index: 0, position: 25 }));
    expect(mock.saved).toHaveBeenCalledWith('env_1_99_0');
  });
  it('清空搜索记录后刷新首页，不清空播放进度', async () => {
    await boot();
    browser.LibreTvBackend!.request({ id: 'clear', op: 'clear-search-history' });
    await vi.waitFor(() => expect(messages.find((m) => m.id === 'clear')?.value).toMatchObject({ searches: [] }));
    expect(mock.clearSearch).toHaveBeenCalledOnce();
    expect(mock.saveProgress).not.toHaveBeenCalled();
  });
  it('换源先完成全部详情检测，再展示列表并更新画质，不把其他影片混入', async () => {
    await boot();
    mock.search.mockResolvedValue({ list: [item, { ...item, sourceKey: 'env_1', vodId: '99', name: '另一部影片' }] });
    mock.detail.mockResolvedValue({ episodes: ['https://cdn.test/1.m3u8'], videoInfo: {} });
    mock.probe.mockResolvedValue({ height: 1080 });
    browser.LibreTvBackend!.request({ id: 'sources', op: 'switch-sources', args: { title: item.name } });
    await vi.waitFor(() => expect(messages.some((m) => m.id === 'sources' && m.event === 'result')).toBe(true));
    expect(messages.filter((m) => m.id === 'sources').map((m) => m.event)).toEqual(['candidates', 'source-result', 'sources-tested', 'quality-result', 'result']);
    expect(mock.detail).toHaveBeenCalledOnce();
    expect(mock.detail.mock.calls[0][0]).toBe('42');
    expect(messages.find(m=>m.id==='sources' && m.event==='quality-result')?.value).toMatchObject({height:1080,quality:'1080P'});
  });
  it('再次打开换源复用列表和未知画质缓存，手动刷新才重新联网', async () => {
    await boot();
    mock.search.mockResolvedValue({ list: [item], failures: [] });
    mock.detail.mockResolvedValue({ episodes: ['https://cdn.test/1.m3u8'], videoInfo: {} });
    mock.probe.mockResolvedValue({ height: undefined });
    for (const id of ['cold', 'warm']) {
      browser.LibreTvBackend!.request({ id, op: 'switch-sources', args: { title: item.name } });
      await vi.waitFor(() => expect(messages.some(m => m.id === id && m.event === 'result')).toBe(true));
    }
    expect(mock.search).toHaveBeenCalledOnce(); expect(mock.detail).toHaveBeenCalledOnce(); expect(mock.probe).toHaveBeenCalledOnce();
    expect(messages.find(m => m.id === 'warm' && m.event === 'sources-tested')?.value).toMatchObject({ cached: true, refreshing: false });
    browser.LibreTvBackend!.request({ id: 'refresh', op: 'switch-sources', args: { title: item.name, refresh: true } });
    await vi.waitFor(() => expect(messages.some(m => m.id === 'refresh' && m.event === 'result')).toBe(true));
    expect(mock.search).toHaveBeenCalledTimes(2); expect(mock.detail).toHaveBeenCalledTimes(2); expect(mock.probe).toHaveBeenCalledTimes(2);
    expect(mock.search).toHaveBeenLastCalledWith(item.name, mock.sources, true, expect.objectContaining({ forceRefresh: true }));
  });
  it('不可用来源可单独重新检测，成功后返回可用集数', async () => {
    await boot();
    mock.detail.mockRejectedValueOnce(new Error('网络超时'));
    browser.LibreTvBackend!.request({ id: 'failed', op: 'retest-source', args: { item } });
    await vi.waitFor(() => expect(messages.find((m) => m.id === 'failed')?.event).toBe('error'));
    mock.detail.mockResolvedValueOnce({ episodes: ['https://cdn.test/1.m3u8', 'https://cdn.test/2.m3u8'], videoInfo: {} });
    browser.LibreTvBackend!.request({ id: 'retry', op: 'retest-source', args: { item } });
    await vi.waitFor(() => expect(messages.find((m) => m.id === 'retry')?.value).toMatchObject({ item, ok: true, episodes: 2 }));
  });
  it('关闭面板后丢弃迟到详情，释放后不再发消息', async () => {
    await boot();
    let finish!: (value: object) => void;
    mock.detail.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    browser.LibreTvBackend!.request({ id: 'old-detail', op: 'detail', args: { item } });
    browser.LibreTvBackend!.request({ id: 'cancel', op: 'cancel', args: { id: 'old-detail' } });
    finish({ episodes: ['https://cdn.test/1.m3u8'], videoInfo: {} });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(messages.some((m) => m.id === 'old-detail')).toBe(false);
    release!(); release = undefined;
    expect(browser.LibreTvBackend).toBeUndefined();
  });
  it('保存进度保持来源、影片、集数与秒单位，忽略无效时长', async () => {
    await boot();
    browser.LibreTvBackend!.request({ id: 'save', op: 'save-progress', args: { sourceKey: 'env_0', vodId: '42', index: 3, position: 12.5, duration: 100 } });
    await vi.waitFor(() => expect(mock.saveProgress).toHaveBeenCalledWith('env_0', '42', 3, 12.5, 100));
    expect(mock.updateHistory).toHaveBeenCalledWith('env_0', '42', 12.5, 100);
    browser.LibreTvBackend!.request({ id: 'invalid', op: 'save-progress', args: { sourceKey: 'env_1', vodId: '43', index: 0, position: 20, duration: 0 } });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mock.saveProgress).toHaveBeenCalledTimes(1);
  });
  it('增强视频保存进度时换算回原片时间，直连继续观看不偏移', async () => {
    await boot();
    browser.LibreTvBackend!.request({ id: 'save-enhanced', op: 'save-progress', args: { sourceKey: 'env_0', vodId: '42', index: 1, position: 6, duration: 10, timeMap: [{ start: 2, duration: 2 }, { start: 8, duration: 3 }] } });
    await vi.waitFor(() => expect(mock.saveProgress).toHaveBeenCalledWith('env_0', '42', 1, 11, 15));
    expect(mock.updateHistory).toHaveBeenCalledWith('env_0', '42', 11, 15);
  });
});
