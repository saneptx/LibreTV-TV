'use client';
import { useEffect, useRef, useState } from 'react';
import type { PlayerShellProps } from './player-shell';
import { buildNativePlaybackRequest, getNativePlayerBridge, NativePlaybackMedia, type NativePlaybackState, type PlaybackMedia } from '@/lib/native-playback';
import { useAppStore } from '@/lib/store';
import { closeDesktopSession, resolvePlaybackDelivery, sourcePosition, sourceDuration, type RemovedInterval, type DesktopSession } from '@/lib/desktop-playback';
import type { ProcessingPresentation } from '@/lib/playback-processing';
type Props = PlayerShellProps & { onMediaReady: (media: PlaybackMedia | null) => void; retryKey?: number; onProcessingStatus?: (message: string) => void; onProcessingPresentation?: (value: ProcessingPresentation | null) => void };

export function NativePlayerShell(props: Props) {
  const latest = useRef(props);
  latest.current = props;
  const mediaRef = useRef<NativePlaybackMedia | null>(null);
  const [state, setState] = useState<NativePlaybackState | null>(null);
  const playbackMode = useAppStore((s) => s.playbackMode);
  const superResolution = useAppStore((s) => s.playbackMode === 'desktop' ? s.superResolution : undefined);
  const processingRef = useRef<'relay' | 'enhance' | undefined>(undefined);
  const desktopAdFilter = playbackMode === 'desktop' ? props.adFilter : undefined;
  const restartPosition = useRef<{ url: string; key: Props['episodeKey']; position: number } | null>(null);
  useEffect(() => {
    const bridge = getNativePlayerBridge();
    if (!bridge) return;
    const session = latest.current;
    const callbacks = () => latest.current.url === session.url && latest.current.episodeKey === session.episodeKey ? latest.current : session;
    const sessionId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const media = new NativePlaybackMedia(sessionId, bridge);
    mediaRef.current = media;
    let cancelled = false, lastSave = 0, started = false, lastError = '', height = 0;
    let desktopSession: DesktopSession | undefined;
    let timeMap: RemovedInterval[] = [];
    let sourceHeight: number | undefined;
    let presentation: ProcessingPresentation | undefined, reportedPresentation = '';
    const controller = new AbortController();
    setState(null);
    latest.current.onProcessingPresentation?.(null);
    latest.current.onPlaybackError?.('');
    latest.current.onDownloadSpeed?.(0);
    latest.current.onMediaReady(media);
    const receive = (event: Event) => {
      const next = (event as CustomEvent<NativePlaybackState>).detail;
      if (!next || next.sessionId !== sessionId) return;
      const previous = media.state;
      if (!media.update(next)) return;
      if (next.rendered && next.width && next.height) {
        const summary = presentation || {
          video: `${playbackMode === 'desktop' ? '电脑原画转发' : '原始直连'} · 未超分 · 当前 ${next.width}×${next.height}`,
          ads: latest.current.adFilter ? '去广告：设备分片过滤已开启（不含内嵌广告）' : '去广告：已关闭',
          overlays: '',
        };
        const key = JSON.stringify(summary);
        if (key !== reportedPresentation) { reportedPresentation = key; latest.current.onProcessingPresentation?.(summary); }
      }
      setState(next);
      latest.current.onDownloadSpeed?.(next.bytesPerSecond);
      latest.current.onPlaybackError?.(next.error);
      if (next.height > height) { height = next.height; latest.current.onQuality?.(sourceHeight || height); }
      if (!next.ended && next.duration > 0 && Date.now() - lastSave >= 5000) {
        lastSave = Date.now(); callbacks().onTimeUpdate?.(sourcePosition(next.position, timeMap), sourceDuration(next.duration, timeMap));
      }
      if (next.paused && !previous.paused) callbacks().onPause?.(sourcePosition(next.position, timeMap), sourceDuration(next.duration, timeMap));
      if (next.ended && !previous.ended) callbacks().onEnded?.();
      if (next.error && next.error !== lastError && started && playbackMode !== 'desktop') latest.current.onRequestSwitchSource?.(next.error);
      lastError = next.error;
      if (next.rendered) started = true;
    };
    window.addEventListener('libretv-native-player-state', receive);
    document.documentElement.setAttribute('data-native-playback', '');
    void (async () => {
      let position = 0;
      if (restartPosition.current?.url === session.url && restartPosition.current.key === session.episodeKey) { position = restartPosition.current.position; restartPosition.current = null; }
      else try { position = await session.getRestorePosition?.() ?? 0; } catch { /* 无历史也可播放 */ }
      if (cancelled) return;
      try {
        const delivery = await resolvePlaybackDelivery(session.url, useAppStore.getState(), controller.signal, { position, adFilter: latest.current.adFilter, onProgress: (value) => { if (!cancelled) latest.current.onProcessingStatus?.(value.message); } });
        timeMap = delivery.timeMap || []; sourceHeight = delivery.sourceHeight; processingRef.current = delivery.processing;
        presentation = delivery.presentation || (delivery.processing === 'enhance' ? { video: delivery.label || '电脑增强 · 处理详情未知', ads: '去广告：处理结果未知，请更新电脑服务', overlays: '' } : undefined);
        desktopSession = delivery.desktopSession;
        if (cancelled) { void closeDesktopSession(desktopSession); desktopSession = undefined; return; }
        const request = delivery.delivery === 'desktop'
          ? { ...delivery, desktopSession: undefined, position: delivery.position ?? position, adFilter: delivery.processing === 'enhance' ? false : latest.current.adFilter }
          : buildNativePlaybackRequest(session.url, location.href, position, latest.current.adFilter);
        media.command('load', { request });
        latest.current.onProcessingStatus?.('');
        media.setFit(media.fit);
      } catch (error) {
        if (cancelled) return;
        restartPosition.current = { url: session.url, key: session.episodeKey, position };
        const message = error instanceof Error ? error.message : '播放地址无效';
        media.update({ ...media.state, error: message }); setState(media.state);
        latest.current.onPlaybackError?.(message);
      }
    })();
    return () => {
      cancelled = true;
      if (media.currentTime > 0) restartPosition.current = { url: session.url, key: session.episodeKey, position: sourcePosition(media.currentTime, timeMap) };
      controller.abort(); void closeDesktopSession(desktopSession);
      if (!media.ended) callbacks().onPause?.(sourcePosition(media.currentTime, timeMap), sourceDuration(media.duration, timeMap));
      media.command('stop');
      window.removeEventListener('libretv-native-player-state', receive);
      document.documentElement.removeAttribute('data-native-playback');
      latest.current.onMediaReady(null);
      if (mediaRef.current === media) mediaRef.current = null;
    };
  }, [props.url, props.episodeKey, playbackMode, desktopAdFilter, superResolution, props.retryKey]);
  useEffect(() => { mediaRef.current?.command('filter', { enabled: processingRef.current !== 'enhance' && props.adFilter }); }, [props.adFilter, playbackMode]);
  return <div data-native-player data-native-state={state ? JSON.stringify(state) : undefined}>
    {state?.error && <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/80" data-tv-playback-error>
      <p className="text-danger text-sm">{state.error}</p>
      <p className="text-sm">{playbackMode === 'desktop' ? '电脑串流暂不可用' : '按下键打开控制栏，可选择换源'}</p>
    </div>}
  </div>;
}
