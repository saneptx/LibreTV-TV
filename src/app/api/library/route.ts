import { NextResponse } from 'next/server';
import { guardRequest, jsonError } from '@/lib/api-guard';
import { HubError, hubJson } from '@/lib/desktop-hub';

export const runtime = 'nodejs';

/** 电脑片库（串流服务后台处理并保存的整集）列表，供网页「已下载」页面使用 */
export async function GET(req: Request) {
  const denied = guardRequest(req);
  if (denied) return denied;
  try {
    const { body } = await hubJson('/v1/downloads');
    return NextResponse.json(body, { headers: { 'Cache-Control': 'no-store' } });
  } catch (error) {
    return error instanceof HubError ? jsonError(error.message, error.status) : jsonError('读取电脑片库失败', 502);
  }
}
