import { NextResponse } from 'next/server';
import { guardRequest, jsonError } from '@/lib/api-guard';
import { hubFetch } from '@/lib/desktop-hub';

export const runtime = 'nodejs';

/** 关闭播放任务（离开播放页时调用；不调用也会在闲置后自动回收） */
export async function DELETE(req: Request, { params }: { params: Promise<{ session: string }> }) {
  const denied = guardRequest(req);
  if (denied) return denied;
  const { session } = await params;
  if (!/^[a-f0-9]{32}$/.test(session)) return jsonError('无效的播放任务', 400);
  await hubFetch(`/v1/sessions/${session}`, { method: 'DELETE', timeoutMs: 5000 }).catch(() => undefined);
  return NextResponse.json({ closed: true });
}
