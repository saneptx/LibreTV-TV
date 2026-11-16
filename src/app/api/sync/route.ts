import { NextResponse } from 'next/server';
import { guardRequest, jsonError } from '@/lib/api-guard';
import { desktopHub } from '@/lib/desktop-hub';

export const runtime = 'nodejs';

/** 与串流服务的收包上限一致（见 desktop-server/server.mjs 的 readJson） */
const MAX_BODY_BYTES = 3 * 1024 * 1024;

/**
 * 资料同步（观看历史、进度、搜索记录、片库收藏）：把浏览器的同步请求转发给本机的串流服务。
 * 协议见 desktop-server/sync.mjs；浏览器端逻辑见 src/lib/library-sync.ts。
 */
export async function POST(req: Request) {
  const denied = guardRequest(req);
  if (denied) return denied;
  const hub = await desktopHub();
  if (!hub) return jsonError('这台电脑没有配置串流服务，无法同步', 503);
  const text = await req.text();
  if (text.length > MAX_BODY_BYTES) return jsonError('同步数据过大', 413);
  try { JSON.parse(text); } catch { return jsonError('同步数据格式无效', 400); }
  try {
    const response = await fetch(`${hub.url}/v1/sync`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${hub.token}`, 'Content-Type': 'application/json' },
      body: text,
      signal: AbortSignal.timeout(15_000),
      cache: 'no-store',
    });
    const body: unknown = await response.json().catch(() => null);
    if (!response.ok) {
      const message = body && typeof body === 'object' && typeof (body as { error?: unknown }).error === 'string' ? (body as { error: string }).error : '同步失败';
      // 配对码不匹配是部署问题，不让浏览器误以为需要重新登录网页
      return jsonError(response.status === 401 ? '串流服务的配对码不匹配' : message, response.status === 400 ? 400 : 502);
    }
    return NextResponse.json(body, { headers: { 'Cache-Control': 'no-store' } });
  } catch {
    return jsonError('串流服务没有运行，暂时无法同步', 502);
  }
}
