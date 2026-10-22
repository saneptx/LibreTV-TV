'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { PlayerShell, type PlayerShellProps } from './player-shell';
import { SwitchSourceModal } from './switch-source';
import { Spinner } from './states';
import { useAppStore } from '@/lib/store';
import { formatTime } from '@/lib/utils';
import { formatDownloadSpeed } from '@/lib/download-speed';
import { clampTvSeek, nextTvFocus, type TvDirection } from '@/lib/tv-player-navigation';
import styles from './tv-watch-player.module.css';

interface TvWatchPlayerProps {
  player: PlayerShellProps;
  sourceName: string;
  sourceKey: string;
  vodId: string;
  episodes: string[];
  currentIndex: number;
  loading: boolean;
  loadError: boolean;
  onEpisode: (index: number) => void;
  onExit: () => void;
}

type Panel = 'episodes' | 'settings' | 'source' | null;
const DIRECTIONS = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'];

function moveFocus(scope: HTMLElement | null, direction: TvDirection) {
  if (!scope) return;
  const nodes = Array.from(scope.querySelectorAll<HTMLElement>('button:not([disabled]), [role="slider"]'))
    .filter((node) => node.offsetWidth > 0 && node.offsetHeight > 0);
  const next = nextTvFocus(nodes.map((node) => node.getBoundingClientRect()), nodes.indexOf(document.activeElement as HTMLElement), direction);
  if (next < 0) return;
  nodes[next].focus({ preventScroll: true });
  nodes[next].scrollIntoView({ block: 'nearest', inline: 'nearest' });
}

