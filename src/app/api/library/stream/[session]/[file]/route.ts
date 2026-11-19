import { guardRequest, jsonError } from '@/lib/api-guard';
import { HubError, hubFetch } from '@/lib/desktop-hub';

export const runtime = 'nodejs';

/**
 * 已下载影片的播放数据：把浏览器的请求转发给串流服务（由网页服务带上配对码）。
 * 只转发处理后的播放列表和分段，不能借此访问串流服务的其他接口。
 */
export async function GET(req: Request, { params }: { params: Promise<{ session: string; file: string }> }) {
  const denied = guardRequest(req);
  if (denied) return denied;
  const { session, file } = await params;
  if (!/^[a-f0-9]{32}$/.test(session) || !/^(index\.m3u8|\d{1,6}\.ts)$/.test(file)) return jsonError('无效的播放地址', 400);
  const base = `/v1/sessions/${session}/processed`;
  try {
    const range = req.headers.get('range');
    const upstream = await hubFetch(`${base}/${file}`, { headers: range ? { Range: range } : {}, signal: req.signal, timeoutMs: 120_000 });
    if (!upstream.ok && upstream.status !== 206) {
      const body = await upstream.json().catch(() => null) as { error?: string } | null;
      return jsonError(upstream.status === 401 ? '串流服务的配对码不匹配' : body?.error || '读取影片失败', [404, 410, 416].includes(upstream.status) ? upstream.status : 502);
    }
    if (file === 'index.m3u8') {
      // 分段地址改成相对路径，浏览器继续经本接口读取
      const text = (await upstream.text()).split(`${base}/`).join('');
      return new Response(text, { headers: { 'Content-Type': 'application/vnd.apple.mpegurl', 'Cache-Control': 'no-store' } });
    }
    const headers = new Headers({ 'Content-Type': 'video/mp2t', 'Accept-Ranges': 'bytes', 'Cache-Control': 'no-store' });
    for (const name of ['content-length', 'content-range']) { const value = upstream.headers.get(name); if (value) headers.set(name, value); }
    return new Response(upstream.body, { status: upstream.status, headers });
  } catch (error) {
    return error instanceof HubError ? jsonError(error.message, error.status) : jsonError('读取影片失败', 502);
  }
}
