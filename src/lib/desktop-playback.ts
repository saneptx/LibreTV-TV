import { getNativeHttpBridge, nativeFetch } from './native-http';
import { parseProcessingInfo, processingPresentation, type ProcessingInfo, type ProcessingPresentation } from './playback-processing';

export interface RemovedInterval { start: number; duration: number }
export function sourcePosition(position: number, removals: RemovedInterval[] = []): number {
  let result = position, removed = 0;
  for (const interval of removals) { if (position + .00001 >= interval.start - removed) result += interval.duration; removed += interval.duration; }
  return result;
}
export function sourceDuration(duration: number, removals: RemovedInterval[] = []): number { return duration > 0 ? duration + removals.reduce((n, r) => n + r.duration, 0) : 0; }
export interface ProcessingProgress { message: string; stage?: string; state?: string; speed?: number; output?: { width: number; height: number; model?: string } }
export interface DesktopSettings { desktopServerUrl: string; desktopPairingToken: string }
export interface DesktopSession { id: string; server: string; token: string }
export interface PlaybackDelivery {
  url: string;
  headers: Record<string, string>;
  delivery: 'direct' | 'desktop';
  desktopSession?: DesktopSession;
  processing?: 'relay' | 'enhance';
  label?: string;
  position?: number;
  timeMap?: RemovedInterval[];
  sourceHeight?: number;
  /** 设备侧识别出的额外广告分片行（编码不一致 / 已学习），直连时交给 Media3 过滤器 */
  adSegments?: string[];
  adSummary?: { codecGroups: number; learnedGroups: number; repeatGroups: number };
  /** 直连时播放器使用与分析相同的媒体列表原文 */
  playlists?: { url: string; text: string }[];
  processingInfo?: ProcessingInfo;
  presentation?: ProcessingPresentation;
}
export function desktopAddress(value: string): string {
  let url: URL;
  try { url = new URL(value.trim()); } catch { throw new Error('请填写电脑服务地址，例如 http://192.168.1.10:8090'); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error('电脑服务地址只能包含协议、主机和端口');
  return url.origin;
}
async function api(settings: DesktopSettings, path: string, method: 'GET' | 'POST' | 'DELETE', body?: unknown, signal?: AbortSignal): Promise<Record<string, unknown>> {
  const base = desktopAddress(settings.desktopServerUrl), token = settings.desktopPairingToken.trim();
  if (token.length < 24 || token.length > 256 || /[\r\n]/.test(token)) throw new Error('请填写电脑服务生成的配对码（独立直连无需配对）');
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 8000);
  const abort = () => controller.abort(); signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) controller.abort();
  try {
    const options = { method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal };
    const response = await (getNativeHttpBridge() ? nativeFetch(base + path, { ...options, timeoutMs: 7000, maxBytes: 65536 }) : fetch(base + path, options));
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || `电脑服务返回 ${response.status}`);
    return result;
  } catch (error) {
    if (signal?.aborted) throw new DOMException('已取消', 'AbortError');
    if (error instanceof Error && !['TypeError', 'AbortError'].includes(error.name)) throw error;
    throw new Error('无法连接电脑服务，请检查地址、服务进程和网络；也可切回独立直连');
  } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort); }
}
export async function testDesktopConnection(settings: DesktopSettings, signal?: AbortSignal): Promise<string> {
  const result = await api(settings, '/v1/status', 'GET', undefined, signal);
  if (result.protocol !== 1 || result.service !== 'LibreTV Desktop' || !['relay','enhance'].includes(String(result.processing))) throw new Error('电脑服务版本不兼容，请更新服务');
  return result.processing === 'enhance' ? `已连接电脑 · 广告分片过滤 / ${String((result.capabilities as Record<string, unknown>)?.model || 'AI')} 超分 / 编码已启用` : '已连接电脑 · 当前仅串流，去广告和超分尚未启用';
}
export async function closeDesktopSession(session?: DesktopSession): Promise<void> {
  if (!session || !/^[a-f0-9]{32}$/.test(session.id)) return;
  try { await api({ desktopServerUrl: session.server, desktopPairingToken: session.token }, `/v1/sessions/${session.id}`, 'DELETE'); }
  catch { /* 断线后由服务端 TTL 清理；不阻塞换集、退出或独立模式。 */ }
}
export async function resolvePlaybackDelivery(url: string, settings: DesktopSettings & { playbackMode?: 'direct' | 'desktop'; superResolution?: boolean }, signal?: AbortSignal, options: { position?: number; adFilter?: boolean; adSegments?: string[]; onProgress?: (progress: ProcessingProgress) => void } = {}): Promise<PlaybackDelivery> {
  if (settings.playbackMode !== 'desktop') return { url, headers: {}, delivery: 'direct' };
  // Do not abort the small create response after it reaches the server: retain its id so a late cancellation can DELETE it.
  if (signal?.aborted) throw new DOMException('已取消', 'AbortError');
  const result = await api(settings, '/v1/sessions', 'POST', { url, superResolution: settings.superResolution !== false, ...(options.position === undefined ? {} : { position: options.position }), ...(options.adFilter === undefined ? {} : { adFilter: options.adFilter }), ...(options.adSegments?.length ? { adSegments: options.adSegments } : {}) });
  if (result.protocol !== 1 || typeof result.id !== 'string' || !/^[a-f0-9]{32}$/.test(result.id) || typeof result.path !== 'string' || !['relay','enhance'].includes(String(result.processing)) || !result.path.startsWith(`/v1/sessions/${result.id}/`)) throw new Error('电脑服务返回了无效播放任务');
  const desktopSession = { id: result.id, server: desktopAddress(settings.desktopServerUrl), token: settings.desktopPairingToken.trim() };
  const target = new URL(result.path, desktopSession.server);
  if (target.origin !== desktopSession.server || target.search || target.hash || !(result.processing === 'enhance' ? target.pathname === `/v1/sessions/${result.id}/processed/index.m3u8` : /^\/v1\/sessions\/[a-f0-9]{32}\/media\/[a-f0-9]{32}(?:\.[a-zA-Z0-9]{1,8})?$/.test(target.pathname))) { void closeDesktopSession(desktopSession); throw new Error('电脑服务返回的播放地址无效'); }
  if (signal?.aborted) { void closeDesktopSession(desktopSession); throw new DOMException('已取消', 'AbortError'); }
  let status: Record<string, unknown> | undefined;
  if (result.processing === 'enhance') {
    try {
      const deadline = Date.now() + 15 * 60_000;
      while (Date.now() < deadline) {
        status = await api(settings, `/v1/sessions/${desktopSession.id}`, 'GET', undefined, signal);
        options.onProgress?.(status as unknown as ProcessingProgress);
        if (status.state === 'error') throw new Error(String(status.error || status.message || '电脑处理视频失败'));
        if (status.state === 'ready') break;
        await new Promise<void>((resolve, reject) => {
          const abort = () => { clearTimeout(timer); reject(new DOMException('已取消', 'AbortError')); };
          const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve(); }, 700);
          if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
        });
      }
      if (settings.superResolution === false && (status?.processingInfo as { superResolutionEnabled?: boolean } | undefined)?.superResolutionEnabled !== false) throw new Error('电脑服务尚不支持关闭超分，请更新并重启电脑服务');
      if (status?.state !== 'ready') throw new Error('电脑处理等待超时，请检查电脑处理速度');
      if (status.timeMap !== undefined && (!Array.isArray(status.timeMap) || status.timeMap.length > 12000 || status.timeMap.some((r: RemovedInterval) => !Number.isFinite(r.start) || !Number.isFinite(r.duration) || r.start < 0 || r.duration <= 0))) throw new Error('电脑返回的广告时间轴无效');
    } catch (error) { void closeDesktopSession(desktopSession); throw error; }
  }
  const processingInfo = parseProcessingInfo(status?.processingInfo);
  return { processingInfo, presentation: processingInfo ? processingPresentation(processingInfo) : undefined, processing: result.processing as 'relay' | 'enhance', sourceHeight: typeof (status?.input as { height?: number } | undefined)?.height === 'number' ? (status!.input as { height: number }).height : undefined, label: typeof status?.label === 'string' ? status.label : '电脑串流 · 原画转发', position: typeof status?.position === 'number' ? status.position : options.position, timeMap: status?.timeMap as RemovedInterval[] | undefined, url: target.href, headers: { Authorization: `Bearer ${desktopSession.token}` }, delivery: 'desktop', desktopSession };
}
