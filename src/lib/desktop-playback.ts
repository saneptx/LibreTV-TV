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
  adSummary?: { codecGroups: number; learnedGroups: number; repeatGroups: number; timelineGroups?: number };
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
async function api(settings: DesktopSettings, path: string, method: 'GET' | 'POST' | 'DELETE', body?: unknown, signal?: AbortSignal, limits: { timeoutMs?: number; maxBytes?: number } = {}): Promise<Record<string, unknown>> {
  const base = desktopAddress(settings.desktopServerUrl), token = settings.desktopPairingToken.trim();
  if (token.length < 24 || token.length > 256 || /[\r\n]/.test(token)) throw new Error('请填写电脑服务生成的配对码（独立直连无需配对）');
  const timeoutMs = limits.timeoutMs ?? 8000;
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), timeoutMs);
  const abort = () => controller.abort(); signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) controller.abort();
  try {
    const options = { method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal };
    const response = await (getNativeHttpBridge() ? nativeFetch(base + path, { ...options, timeoutMs: timeoutMs - 1000, maxBytes: limits.maxBytes ?? 65536 }) : fetch(base + path, options));
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
/** 资料同步：推送本机修改、取回其他设备的修改（见 desktop-server/sync.mjs） */
export async function exchangeDesktopLibrary(settings: DesktopSettings, body: unknown, signal?: AbortSignal): Promise<Record<string, unknown>> {
  return api(settings, '/v1/sync', 'POST', body, signal, { timeoutMs: 20000, maxBytes: 4 * 1024 * 1024 });
}
/** 设备硬件解码能力（APK 上报），电脑据此选择 HEVC 或 H.264 输出 */
export type DecoderReport = Partial<Record<'hevc' | 'h264' | 'av1', { maxWidth: number; maxHeight: number; fps1080: number; fps2160: number }>>;
export function sanitizeDecoders(value: unknown): DecoderReport | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const result: DecoderReport = {};
  for (const codec of ['hevc', 'h264', 'av1'] as const) {
    const d = (value as Record<string, Record<string, unknown>>)[codec];
    if (!d || typeof d !== 'object') continue;
    const n = (k: string) => { const v = Number(d[k]); return Number.isFinite(v) && v >= 0 && v <= 8192 ? Math.round(v) : 0; };
    result[codec] = { maxWidth: n('maxWidth'), maxHeight: n('maxHeight'), fps1080: n('fps1080'), fps2160: n('fps2160') };
  }
  return Object.keys(result).length ? result : undefined;
}
export interface CompareRequest { primary: { url: string; playlists?: { url: string; text: string }[] }; references: string[]; adFilter?: boolean; adSegments?: string[] }
export interface CompareResult { intervals: RemovedInterval[]; references: number }
/** 电脑多来源比对：创建任务后轮询，取消时删除电脑端任务 */
export async function compareOnDesktop(settings: DesktopSettings, request: CompareRequest, signal?: AbortSignal, onProgress?: (message: string) => void): Promise<CompareResult> {
  let status = await api(settings, '/v1/compare', 'POST', request, signal);
  const id = String(status.id || '');
  if (status.protocol !== 1 || !/^[a-f0-9]{32}$/.test(id)) throw new Error('电脑服务尚不支持多来源比对，请更新电脑服务');
  try {
    const deadline = Date.now() + 10 * 60_000;
    while (status.state === 'running') {
      if (Date.now() > deadline) throw new Error('多来源比对超时');
      onProgress?.(String(status.message || '电脑正在多来源比对'));
      await new Promise<void>((resolve, reject) => {
        const abort = () => { clearTimeout(timer); reject(new DOMException('已取消', 'AbortError')); };
        const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve(); }, 2000);
        if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
      });
      status = await api(settings, `/v1/compare/${id}`, 'GET', undefined, signal);
    }
  } catch (error) { if (signal?.aborted) void api(settings, `/v1/compare/${id}`, 'DELETE').catch(() => undefined); throw error; }
  if (status.state !== 'done') throw new Error(String(status.error || status.message || '多来源比对失败'));
  const intervals = Array.isArray(status.intervals) ? (status.intervals as RemovedInterval[]).filter((r) => Number.isFinite(r.start) && Number.isFinite(r.duration) && r.start >= 0 && r.duration > 0 && r.duration <= 600).slice(0, 100) : [];
  return { intervals, references: Number(status.references) || 0 };
}
/** 电脑片库条目（电脑后台处理完整集后保存） */
export interface DesktopDownload {
  id: string; url: string; title: string; subtitle: string; state: 'queued' | 'preparing' | 'downloading' | 'done' | 'error';
  done: number; segments?: number; bytes: number; duration?: number; label?: string; error?: string; createdAt: number;
  meta?: { sourceKey?: string; sourceName?: string; sourceUrl?: string; vodId?: string; index?: number; name?: string; pic?: string };
}
export async function listDesktopDownloads(settings: DesktopSettings, signal?: AbortSignal): Promise<DesktopDownload[]> {
  const result = await api(settings, '/v1/downloads', 'GET', undefined, signal);
  return Array.isArray(result.items) ? result.items as DesktopDownload[] : [];
}
export async function addDesktopDownload(settings: DesktopSettings, body: Record<string, unknown>, signal?: AbortSignal): Promise<DesktopDownload> {
  return await api(settings, '/v1/downloads', 'POST', body, signal) as unknown as DesktopDownload;
}
export async function removeDesktopDownload(settings: DesktopSettings, id: string): Promise<void> {
  if (!/^[a-f0-9]{32}$/.test(id)) throw new Error('无效的下载');
  await api(settings, `/v1/downloads/${id}`, 'DELETE');
}
export async function closeDesktopSession(session?: DesktopSession): Promise<void> {
  if (!session || !/^[a-f0-9]{32}$/.test(session.id)) return;
  try { await api({ desktopServerUrl: session.server, desktopPairingToken: session.token }, `/v1/sessions/${session.id}`, 'DELETE'); }
  catch { /* 断线后由服务端 TTL 清理；不阻塞换集、退出或独立模式。 */ }
}
export async function resolvePlaybackDelivery(url: string, settings: DesktopSettings & { playbackMode?: 'direct' | 'desktop'; superResolution?: boolean; overlayRemoval?: boolean }, signal?: AbortSignal, options: { position?: number; adFilter?: boolean; adSegments?: string[]; playlists?: { url: string; text: string }[]; decoders?: DecoderReport; onProgress?: (progress: ProcessingProgress) => void } = {}): Promise<PlaybackDelivery> {
  if (settings.playbackMode !== 'desktop') return { url, headers: {}, delivery: 'direct' };
  // Do not abort the small create response after it reaches the server: retain its id so a late cancellation can DELETE it.
  if (signal?.aborted) throw new DOMException('已取消', 'AbortError');
  const create = (extras: boolean) => api(settings, '/v1/sessions', 'POST', { url, superResolution: settings.superResolution !== false, overlayRemoval: settings.overlayRemoval !== false, ...(options.decoders ? { decoders: options.decoders } : {}), ...(options.position === undefined ? {} : { position: options.position }), ...(options.adFilter === undefined ? {} : { adFilter: options.adFilter }),
    ...(extras && options.adSegments?.length ? { adSegments: options.adSegments } : {}), ...(extras && options.playlists?.length ? { playlists: options.playlists } : {}) });
  // 旧版电脑服务请求体上限较小：去掉设备附带的列表原文和分片标记后重试一次。
  const result = await create(true).catch((error) => { if (error instanceof Error && error.message === '请求过大' && (options.playlists?.length || options.adSegments?.length)) return create(false); throw error; });
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
