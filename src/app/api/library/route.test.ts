import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { GET as list } from './route';
import { DELETE as remove, POST as play } from './[id]/route';
import { GET as stream } from './stream/[session]/[file]/route';
import { SESSION_COOKIE, signSession } from '@/lib/auth';

/** 电脑片库转发接口：登录守卫、配对码只由服务端附加、播放地址只能访问处理后的列表和分段 */

const TOKEN = 'a'.repeat(48), ID = 'b'.repeat(32), SESSION = 'c'.repeat(32);
const fetchMock = vi.fn();
const request = (path: string, init: RequestInit & { authenticated?: boolean } = {}) => new Request('https://local.test' + path, {
  ...init, headers: { ...(init.authenticated === false ? {} : { cookie: `${SESSION_COOKIE}=${signSession().token}` }), ...init.headers },
});
const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const auth = (call = 0) => ((fetchMock.mock.calls[call] as [string, RequestInit])[1].headers as Record<string, string>).Authorization;

beforeAll(() => { process.env.PASSWORD = 'test-password'; });
beforeEach(() => {
  fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock);
  process.env.DESKTOP_PAIRING_TOKEN = TOKEN; process.env.DESKTOP_SERVER_URL = 'http://127.0.0.1:18090';
});
afterEach(() => { vi.unstubAllGlobals(); delete process.env.DESKTOP_PAIRING_TOKEN; delete process.env.DESKTOP_SERVER_URL; });

describe('/api/library', () => {
  it('未登录返回 401，不访问串流服务', async () => {
    expect((await list(request('/api/library', { authenticated: false }))).status).toBe(401);
    expect((await play(request(`/api/library/${ID}`, { method: 'POST', authenticated: false }), { params: Promise.resolve({ id: ID }) })).status).toBe(401);
    expect((await stream(request(`/api/library/stream/${SESSION}/0.ts`, { authenticated: false }), { params: Promise.resolve({ session: SESSION, file: '0.ts' }) })).status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('列表原样返回，配对码由服务端附加', async () => {
    fetchMock.mockResolvedValue(reply({ items: [{ id: ID, title: '繁花', state: 'done' }] }));
    const res = await list(request('/api/library'));
    expect(await res.json()).toEqual({ items: [{ id: ID, title: '繁花', state: 'done' }] });
    expect(fetchMock.mock.calls[0][0]).toBe('http://127.0.0.1:18090/v1/downloads');
    expect(auth()).toBe(`Bearer ${TOKEN}`);
  });

  it('串流服务离线或没有处理环境时给出说明，不返回 401', async () => {
    fetchMock.mockRejectedValue(new TypeError('fetch failed'));
    const offline = await list(request('/api/library'));
    expect(offline.status).toBe(502);
    expect((await offline.json()).error).toContain('没有运行');
    fetchMock.mockResolvedValue(reply({ error: '电脑服务配对码不匹配' }, 401));
    expect((await list(request('/api/library'))).status).toBe(502);
    fetchMock.mockResolvedValue(reply({ error: '电脑下载需要电脑增强处理环境' }, 501));
    expect((await list(request('/api/library'))).status).toBe(501);
  });

  it('播放按下载条目建立任务，返回经网页服务转发的播放地址', async () => {
    fetchMock.mockResolvedValue(reply({ id: SESSION, path: `/v1/sessions/${SESSION}/processed/index.m3u8` }, 201));
    const res = await play(request(`/api/library/${ID}`, { method: 'POST' }), { params: Promise.resolve({ id: ID }) });
    expect(await res.json()).toEqual({ session: SESSION, playlist: `/api/library/stream/${SESSION}/index.m3u8` });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://127.0.0.1:18090/v1/sessions');
    expect(JSON.parse(init.body as string)).toEqual({ download: ID });
  });

  it('无效的编号不转发', async () => {
    expect((await play(request('/api/library/x', { method: 'POST' }), { params: Promise.resolve({ id: '../status' }) })).status).toBe(400);
    expect((await remove(request('/api/library/x', { method: 'DELETE' }), { params: Promise.resolve({ id: 'x' }) })).status).toBe(400);
    for (const file of ['../status', 'index.m3u8/x', 'a.ts', '1.mp4'])
      expect((await stream(request('/x'), { params: Promise.resolve({ session: SESSION, file }) })).status).toBe(400);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('删除转发到串流服务', async () => {
    fetchMock.mockResolvedValue(reply({ removed: true }));
    expect((await remove(request(`/api/library/${ID}`, { method: 'DELETE' }), { params: Promise.resolve({ id: ID }) })).status).toBe(200);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(`http://127.0.0.1:18090/v1/downloads/${ID}`);
    expect(init.method).toBe('DELETE');
  });

  it('播放列表里的分段地址改为相对路径；分段带 Range 转发', async () => {
    fetchMock.mockResolvedValue(new Response(`#EXTM3U\n#EXTINF:8.000000,\n/v1/sessions/${SESSION}/processed/0.ts\n#EXT-X-ENDLIST\n`));
    const playlist = await stream(request(`/api/library/stream/${SESSION}/index.m3u8`), { params: Promise.resolve({ session: SESSION, file: 'index.m3u8' }) });
    expect(await playlist.text()).toBe('#EXTM3U\n#EXTINF:8.000000,\n0.ts\n#EXT-X-ENDLIST\n');
    fetchMock.mockResolvedValue(new Response('abcd', { status: 206, headers: { 'Content-Range': 'bytes 0-3/10', 'Content-Length': '4' } }));
    const segment = await stream(request(`/api/library/stream/${SESSION}/0.ts`, { headers: { Range: 'bytes=0-3' } }), { params: Promise.resolve({ session: SESSION, file: '0.ts' }) });
    expect(segment.status).toBe(206);
    expect(segment.headers.get('content-range')).toBe('bytes 0-3/10');
    expect(await segment.text()).toBe('abcd');
    const [url, init] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(url).toBe(`http://127.0.0.1:18090/v1/sessions/${SESSION}/processed/0.ts`);
    expect((init.headers as Record<string, string>).Range).toBe('bytes=0-3');
    expect(auth(1)).toBe(`Bearer ${TOKEN}`);
  });
});
