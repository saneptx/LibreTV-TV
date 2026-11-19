import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

/**
 * 网页服务所在电脑上的串流服务（desktop-server，默认 8090）。
 * 浏览器不直接连接它，也不需要填写配对码：网页服务用本机保存的配对码代为转发，
 * 能访问网页（通过了网页的访问验证）的设备即视为同一个家庭。
 *
 * - DESKTOP_SERVER_URL：串流服务地址，默认 http://127.0.0.1:8090
 * - DESKTOP_PAIRING_TOKEN：配对码；未设置时读取当前目录下串流服务生成的 .desktop-server/config.json
 */
export interface DesktopHub { url: string; token: string }

const validToken = (token: unknown): token is string => typeof token === 'string' && token.length >= 24 && token.length <= 256 && !/[\r\n]/.test(token);

export async function desktopHub(): Promise<DesktopHub | null> {
  let url: string;
  try {
    const parsed = new URL(process.env.DESKTOP_SERVER_URL?.trim() || 'http://127.0.0.1:8090');
    if (!['http:', 'https:'].includes(parsed.protocol)) return null;
    url = parsed.origin;
  } catch { return null; }
  const fromEnv = process.env.DESKTOP_PAIRING_TOKEN?.trim();
  if (fromEnv) return validToken(fromEnv) ? { url, token: fromEnv } : null;
  try {
    const { token } = JSON.parse(await readFile(resolve('.desktop-server/config.json'), 'utf8')) as { token?: unknown };
    return validToken(token) ? { url, token } : null;
  } catch { return null; }
}

/** 转发到串流服务；没有配置或连不上时抛出带中文说明的错误 */
export class HubError extends Error { constructor(message: string, public status: number) { super(message); } }

export async function hubFetch(path: string, init: { method?: string; body?: string; headers?: Record<string, string>; signal?: AbortSignal; timeoutMs?: number } = {}): Promise<Response> {
  const hub = await desktopHub();
  if (!hub) throw new HubError('这台电脑没有配置串流服务', 503);
  const timeout = AbortSignal.timeout(init.timeoutMs ?? 15_000);
  try {
    return await fetch(hub.url + path, {
      method: init.method || 'GET',
      headers: { Authorization: `Bearer ${hub.token}`, ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...init.headers },
      body: init.body,
      signal: init.signal ? AbortSignal.any([init.signal, timeout]) : timeout,
      cache: 'no-store',
    });
  } catch { throw new HubError('串流服务没有运行', 502); }
}

/** 串流服务的 JSON 应答原样转给浏览器；配对码不匹配属于部署问题，不能让浏览器误以为要重新登录网页 */
export async function hubJson(path: string, init: Parameters<typeof hubFetch>[1] = {}): Promise<{ status: number; body: unknown }> {
  const response = await hubFetch(path, init);
  const body: unknown = await response.json().catch(() => null);
  if (response.ok) return { status: response.status, body };
  const message = body && typeof body === 'object' && typeof (body as { error?: unknown }).error === 'string' ? (body as { error: string }).error : '串流服务出错';
  throw new HubError(response.status === 401 ? '串流服务的配对码不匹配' : message, [400, 404, 429, 501].includes(response.status) ? response.status : 502);
}
