import { afterEach, describe, expect, it, vi } from 'vitest';
import { desktopAddress, resolvePlaybackDelivery, closeDesktopSession, testDesktopConnection, sourcePosition, sourceDuration } from './desktop-playback';
const settings = { playbackMode: 'desktop' as const, desktopServerUrl: 'http://10.0.2.2:8090', desktopPairingToken: 'test-pairing-code-at-least-24-characters' };
const id='a'.repeat(32), path=`/v1/sessions/${id}/media/${'b'.repeat(32)}.m3u8`;
afterEach(() => vi.unstubAllGlobals());
describe('电脑串流客户端', () => {
  it('独立模式无需电脑地址、配对码或网络请求', async () => {
    const fetch=vi.fn(); vi.stubGlobal('fetch',fetch);
    expect(await resolvePlaybackDelivery('https://cdn.test/a.m3u8',{playbackMode:'direct',desktopServerUrl:'',desktopPairingToken:''})).toEqual({url:'https://cdn.test/a.m3u8',headers:{},delivery:'direct'});
    expect(fetch).not.toHaveBeenCalled();
  });
  it('创建/清理任务，配对码只放请求头和设备本地会话，不放媒体 URL',async () => {
    const fetch=vi.fn().mockResolvedValueOnce(Response.json({protocol:1,id,path,processing:'relay'})).mockResolvedValueOnce(Response.json({closed:true})); vi.stubGlobal('fetch',fetch);
    const result=await resolvePlaybackDelivery('https://cdn.test/a.m3u8',settings);
    expect(result.url).toBe(settings.desktopServerUrl+path); expect(result.url).not.toContain(settings.desktopPairingToken);
    expect(result.headers.Authorization).toBe(`Bearer ${settings.desktopPairingToken}`);
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({url:'https://cdn.test/a.m3u8',superResolution:true});
    await closeDesktopSession(result.desktopSession); expect(fetch.mock.calls[1][1].method).toBe('DELETE');
  });
  it('迟到的创建响应在取消后被清理，不启动旧影片',async () => {
    let finish!: (value: Response) => void;
    const fetch=vi.fn().mockImplementationOnce(()=>new Promise((resolve)=>{finish=resolve;})).mockResolvedValueOnce(Response.json({closed:true})); vi.stubGlobal('fetch',fetch);
    const controller=new AbortController(), result=resolvePlaybackDelivery('https://cdn.test/a.m3u8',settings,controller.signal);
    const checked=expect(result).rejects.toMatchObject({name:'AbortError'}); controller.abort(); finish(Response.json({protocol:1,id,path,processing:'relay'})); await checked;
    await vi.waitFor(()=>expect(fetch).toHaveBeenCalledTimes(2));
  });
  it('拒绝外部媒体地址，避免把配对凭证发给第三方',async () => {
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(Response.json({protocol:1,id,path:'https://evil.test/a.m3u8',processing:'relay'})));
    await expect(resolvePlaybackDelivery('https://cdn.test/a.m3u8',settings)).rejects.toThrow('无效播放任务');
    expect(()=>desktopAddress('http://user:pass@pc.test')).toThrow(); expect(()=>desktopAddress('http://pc.test/other')).toThrow();
  });
  it('断线不暗中改为直连，连接测试如实标注尚无超分',async () => {
    vi.stubGlobal('fetch',vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(resolvePlaybackDelivery('https://cdn.test/a.m3u8',settings)).rejects.toThrow('切回独立直连');
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(Response.json({protocol:1,service:'LibreTV Desktop',processing:'relay'})));
    expect(await testDesktopConnection(settings)).toContain('尚未启用');
  });
});

