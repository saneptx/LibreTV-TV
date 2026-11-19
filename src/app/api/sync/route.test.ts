import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { POST } from './route';
import { SESSION_COOKIE, signSession } from '@/lib/auth';

/**
 * 同步转发接口单测：登录守卫、未配置串流服务、配对码只由服务端附加、
 * 串流服务离线 / 拒绝时不让浏览器误判为需要登录网页。
 */

const TOKEN = 'a'.repeat(48);
const fetchMock = vi.fn();

function makeRequest(body: unknown, options?: { authenticated?: boolean; raw?: string }): Request {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (options?.authenticated !== false) headers.cookie = `${SESSION_COOKIE}=${signSession().token}`;
  return new Request('https://local.test/api/sync', { method: 'POST', headers, body: options?.raw ?? JSON.stringify(body) });
}
const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

beforeAll(() => {
  process.env.PASSWORD = 'test-password';
});

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  process.env.DESKTOP_PAIRING_TOKEN = TOKEN;
  process.env.DESKTOP_SERVER_URL = 'http://127.0.0.1:18090';
});

afterEach(() => {
  vi.unstubAllGlobals();
  delete process.env.DESKTOP_PAIRING_TOKEN;
  delete process.env.DESKTOP_SERVER_URL;
});

describe('POST /api/sync', () => {
  it('未登录返回 401，不访问串流服务', async () => {
    const res = await POST(makeRequest({ since: 0, changes: [] }, { authenticated: false }));
    expect(res.status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('把请求原样转发给串流服务，配对码由服务端附加', async () => {
    fetchMock.mockResolvedValue(reply({ epoch: 'e1', reset: true, rev: 3, more: false, changes: [] }));
    const body = { since: 0, changes: [{ table: 'favorites', key: '繁花|2023', at: 100, value: { key: '繁花|2023' } }] };
    const res = await POST(makeRequest(body));
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ epoch: 'e1', rev: 3 });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://127.0.0.1:18090/v1/sync');
    expect((init.headers as Record<string, string>).Authorization).toBe(`Bearer ${TOKEN}`);
    expect(JSON.parse(init.body as string)).toEqual(body);
  });

  it('配对码无效（过短）视为未配置，返回 503', async () => {
    process.env.DESKTOP_PAIRING_TOKEN = 'short';
    const res = await POST(makeRequest({ since: 0, changes: [] }));
    expect(res.status).toBe(503);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('请求体不是 JSON 返回 400', async () => {
    const res = await POST(makeRequest(null, { raw: '{oops' }));
    expect(res.status).toBe(400);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('串流服务没有运行返回 502', async () => {
    fetchMock.mockRejectedValue(new TypeError('fetch failed'));
    const res = await POST(makeRequest({ since: 0, changes: [] }));
    expect(res.status).toBe(502);
    expect((await res.json()).error).toContain('没有运行');
  });

  it('串流服务拒绝配对码时返回 502 而不是 401，避免浏览器弹出登录框', async () => {
    fetchMock.mockResolvedValue(reply({ error: '配对码无效' }, 401));
    const res = await POST(makeRequest({ since: 0, changes: [] }));
    expect(res.status).toBe(502);
    expect((await res.json()).error).toContain('配对码');
  });

  it('串流服务判定数据格式无效时透传 400 与原因', async () => {
    fetchMock.mockResolvedValue(reply({ error: '同步数据格式无效' }, 400));
    const res = await POST(makeRequest({ since: -1, changes: [] }));
    expect(res.status).toBe(400);
    expect((await res.json()).error).toBe('同步数据格式无效');
  });
});
