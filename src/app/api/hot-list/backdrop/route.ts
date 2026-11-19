import { NextResponse } from 'next/server';
import { guardRequest } from '@/lib/api-guard';
import { fetchBackdrop } from '@/lib/douban-backdrop';

export const runtime = 'nodejs';

/** 主推大图的横版剧照：?id=豆瓣条目号&tv=1；没有合适的剧照时 backdrop 为 null */
export async function GET(req: Request) {
  const guarded = guardRequest(req);
  if (guarded) return guarded;
  const params = new URL(req.url).searchParams, id = params.get('id') || '';
  if (!/^\d{1,12}$/.test(id)) return NextResponse.json({ error: '条目号无效' }, { status: 400 });
  return NextResponse.json({ backdrop: await fetchBackdrop(id, params.get('tv') === '1') }, { headers: { 'Cache-Control': 'private, max-age=1800' } });
}
