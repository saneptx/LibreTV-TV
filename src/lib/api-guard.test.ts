import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { guardRequest } from './api-guard';
import { SESSION_COOKIE, signSession } from './auth';
import { GET as getStatus } from '@/app/api/status/route';
import { GET as getAuth, POST as login } from '@/app/api/auth/route';

beforeEach(() => {
  vi.stubEnv('AUTH_DISABLED', undefined);
  vi.stubEnv('PASSWORD', 'test-password');
});

afterEach(() => vi.unstubAllEnvs());

describe('服务端访问验证开关', () => {
  const anonymous = () => new Request('http://localhost/api/search');

  it('默认仍拒绝没有 Cookie 的请求', () => {
    expect(guardRequest(anonymous())?.status).toBe(401);
  });

  it('客户端标识不能开启免登录', () => {
    const request = new Request('http://localhost/api/search', {
      headers: { 'user-agent': 'LibreTV-AndroidTV/0.1' },
    });
    expect(guardRequest(request)?.status).toBe(401);
  });

  it('显式关闭验证后，没有密码和 Cookie 也可访问', () => {
    vi.stubEnv('AUTH_DISABLED', 'true');
    vi.stubEnv('PASSWORD', undefined);
    expect(guardRequest(anonymous())).toBeNull();
  });

  it('免登录状态同时反映在网页状态和认证接口中', async () => {
    vi.stubEnv('AUTH_DISABLED', 'true');
    vi.stubEnv('PASSWORD', undefined);
    const status = await getStatus(anonymous());
    expect(await status.json()).toMatchObject({ passwordRequired: false, verified: true });
    expect(await (await getAuth(anonymous())).json()).toMatchObject({ verified: true });
    expect((await login(anonymous())).status).toBe(200);
  });

  it('重新开启验证后仍要求有效会话', async () => {
    vi.stubEnv('AUTH_DISABLED', 'false');
    expect(guardRequest(anonymous())?.status).toBe(401);
    expect(await (await getStatus(anonymous())).json()).toMatchObject({ passwordRequired: true, verified: false });
    const { token } = signSession();
    const signed = new Request('http://localhost/api/search', {
      headers: { cookie: `${SESSION_COOKIE}=${token}` },
    });
    expect(guardRequest(signed)).toBeNull();
  });

  it('开关值不正确时继续要求密码配置', () => {
    vi.stubEnv('AUTH_DISABLED', '1');
    vi.stubEnv('PASSWORD', undefined);
    expect(guardRequest(anonymous())?.status).toBe(503);
  });
});
