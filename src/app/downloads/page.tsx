'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Hls from 'hls.js';
import { Header } from '@/components/header';
import { SiteFooter } from '@/components/site-footer';
import { ConfirmDialog } from '@/components/confirm-dialog';
import { EmptyState, ErrorState, LoadingState } from '@/components/states';
import { useToast } from '@/components/toast';
import { Icon } from '@/components/icon';
import { formatTime } from '@/lib/utils';
import type { DesktopDownload } from '@/lib/desktop-playback';

const QUERY_KEY = ['desktop-library'] as const;
const STATE_TEXT: Record<DesktopDownload['state'], string> = { queued: '排队中', preparing: '准备中', downloading: '处理中', done: '已完成', error: '失败' };

/** 不走 client-api 的 request：这里的 503 表示电脑没有配置串流服务，不是要登录网页 */
async function call<T>(url: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try { response = await fetch(url, init); } catch { throw new Error('网络请求失败，请检查网络连接'); }
  const body: unknown = await response.json().catch(() => null);
  if (!response.ok) throw new Error((body as { error?: string } | null)?.error || `请求失败 (${response.status})`);
  return body as T;
}

function formatBytes(bytes: number): string {
  if (!(bytes > 0)) return '0 MB';
  return bytes >= 1024 ** 3 ? `${(bytes / 1024 ** 3).toFixed(1)} GB` : `${Math.max(1, Math.round(bytes / 1024 ** 2))} MB`;
}

/** 电脑片库：串流服务后台处理好并保存在电脑上的整集，在浏览器里查看、播放、删除 */
export default function DownloadsPage() {
  const client = useQueryClient();
  const { toast } = useToast();
  const [playing, setPlaying] = useState<DesktopDownload | null>(null);
  const [removing, setRemoving] = useState<DesktopDownload | null>(null);
  const { data, error, isPending, refetch } = useQuery({
    queryKey: QUERY_KEY,
    queryFn: () => call<{ items: DesktopDownload[] }>('/api/library'),
    // 有未完成的下载时每 3 秒刷新进度
    refetchInterval: (query) => query.state.data?.items.some((i) => i.state !== 'done' && i.state !== 'error') ? 3000 : 20_000,
    retry: false,
  });
  const items = data?.items ?? [];

  const remove = async (item: DesktopDownload) => {
    setRemoving(null);
    try {
      await call(`/api/library/${item.id}`, { method: 'DELETE' });
      toast(`已删除「${item.title || '未命名'}」`, 'success');
    } catch (e) { toast(e instanceof Error ? e.message : '删除失败', 'error'); }
    void client.invalidateQueries({ queryKey: QUERY_KEY });
  };

  return (
    <div className="min-h-screen flex flex-col">
      <Header />
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-8 py-8">
        <div className="flex items-baseline gap-3 mb-6">
          <h1 className="text-2xl font-bold tracking-tight text-content">已下载</h1>
          {items.length > 0 && (
            <p className="text-xs text-faint">{items.length} 个 · 共 {formatBytes(items.reduce((sum, i) => sum + (i.bytes || 0), 0))} · 保存在电脑上</p>
          )}
        </div>
        {isPending ? <LoadingState label="正在读取电脑片库……" />
          : error ? <ErrorState message={error.message} onRetry={() => void refetch()} />
          : items.length === 0 ? (
            <EmptyState icon="download" title="电脑片库里还没有影片" description="在电视的播放页选择「下载到电脑」，电脑会在后台把整集处理好保存下来，之后在这里和电视上都能直接观看。" />
          ) : (
            <ul className="space-y-2.5">
              {items.map((item) => <DownloadRow key={item.id} item={item} onPlay={() => setPlaying(item)} onRemove={() => setRemoving(item)} />)}
            </ul>
          )}
      </main>
      <SiteFooter />
      {playing && <LibraryPlayer item={playing} onClose={() => setPlaying(null)} />}
      <ConfirmDialog
        open={!!removing}
        title="删除这个下载？"
        message={removing ? `「${removing.title || '未命名'}${removing.subtitle ? ' · ' + removing.subtitle : ''}」会从电脑上删除，电视上也不再能离线观看。` : undefined}
        confirmLabel="删除"
        danger
        onConfirm={() => removing && void remove(removing)}
        onCancel={() => setRemoving(null)}
      />
    </div>
  );
}

