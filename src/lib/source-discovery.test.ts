import { afterEach, describe, expect, it, vi } from 'vitest';
import { sourceCache } from './source-cache';
import { discoverSources } from './source-discovery';
const source = { key: 'one', name: '源', url: 'https://one.test/api' };
const item = { sourceKey: 'one', sourceName: '源', sourceUrl: source.url, vodId: '1', name: '影片', pic: '' };
const setup = () => ({ title: '影片', sources: [source], filterAdult: true,
  search: vi.fn().mockResolvedValue({ list: [item], failures: [] }),
  inspect: vi.fn().mockResolvedValue({ ok: true, ms: 300, episodes: 2 }), onSnapshot: vi.fn(), onWarning: vi.fn() });
afterEach(() => { sourceCache.clear(); vi.useRealTimers(); });
describe('换源面板缓存', () => {
  it('近期结果直接回放，不等待搜索或详情，源配置变化自动失效', async () => {
    const opts = setup(); await discoverSources(opts); await discoverSources(opts);
    expect(opts.search).toHaveBeenCalledOnce(); expect(opts.inspect).toHaveBeenCalledOnce();
    expect(opts.onSnapshot).toHaveBeenLastCalledWith(expect.objectContaining({ cached: true, refreshing: false, rows: [expect.objectContaining({ episodes: 2 })] }));
    await discoverSources({ ...opts, sources: [{ ...source, url: 'https://new.test/api' }] });
    expect(opts.search).toHaveBeenCalledTimes(2);
  });
  it('过期先展示缓存，后台失败仍保留可用列表；手动刷新绕过所有缓存', async () => {
    vi.useFakeTimers(); const opts = setup(); await discoverSources(opts);
    vi.advanceTimersByTime(5 * 60_000 + 1); opts.onSnapshot.mockClear();
    let reject!: (error: Error) => void;
    opts.search.mockImplementationOnce(() => new Promise((_resolve, fail) => { reject = fail; }));
    const pending = discoverSources(opts);
    expect(opts.onSnapshot).toHaveBeenCalledWith(expect.objectContaining({ cached: true, refreshing: true }));
    reject(new Error('离线')); expect(await pending).toHaveLength(1); expect(opts.onWarning).toHaveBeenCalledWith('离线');
    await discoverSources({ ...opts, forceRefresh: true });
    expect(opts.search).toHaveBeenLastCalledWith(true); expect(opts.inspect).toHaveBeenLastCalledWith(item, source, true);
  });
  it('关闭面板时迟到结果不写入缓存', async () => {
    const opts = setup(), controller = new AbortController();
    opts.inspect.mockImplementationOnce(async () => { controller.abort(); return { ok: true }; });
    await expect(discoverSources({ ...opts, signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' });
    opts.onSnapshot.mockClear(); await discoverSources(opts);
    expect(opts.search).toHaveBeenCalledTimes(2);
    expect(opts.onSnapshot).toHaveBeenCalledOnce();
  });
});