/** 全屏视频与遥控器控制分离；不依赖 ArtPlayer 的鼠标控制条或原生全屏 API。 */
export function TvWatchPlayer({ player, sourceName, sourceKey, vodId, episodes, currentIndex, loading, loadError, onEpisode, onExit }: TvWatchPlayerProps) {
  const store = useAppStore();
  const rootRef = useRef<HTMLDivElement>(null);
  const sheetRef = useRef<HTMLElement>(null);
  const controlsRef = useRef<HTMLDivElement>(null);
  const progressRef = useRef<HTMLDivElement>(null);
  const playRef = useRef<HTMLButtonElement>(null);
  const pendingFocus = useRef<'play' | 'progress' | null>(null);
  const [video, setVideo] = useState<HTMLVideoElement | null>(null);
  const [panel, setPanel] = useState<Panel>(null);
  const [hud, setHud] = useState(true);
  const [activity, setActivity] = useState(0);
  const [seekPosition, setSeekPosition] = useState<number | null>(null);
  const [fit, setFit] = useState<'contain' | 'cover'>('contain');
  const [reversed, setReversed] = useState(false);
  const [paused, setPaused] = useState(false);
  const [buffering, setBuffering] = useState(true);
  const [showBuffering, setShowBuffering] = useState(false);
  const [downloadSpeed, setDownloadSpeed] = useState(0);
  const [playbackError, setPlaybackError] = useState('');
  const [bufferAhead, setBufferAhead] = useState(0);
  const [position, setPosition] = useState(0);
  const [duration, setDuration] = useState(0);
  const [buffered, setBuffered] = useState(0);
  const [rate, setRate] = useState(1);
  const receiveVideo = useCallback((element: HTMLVideoElement | null) => setVideo(element), []);
  const openSources = useCallback(() => { setPanel('source'); setHud(true); }, []);

  // 原生按键回调不在 React 事件批次内：等控件挂载后再交还焦点。
  useEffect(() => {
    if (panel || !pendingFocus.current) return;
    const target = pendingFocus.current === 'play' ? playRef.current : progressRef.current;
    if (target) {
      target.focus({ preventScroll: true });
      pendingFocus.current = null;
    }
  });

  useEffect(() => {
    const htmlOverflow = document.documentElement.style.overflow;
    const bodyOverflow = document.body.style.overflow;
    document.documentElement.style.overflow = 'hidden';
    document.body.style.overflow = 'hidden';
    rootRef.current?.focus({ preventScroll: true });
    return () => {
      document.documentElement.style.overflow = htmlOverflow;
      document.body.style.overflow = bodyOverflow;
    };
  }, []);

  useEffect(() => {
    if (!video) return;
    let hasPlayed = false;
    setBuffering(true);
    const sync = () => {
      setPosition(Number.isFinite(video.currentTime) ? video.currentTime : 0);
      setDuration(Number.isFinite(video.duration) ? video.duration : 0);
      setPaused(video.paused);
      setRate(video.playbackRate);
      setBuffering(!video.error && !video.ended && video.readyState < 3 && (!video.paused || !hasPlayed));
      setBuffered(video.buffered.length ? video.buffered.end(video.buffered.length - 1) : 0);
      let ahead = 0;
      for (let i = 0; i < video.buffered.length; i++) {
        if (video.buffered.start(i) <= video.currentTime + 0.1 && video.buffered.end(i) > video.currentTime) {
          ahead = video.buffered.end(i) - video.currentTime;
          break;
        }
      }
      setBufferAhead(ahead);
    };
    const waiting = () => { if (!video.paused && !video.error) setBuffering(true); };
    const playing = () => { hasPlayed = true; sync(); setBuffering(false); };
    const events = ['timeupdate', 'durationchange', 'play', 'pause', 'progress', 'ratechange', 'loadedmetadata', 'ended', 'canplay', 'error', 'seeking', 'seeked'];
    events.forEach((event) => video.addEventListener(event, sync));
    video.addEventListener('waiting', waiting);
    video.addEventListener('playing', playing);
    sync();
    return () => {
      events.forEach((event) => video.removeEventListener(event, sync));
      video.removeEventListener('waiting', waiting);
      video.removeEventListener('playing', playing);
    };
  }, [video, player.url]);

  useEffect(() => {
    if (!buffering) { setShowBuffering(false); return; }
    const timer = setTimeout(() => setShowBuffering(true), 350);
    return () => clearTimeout(timer);
  }, [buffering, player.url]);

  useEffect(() => {
    if (!hud || panel || paused || seekPosition !== null) return;
    const timer = setTimeout(() => {
      setHud(false);
      rootRef.current?.focus({ preventScroll: true });
    }, 5000);
    return () => clearTimeout(timer);
  }, [hud, panel, paused, seekPosition, activity]);

  useEffect(() => {
    if (!panel || panel === 'source') return;
    const timer = requestAnimationFrame(() => {
      const selected = sheetRef.current?.querySelector<HTMLElement>('[aria-current="true"], [aria-pressed="true"]');
      const target = selected ?? sheetRef.current?.querySelector<HTMLElement>('button:not([disabled])');
      target?.focus({ preventScroll: true });
      target?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    });
    return () => cancelAnimationFrame(timer);
  }, [panel, episodes.length]);

  const closePanel = () => {
    setPanel(null);
    setHud(true);
    setActivity((value) => value + 1);
    pendingFocus.current = 'play';
  };

  const toggle = () => {
    if (!video) return;
    if (video.paused) void video.play().catch(() => {});
    else video.pause();
    setHud(true);
    setActivity((value) => value + 1);
    pendingFocus.current = 'play';
  };

  const chooseEpisode = (index: number) => {
    if (index !== currentIndex) onEpisode(index);
    setSeekPosition(null);
    setPosition(0);
    setPanel(null);
    setHud(true);
    setActivity((value) => value + 1);
    rootRef.current?.focus({ preventScroll: true });
  };

  useEffect(() => {
    const handle = (key: string) => {
      setActivity((value) => value + 1);
      if (key === 'Escape' || key === 'BrowserBack') {
        if (panel) closePanel();
        else if (seekPosition !== null) { setSeekPosition(null); setHud(false); rootRef.current?.focus(); }
        else if (hud) { setHud(false); rootRef.current?.focus(); }
        else onExit();
        return;
      }
      if (key === 'ContextMenu' || key === 'F2') { setPanel(panel === 'settings' ? null : 'settings'); setHud(true); return; }
      if (key === 'MediaPlayPause' || key === ' ') { toggle(); return; }
      if (panel) {
        const scope = panel === 'source' ? rootRef.current?.querySelector<HTMLElement>('[role="dialog"]') : sheetRef.current;
        if (DIRECTIONS.includes(key)) moveFocus(scope ?? null, key as TvDirection);
        else if (key === 'Enter') (document.activeElement as HTMLElement | null)?.click();
        return;
      }
      if (key === 'Enter') {
        if (seekPosition !== null && video) {
          video.currentTime = clampTvSeek(seekPosition, video.duration);
          setSeekPosition(null);
          setHud(true);
          rootRef.current?.focus({ preventScroll: true });
        } else if (hud && document.activeElement?.closest('[data-tv-controls]')) {
          (document.activeElement as HTMLElement).click();
        } else toggle();
        return;
      }
      if (key === 'ArrowLeft' || key === 'ArrowRight') {
        if (hud && document.activeElement?.closest('[data-tv-controls]') && seekPosition === null) {
          moveFocus(controlsRef.current, key);
        } else if (video && Number.isFinite(video.duration) && video.duration > 0) {
          setSeekPosition((previous) => clampTvSeek((previous ?? video.currentTime) + (key === 'ArrowLeft' ? -10 : 10), video.duration));
          setHud(true);
          pendingFocus.current = 'progress';
        }
        return;
      }
      if (key === 'ArrowDown') {
        setSeekPosition(null);
        setHud(true);
        pendingFocus.current = 'play';
      } else if (key === 'ArrowUp') {
        if (hud && document.activeElement?.closest('[data-tv-controls]')) progressRef.current?.focus({ preventScroll: true });
        else { setSeekPosition(null); setPanel('episodes'); setHud(true); }
      }
    };
    const onKeyboard = (event: KeyboardEvent) => {
      if (![...DIRECTIONS, 'Enter', 'Escape', 'BrowserBack', 'ContextMenu', 'F2', 'MediaPlayPause', ' '].includes(event.key)) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      if (event.repeat && ['Enter', ' ', 'MediaPlayPause', 'ContextMenu', 'F2'].includes(event.key)) return;
      handle(event.key);
    };
    const onRemote = (event: Event) => handle((event as CustomEvent<{ key: string }>).detail.key);
    window.addEventListener('keydown', onKeyboard, true);
    window.addEventListener('libretv-tv-key', onRemote);
    return () => {
      window.removeEventListener('keydown', onKeyboard, true);
      window.removeEventListener('libretv-tv-key', onRemote);
    };
  });

  const shownPosition = seekPosition ?? position;
  const percentage = duration ? Math.min(100, shownPosition / duration * 100) : 0;
  const order = episodes.map((_, index) => index);
  if (reversed) order.reverse();

  return (
    <div ref={rootRef} className={styles.screen} tabIndex={-1} data-tv-watch aria-label={`${player.title}，电视全屏播放`}>
      <div className={styles.media} data-fit={fit}>
        {player.url ? <PlayerShell {...player} tvMode onVideoReady={receiveVideo} onDownloadSpeed={setDownloadSpeed} onPlaybackError={setPlaybackError} onRequestSwitchSource={openSources} /> : (
          <div className={styles.loading} role="status">
            {loading && <Spinner size="lg" />}
            <span>{loading ? '正在获取播放地址…' : loadError ? '播放地址获取失败，按下键选择换源' : '暂无可用播放地址，按下键选择换源'}</span>
          </div>
        )}
      </div>
      <div className={styles.gesture} onClick={toggle} aria-hidden="true" />
      {player.url && showBuffering && !playbackError && !panel && seekPosition === null && (
        <div className={styles.buffering} role="status" data-tv-buffering>
          <div className={styles.bufferingTitle}><Spinner size="lg" /><span>正在缓冲</span></div>
          <strong className={styles.downloadSpeed} data-tv-download-speed>{formatDownloadSpeed(downloadSpeed)}</strong>
          <p>{downloadSpeed > 0 ? '视频下载速度' : '等待片源响应，暂未收到数据'}</p>
          <p className={styles.bufferDetail}>已缓冲 {bufferAhead.toFixed(1)} 秒 · 下键可打开控制栏换源</p>
        </div>
      )}
      {hud && !panel && <>
        <header className={styles.top}>
          <div className={styles.brand}>LIBRETV · TV</div>
          <h1>{player.title}</h1>
          <div className={styles.subtitle}>{sourceName}{episodes.length > 1 ? ` · 第 ${currentIndex + 1} / ${episodes.length} 集` : ' · 电影'} · {paused ? '已暂停' : buffering ? '缓冲中' : '正在播放'}</div>
        </header>
        <div className={styles.bottom}>
          <div className={styles.timeline}>
            <span className={styles.time}>{formatTime(shownPosition)}</span>
            <div ref={progressRef} className={styles.progress} tabIndex={0} role="slider" aria-label="播放进度，左右键调整，确认键跳转" aria-valuemin={0} aria-valuemax={Math.floor(duration)} aria-valuenow={Math.floor(shownPosition)}
              onClick={(event) => { if (!video || !duration) return; const rect = event.currentTarget.getBoundingClientRect(); video.currentTime = clampTvSeek((event.clientX - rect.left) / rect.width * duration, duration); setSeekPosition(null); }}>
              <div className={styles.track}><div className={styles.buffered} style={{ width: `${duration ? Math.min(100, buffered / duration * 100) : 0}%` }} /><div className={styles.elapsed} style={{ width: `${percentage}%` }} /></div>
              <div className={styles.thumb} style={{ left: `${percentage}%` }} />
            </div>
            <span className={styles.time}>{formatTime(duration)}</span>
          </div>
          <div ref={controlsRef} className={styles.controls} data-tv-controls>
            <button ref={playRef} className={styles.button} onClick={toggle}>{paused ? '▶ 继续播放' : 'Ⅱ 暂停'}</button>
            <button className={styles.button} disabled={currentIndex <= 0} onClick={() => chooseEpisode(currentIndex - 1)}>上一集</button>
            <button className={styles.button} disabled={!episodes.length || currentIndex >= episodes.length - 1} onClick={() => chooseEpisode(currentIndex + 1)}>下一集</button>
            <div className={styles.spacer} />
            <button className={styles.button} onClick={() => setPanel('episodes')}>选集</button>
            <button className={styles.button} onClick={openSources}>换源</button>
            <button className={styles.button} onClick={() => setPanel('settings')}>设置 · {rate}×</button>
          </div>
          <p className={styles.help}>确认播放 / 暂停 · ← → 快退 / 快进 · ↑ 选集 · ↓ 控制栏 · 菜单设置 · 返回收起</p>
        </div>
      </>}
      {seekPosition !== null && !panel && <div className={styles.seek} role="status"><strong>{formatTime(seekPosition)}</strong><span>← → 每次调整 10 秒 · 确认跳转 · 返回取消</span></div>}
      {panel && panel !== 'source' && <>
        <div className={styles.shade} onClick={closePanel} />
        <section ref={sheetRef} className={styles.sheet} role="dialog" aria-modal="true" aria-label={panel === 'episodes' ? '选集' : '播放设置'}>
          <div className={styles.sheetHeader}>
            <h2>{panel === 'episodes' ? '选集' : '播放设置'}</h2>
            <p>{panel === 'episodes' ? `${player.title} · 共 ${episodes.length} 集` : '方向键选择，确认键应用'}</p>
            {panel === 'episodes' && episodes.length > 1 && <button className={styles.button} onClick={() => setReversed((value) => !value)}>{reversed ? '切为正序' : '切为倒序'}</button>}
            <button className={styles.button} onClick={closePanel}>收起</button>
          </div>
          {panel === 'episodes' ? <div className={styles.episodes}>
            {order.length ? order.map((index) => <button key={index} className={styles.episode} aria-current={index === currentIndex ? 'true' : undefined} onClick={() => chooseEpisode(index)}>{index === currentIndex ? '▶ ' : ''}{episodes.length === 1 ? '正片' : `第 ${index + 1} 集`}</button>) : <p>暂无剧集，可在控制栏切换播放源</p>}
          </div> : <div className={styles.settings}>
            <div className={styles.settingRow}><span className={styles.settingLabel}>播放速度</span>{[0.75, 1, 1.25, 1.5, 2].map((value) => <button key={value} className={styles.button} aria-pressed={rate === value} onClick={() => { if (video) video.playbackRate = value; }}>{value === 1 ? '正常 1×' : `${value}×`}</button>)}</div>
            <div className={styles.settingRow}><span className={styles.settingLabel}>画面比例</span><button className={styles.button} aria-pressed={fit === 'contain'} onClick={() => setFit('contain')}>原始比例 · 完整显示</button><button className={styles.button} aria-pressed={fit === 'cover'} onClick={() => setFit('cover')}>铺满屏幕 · 裁切边缘</button></div>
            <div className={styles.settingRow}><span className={styles.settingLabel}>自动连播</span><button className={styles.button} aria-pressed={store.autoplayNext} onClick={() => store.updateSettings({ autoplayNext: !store.autoplayNext })}>{store.autoplayNext ? '已开启' : '已关闭'}</button><span className={styles.settingLabel}>广告过滤</span><button className={styles.button} aria-pressed={store.adFilter} onClick={() => store.updateSettings({ adFilter: !store.adFilter })}>{store.adFilter ? '已开启' : '已关闭'}</button></div>
          </div>}
          <p className={styles.sheetFooter}>方向键移动焦点 · 确认选择 · 返回继续观看</p>
        </section>
      </>}
      {panel === 'source' && <div className={styles.sourceModal}><SwitchSourceModal tvMode currentTitle={player.title} currentSourceKey={sourceKey} currentVodId={vodId} currentIndex={currentIndex} onClose={closePanel} /></div>}
    </div>
  );
}