function DownloadRow({ item, onPlay, onRemove }: { item: DesktopDownload; onPlay: () => void; onRemove: () => void }) {
  const [posterFailed, setPosterFailed] = useState(false);
  const percent = item.segments ? Math.min(100, Math.round((item.done / item.segments) * 100)) : 0;
  const active = item.state !== 'done' && item.state !== 'error';
  const details = [
    item.state === 'done' ? item.label : STATE_TEXT[item.state] + (item.state === 'downloading' ? ` ${percent}%` : ''),
    item.duration ? formatTime(item.duration) : undefined,
    item.bytes ? formatBytes(item.bytes) : undefined,
  ].filter(Boolean).join(' · ');
  return (
    <li className="flex items-center gap-4 p-3 rounded-2xl bg-card hover:bg-chip/60 transition-colors">
      <div className="w-16 h-24 shrink-0 rounded-xl bg-chip overflow-hidden flex items-center justify-center">
        {item.meta?.pic && !posterFailed
          // eslint-disable-next-line @next/next/no-img-element
          ? <img src={item.meta.pic} alt="" className="w-full h-full object-cover" loading="lazy" referrerPolicy="no-referrer" onError={() => setPosterFailed(true)} />
          : <Icon name="download" className="w-5 h-5 text-faint" />}
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-sm font-semibold text-content line-clamp-1">{item.title || '未命名'}</p>
        {item.subtitle && <p className="text-xs text-muted mt-0.5 line-clamp-1">{item.subtitle}</p>}
        <p className={item.state === 'error' ? 'text-xs text-danger mt-1.5 line-clamp-2' : 'text-xs text-faint mt-1.5 line-clamp-1'}>
          {item.state === 'error' ? `失败：${item.error || '电脑处理失败'}` : details}
        </p>
        {active && (
          <div className="mt-2 h-1 rounded-full bg-chip overflow-hidden" role="progressbar" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100} aria-label="下载进度">
            <div className="h-full bg-accent transition-all" style={{ width: `${percent}%` }} />
          </div>
        )}
      </div>
      <div className="flex items-center gap-2 shrink-0">
        {item.state === 'done' && <button type="button" className="btn-primary" onClick={onPlay}>播放</button>}
        <button type="button" className="btn-ghost" onClick={onRemove}>{active ? '取消' : '删除'}</button>
      </div>
    </li>
  );
}

const positionKey = (id: string) => `libretv-library-position:${id}`;

/** 全屏播放已下载的影片；记住这台设备上次看到的位置 */
function LibraryPlayer({ item, onClose }: { item: DesktopDownload; onClose: () => void }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const close = useCallback(() => onClose(), [onClose]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    let hls: Hls | undefined, session = '', cancelled = false;
    setError('');
    const resume = () => {
      const saved = Number(localStorage.getItem(positionKey(item.id))) || 0;
      // 快看完的从头开始
      if (saved > 5 && (!item.duration || saved < item.duration - 30)) video.currentTime = saved;
      void video.play().catch(() => undefined);
    };
    const save = () => { if (video.currentTime > 0) try { localStorage.setItem(positionKey(item.id), String(Math.floor(video.currentTime))); } catch { /* 存不下就不记 */ } };
    const timer = window.setInterval(save, 5000);
    call<{ session: string; playlist: string }>(`/api/library/${item.id}`, { method: 'POST' }).then((reply) => {
      session = reply.session;
      if (cancelled) return;
      if (Hls.isSupported()) {
        hls = new Hls({ maxBufferLength: 60 });
        hls.on(Hls.Events.MANIFEST_PARSED, resume);
        hls.on(Hls.Events.ERROR, (_event, data) => {
          if (!data.fatal) return;
          if (data.type === Hls.ErrorTypes.MEDIA_ERROR && data.details !== Hls.ErrorDetails.BUFFER_INCOMPATIBLE_CODECS_ERROR && data.details !== Hls.ErrorDetails.MANIFEST_INCOMPATIBLE_CODECS_ERROR) { hls?.recoverMediaError(); return; }
          setError(data.type === Hls.ErrorTypes.MEDIA_ERROR ? '这个浏览器无法解码这部影片（可能是 HEVC 编码），请换用 Edge / Chrome 或在电视上观看' : '读取影片失败，请确认电脑串流服务正在运行');
        });
        hls.loadSource(reply.playlist); hls.attachMedia(video);
      } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
        video.src = reply.playlist;
        video.addEventListener('loadedmetadata', resume, { once: true });
      } else setError('这个浏览器不支持播放');
    }).catch((e: unknown) => { if (!cancelled) setError(e instanceof Error ? e.message : '无法开始播放'); });
    return () => {
      cancelled = true; window.clearInterval(timer); save(); hls?.destroy();
      video.removeAttribute('src'); video.load();
      if (session) void fetch(`/api/library/stream/${session}`, { method: 'DELETE', keepalive: true }).catch(() => undefined);
    };
  }, [item.id, item.duration, attempt]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape' && !document.fullscreenElement) close(); };
    window.addEventListener('keydown', handler);
    const overflow = document.body.style.overflow; document.body.style.overflow = 'hidden';
    return () => { window.removeEventListener('keydown', handler); document.body.style.overflow = overflow; };
  }, [close]);

  return (
    <div className="fixed inset-0 z-50 bg-black flex flex-col" role="dialog" aria-modal="true" aria-label={`播放 ${item.title}`}>
      <div className="flex items-center gap-3 px-4 h-14 shrink-0 text-white">
        <button type="button" onClick={close} aria-label="关闭播放" className="w-9 h-9 rounded-full bg-white/10 hover:bg-white/20 flex items-center justify-center focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/60">
          <Icon name="close" className="w-4 h-4" />
        </button>
        <p className="text-sm font-semibold line-clamp-1">{item.title}</p>
        {item.subtitle && <p className="text-xs text-white/60 line-clamp-1 hidden sm:block">{item.subtitle}</p>}
        {item.label && <p className="text-xs text-white/60 ml-auto shrink-0 hidden sm:block">{item.label}</p>}
      </div>
      <div className="flex-1 min-h-0 relative">
        <video ref={videoRef} className="w-full h-full bg-black" controls playsInline />
        {error && (
          <div className="absolute inset-0 flex items-center justify-center bg-black/80">
            <ErrorState tone="onDark" message={error} onRetry={() => setAttempt((n) => n + 1)} />
          </div>
        )}
      </div>
    </div>
  );
}