describe('电脑增强处理协议', () => {
  it('历史和直连恢复使用原片进度，正确跨越多个已删除广告区间', () => {
    const removed = [{ start: 2, duration: 2 }, { start: 8, duration: 3 }];
    expect(sourcePosition(1, removed)).toBe(1);
    expect(sourcePosition(2, removed)).toBe(4);
    expect(sourcePosition(6, removed)).toBe(11);
    expect(sourcePosition(9, removed)).toBe(14);
    expect(sourceDuration(10, removed)).toBe(15);
    expect(sourceDuration(0, removed)).toBe(0);
  });
  it('等待首段处理完成，保留实际能力、进度和广告时间轴', async () => {
    const path=`/v1/sessions/${id}/processed/index.m3u8`, onProgress=vi.fn();
    const fetch=vi.fn().mockResolvedValueOnce(Response.json({protocol:1,id,path,processing:'enhance'})).mockResolvedValueOnce(Response.json({state:'ready',position:4,label:'电脑 AI 超分 · 1080P',input:{height:720},processingInfo:{sourceWidth:1280,sourceHeight:720,outputWidth:1920,outputHeight:1080,superResolution:true,model:'fsrcnnx',adRemovalEnabled:true,adRemovalSupported:true,removedSegments:1,removedSeconds:2},timeMap:[{start:2,duration:2}]}));vi.stubGlobal('fetch',fetch);
    const result=await resolvePlaybackDelivery('https://cdn.test/a.m3u8',settings,undefined,{position:6,adFilter:true,onProgress});
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({url:'https://cdn.test/a.m3u8',superResolution:true,position:6,adFilter:true});expect(result).toMatchObject({processing:'enhance',position:4,sourceHeight:720,timeMap:[{start:2,duration:2}]});expect(onProgress).toHaveBeenCalled();expect(result.presentation?.video).toContain('原始 1280×720 → 当前 1920×1080 · 已超分');expect(result.presentation?.ads).toContain('已去广告：1 个分片 / 2 秒');
  });
  it('关闭超分显式发送开关，去广告独立保留，拒绝旧服务忽略开关', async () => {
    const status = { state: 'ready', input: { height: 720 }, processingInfo: { sourceWidth: 1280, sourceHeight: 720, outputWidth: 1280, outputHeight: 720,
      superResolution: false, superResolutionEnabled: false, model: null, adRemovalEnabled: true, adRemovalSupported: true, removedSegments: 1, removedSeconds: 2 } };
    const create = { protocol: 1, id, path: `/v1/sessions/${id}/processed/index.m3u8`, processing: 'enhance' };
    const fetch = vi.fn().mockImplementation(async (_url, opts) => Response.json(opts.method === 'POST' ? create : opts.method === 'DELETE' ? { closed: true } : status));
    vi.stubGlobal('fetch', fetch);
    const result = await resolvePlaybackDelivery('https://cdn.test/a.m3u8', { ...settings, superResolution: false }, undefined, { adFilter: true });
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toMatchObject({ superResolution: false, adFilter: true });
    expect(result.presentation?.video).toContain('超分已关闭（原始分辨率）'); expect(result.presentation?.ads).toContain('已去广告');
    delete (status.processingInfo as { superResolutionEnabled?: boolean }).superResolutionEnabled;
    await expect(resolvePlaybackDelivery('https://cdn.test/a.m3u8', { ...settings, superResolution: false })).rejects.toThrow('重启电脑服务');
  });
  it('处理失败立即报错并清理会话，不悄悄播放未处理视频',async()=>{
    const fetch=vi.fn().mockResolvedValueOnce(Response.json({protocol:1,id,path:`/v1/sessions/${id}/processed/index.m3u8`,processing:'enhance'})).mockResolvedValueOnce(Response.json({state:'error',error:'GPU 不可用'})).mockResolvedValueOnce(Response.json({closed:true}));vi.stubGlobal('fetch',fetch);
    await expect(resolvePlaybackDelivery('https://cdn.test/a.m3u8',settings)).rejects.toThrow('GPU 不可用');await vi.waitFor(()=>expect(fetch.mock.calls.at(-1)?.[1].method).toBe('DELETE'));
  });
});
