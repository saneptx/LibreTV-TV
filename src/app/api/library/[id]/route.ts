import { NextResponse } from 'next/server';
import { guardRequest, jsonError } from '@/lib/api-guard';
import { HubError, hubJson } from '@/lib/desktop-hub';

export const runtime = 'nodejs';

const ID = /^[a-f0-9]{32}$/;
type Context = { params: Promise<{ id: string }> };
const fail = (error: unknown) => error instanceof HubError ? jsonError(error.message, error.status) : jsonError('电脑片库操作失败', 502);

/** 开始播放一条已下载的影片：在串流服务上建立播放任务，返回浏览器可用的播放地址 */
export async function POST(req: Request, { params }: Context) {
  const denied = guardRequest(req);
  if (denied) return denied;
  const { id } = await params;
  if (!ID.test(id)) return jsonError('无效的下载', 400);
  try {
    const { body } = await hubJson('/v1/sessions', { method: 'POST', body: JSON.stringify({ download: id }) });
    const session = (body as { id?: unknown } | null)?.id;
    if (typeof session !== 'string' || !ID.test(session)) return jsonError('串流服务应答无效', 502);
    return NextResponse.json({ session, playlist: `/api/library/stream/${session}/index.m3u8` }, { headers: { 'Cache-Control': 'no-store' } });
  } catch (error) { return fail(error); }
}

/** 删除一条下载（未完成的会取消） */
export async function DELETE(req: Request, { params }: Context) {
  const denied = guardRequest(req);
  if (denied) return denied;
  const { id } = await params;
  if (!ID.test(id)) return jsonError('无效的下载', 400);
  try {
    await hubJson(`/v1/downloads/${id}`, { method: 'DELETE' });
    return NextResponse.json({ removed: true });
  } catch (error) { return fail(error); }
}
