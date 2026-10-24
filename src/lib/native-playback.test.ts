import { describe, expect, it } from 'vitest';
import { buildNativePlaybackRequest, NativePlaybackMedia } from './native-playback';

describe('原生播放协议', () => {
  it('为外网片源构造可选代理地址，拒绝本地文件协议', () => {
    const r = buildNativePlaybackRequest('https://cdn.example/video.m3u8?x=1', 'http://tv.local:8080/watch', 12, true);
    expect(r.url).toBe('https://cdn.example/video.m3u8?x=1');
    expect(r.proxyUrl).toBe('http://tv.local:8080/api/proxy?url=https%3A%2F%2Fcdn.example%2Fvideo.m3u8%3Fx%3D1');
    expect(r.position).toBe(12);
    expect(() => buildNativePlaybackRequest('file:///secret', 'http://tv.local', 0, true)).toThrow();
    expect(buildNativePlaybackRequest('/api/proxy?url=a', 'http://tv.local', NaN, false)).toMatchObject({ position: 0, proxyUrl: undefined });
  });
  it('换集后的旧状态不能覆盖新进度，结束事件只触发一次', () => {
    const media = new NativePlaybackMedia('new', { postMessage() {} });
    const state = { ...media.state, position: 20, duration: 120, ready: true, buffering: false };
    expect(media.update({ ...state, sessionId: 'old' })).toBe(false);
    expect(media.currentTime).toBe(0);
    let ended = 0; media.addEventListener('ended', () => ended++);
    media.update(state); media.update({ ...state, ended: true }); media.update({ ...state, ended: true });
    expect(media.currentTime).toBe(20); expect(ended).toBe(1);
  });
  it('恢复、暂停、缓冲事件及快进命令保持统一的秒单位', async () => {
    const sent: { type: string; position?: number; sessionId: string }[] = [];
    const media = new NativePlaybackMedia('one', { postMessage: (message) => sent.push(JSON.parse(message)) });
    const events: string[] = [];
    ['playing', 'pause', 'waiting'].forEach((type) => media.addEventListener(type, () => events.push(type)));
    media.update({ ...media.state, duration: 100, ready: true, buffering: false, bufferedPosition: 50, bufferedDuration: 20 });
    media.update({ ...media.state, paused: true });
    media.update({ ...media.state, paused: false, ready: false, buffering: true });
    expect(events).toEqual(['playing', 'pause', 'waiting']);
    expect(media.buffered.start(0)).toBe(30); expect(media.buffered.end(0)).toBe(50);
    media.currentTime = 12.5; media.playbackRate = 1.25; await media.play(); media.pause();
    expect(sent.map((m) => m.type)).toEqual(['seek', 'rate', 'play', 'pause']);
    expect(sent[0]).toMatchObject({ position: 12.5, sessionId: 'one' });
  });
});
