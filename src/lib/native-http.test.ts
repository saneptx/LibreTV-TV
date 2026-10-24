import { afterEach, describe, expect, it, vi } from 'vitest';
import { nativeFetch, nativeRpc } from './native-http';
import { buildNativePlaybackRequest } from './native-playback';

function bridge() {
  const sent: { id: string; type: string }[] = [];
  const fake = { postMessage: vi.fn((text: string) => sent.push(JSON.parse(text))), onmessage: undefined as ((event: { data: string }) => void) | undefined };
  vi.stubGlobal('window', { LibreTvNativeHttp: fake });
  return { fake, sent, reply: (id: string, value: object) => fake.onmessage?.({ data: JSON.stringify({ id, ...value }) }) };
}
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('Android HTTP 桥', () => {
  it('匹配并发响应并保留二进制、HTTP 状态及重定向后的地址', async () => {
    const b = bridge();
    const first = nativeFetch('https://source.test/one');
    const second = nativeFetch('https://source.test/two');
    b.reply(b.sent[1].id, { body: btoa('two'), status: 403, url: 'https://cdn.test/two' });
    b.reply('unknown', { body: btoa('bad'), status: 200 });
    b.reply(b.sent[0].id, { body: btoa('\x00\xff'), status: 200 });
    expect(new Uint8Array(await (await first).arrayBuffer())).toEqual(new Uint8Array([0, 255]));
    const denied = await second;
    expect(denied.status).toBe(403); expect(denied.url).toBe('https://cdn.test/two'); expect(await denied.text()).toBe('two');
  });
  it('取消与超时会通知设备停止请求，迟到响应不会重新结算', async () => {
    vi.useFakeTimers();
    const b = bridge();
    const controller = new AbortController();
    const cancelled = nativeRpc('fetch', {}, controller.signal);
    const checkCancel = expect(cancelled).rejects.toMatchObject({ name: 'AbortError' });
    controller.abort(); await checkCancel;
    expect(b.sent[1]).toEqual({ id: b.sent[0].id, type: 'cancel' });
    b.reply(b.sent[0].id, { body: btoa('late'), status: 200 });
    const timed = nativeRpc('fetch', { timeoutMs: 1000 });
    const checkTimeout = expect(timed).rejects.toThrow('超时');
    await vi.advanceTimersByTimeAsync(2000); await checkTimeout;
    expect(b.sent.at(-1)?.type).toBe('cancel');
  });
  it('电脑服务 POST/DELETE 和配对头经原生网络传递', async () => {
    const b = bridge();
    const created = nativeFetch('http://10.0.2.2:8090/v1/sessions', { method: 'POST', headers: { Authorization: 'Bearer paired' }, body: '{"url":"https://cdn.test/a.m3u8"}' });
    expect(b.sent[0]).toMatchObject({ type: 'fetch', method: 'POST', headers: { Authorization: 'Bearer paired' }, body: '{"url":"https://cdn.test/a.m3u8"}' });
    b.reply(b.sent[0].id, { body: btoa('{}'), status: 201 }); await created;
    const closed = nativeFetch('http://10.0.2.2:8090/v1/sessions/one', { method: 'DELETE', headers: { Authorization: 'Bearer paired' } });
    expect(b.sent[1]).toMatchObject({ method: 'DELETE' }); b.reply(b.sent[1].id, { body: btoa('{}'), status: 200 }); await closed;
  });
  it('独立模式播放请求不再包含电脑代理地址', () => {
    bridge();
    expect(buildNativePlaybackRequest('https://cdn.test/film.m3u8', 'http://127.0.0.1:8080/watch', 23, true))
      .toEqual({ url: 'https://cdn.test/film.m3u8', headers: {}, position: 23, adFilter: true, proxyUrl: undefined });
  });
});
