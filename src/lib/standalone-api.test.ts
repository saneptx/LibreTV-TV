import { afterEach, describe, expect, it, vi } from 'vitest';
import { sourceCache } from './source-cache';
import { nativeFetch } from './native-http';
import { mapConcurrent, standaloneDetail, standaloneSearch, standaloneSourceList, standaloneStatus } from './standalone-api';
vi.mock('./native-http', async (original) => ({ ...await original<typeof import('./native-http')>(), nativeFetch: vi.fn() }));
const fetchNative = vi.mocked(nativeFetch);
const source = { key: 'env_0', name: '测试源', url: 'https://source.test/api.php?token=keep' };
afterEach(() => { vi.resetAllMocks(); sourceCache.clear(); vi.useRealTimers(); });
const movie = (id: number, name = '测试电影') => ({ vod_id: id, vod_name: name, vod_play_url: '正片$https://cdn.test/a.m3u8' });

describe('设备独立 API', () => {
  it('启动状态不需密码，内置源 key 与服务器版本一致', () => {
    const status = standaloneStatus();
    expect(status).toMatchObject({ verified: true, passwordRequired: false });
    expect(status.defaultSources.length).toBeGreaterThan(0);
    expect(status.defaultSources[0].key).toBe('env_0');
  });
  it('搜索保留接口原有参数，分页去重、相关性过滤并隔离坏源', async () => {
    fetchNative.mockImplementation(async (raw) => {
      const url = new URL(raw);
      if (url.hostname === 'bad.test') throw new Error('HTTP 503');
      expect(url.searchParams.get('token')).toBe('keep');
      return Response.json({ pagecount: 2, list: url.searchParams.get('pg') === '1' ? [movie(1), movie(2, '无关内容')] : [movie(1), movie(3)] });
    });
    const onSource = vi.fn();
    const result = await standaloneSearch('测试电影', [source, { ...source, key: 'bad', url: 'https://bad.test/api' }], true, { onSource });
    expect(result.list.map((m) => m.vodId)).toEqual(['1', '3']);
    expect(result.failures).toEqual([expect.objectContaining({ sourceKey: 'bad', error: 'HTTP 503' })]);
    expect(onSource).toHaveBeenCalledTimes(2);
  });
  it('重复搜索不联网且每源只回调一次，主动刷新和配置变化重新请求', async () => {
    fetchNative.mockImplementation(async () => Response.json({ list: [movie(1)] }));
    await standaloneSearch('测试电影', [source], true);
    const onSource = vi.fn();
    expect((await standaloneSearch('测试电影', [source], true, { onSource })).list).toHaveLength(1);
    expect(fetchNative).toHaveBeenCalledTimes(1); expect(onSource).toHaveBeenCalledTimes(1);
    await standaloneSearch('测试电影', [source], true, { forceRefresh: true });
    await standaloneSearch('测试电影', [{ ...source, url: 'https://changed.test/api' }], true);
    await standaloneSearch('测试电影', [source], false);
    expect(fetchNative).toHaveBeenCalledTimes(4);
  });
  it('失败短期复用，30秒后重试；已取消的调用不读取或污染缓存', async () => {
    vi.useFakeTimers();
    fetchNative.mockRejectedValue(new Error('离线'));
    await standaloneSearch('测试电影', [source], true);
    await standaloneSearch('测试电影', [source], true);
    expect(fetchNative).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(30_001);
    fetchNative.mockImplementation(async () => Response.json({ list: [movie(1)] }));
    expect((await standaloneSearch('测试电影', [source], true)).list).toHaveLength(1);
    const controller = new AbortController(); controller.abort();
    await expect(standaloneSearch('测试电影', [source], true, { signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' });
    expect(fetchNative).toHaveBeenCalledTimes(2);
  });
  it('取消后不启动后续源，也不发送成功回调', async () => {
    const controller = new AbortController();
    fetchNative.mockImplementation(async () => { controller.abort(); throw new DOMException('已取消', 'AbortError'); });
    const onSource = vi.fn();
    await expect(standaloneSearch('测试电影', Array.from({ length: 20 }, (_, i) => ({ ...source, key: String(i) })), true, { signal: controller.signal, onSource }))
      .rejects.toMatchObject({ name: 'AbortError' });
    expect(fetchNative.mock.calls.length).toBeLessThanOrEqual(6); expect(onSource).not.toHaveBeenCalled();
  });
  it('详情接口缺少播放地址时从详情页提取，缓存不串源', async () => {
    fetchNative.mockResolvedValueOnce(Response.json({ list: [movie(1)] }));
    const detail = await standaloneDetail('standalone-unit-1', source);
    expect(detail.episodes).toEqual(['https://cdn.test/a.m3u8']);
    expect(await standaloneDetail('standalone-unit-1', source)).toEqual(detail);
    expect(fetchNative).toHaveBeenCalledTimes(1);
    fetchNative.mockResolvedValueOnce(Response.json({ list: [] })).mockResolvedValueOnce(new Response('<a href="x">第一集$https://cdn.test/fallback.m3u8</a>'));
    const other = await standaloneDetail('standalone-unit-1', { ...source, url: 'https://other.test/api' });
    expect(other.episodes).toContain('https://cdn.test/fallback.m3u8');
  });
  it('订阅在设备端识别 TVBOX 格式，跳过 Spider', async () => {
    fetchNative.mockResolvedValue(new Response(JSON.stringify({ sites: [{ key: 'one', name: '直连', type: 1, api: 'https://example.test/api.php/provide/vod' }, { key: 'spider', type: 3, api: 'csp_Test' }] })));
    const result = await standaloneSourceList('https://example.test/config.json');
    expect(result.sources).toHaveLength(1); expect(result.sources[0].name).toBe('直连');
  });
  it('设备网络并发受限', async () => {
    let running = 0, max = 0;
    await mapConcurrent([1, 2, 3, 4, 5], 2, async (n) => { running++; max = Math.max(running, max); await Promise.resolve(); running--; return n; });
    expect(max).toBe(2);
  });
});
