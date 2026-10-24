import { getNativeHttpBridge } from './native-http';
/** 两种播放引擎共用的控制面；时间统一为秒。 */
export interface PlaybackMedia extends EventTarget {
  currentTime: number;
  readonly duration: number;
  readonly paused: boolean;
  readonly ended: boolean;
  readonly readyState: number;
  readonly error: { message: string } | null;
  readonly buffered: TimeRanges;
  playbackRate: number;
  play(): Promise<void>;
  pause(): void;
  setFit?: (fit: 'contain' | 'cover') => void;
}
export interface NativePlaybackRequest {
  url: string;
  proxyUrl?: string;
  headers: Record<string, string>;
  position: number;
  adFilter: boolean;
}
export interface NativePlaybackState {
  sessionId: string;
  position: number;
  duration: number;
  bufferedPosition: number;
  bufferedDuration: number;
  paused: boolean;
  buffering: boolean;
  ended: boolean;
  ready: boolean;
  rate: number;
  height: number;
  bytesPerSecond: number;
  error: string;
  rendered: boolean;
  width?: number;
  route?: 'direct' | 'proxy' | 'desktop';
  fit?: 'contain' | 'cover';
}
export interface NativePlayerBridge { postMessage(message: string): void }
declare global { interface Window { LibreTvNativePlayer?: NativePlayerBridge } }

export function getNativePlayerBridge(): NativePlayerBridge | null {
  return typeof window !== 'undefined' && typeof window.LibreTvNativePlayer?.postMessage === 'function'
    ? window.LibreTvNativePlayer : null;
}
export function buildNativePlaybackRequest(url: string, base: string, position: number, adFilter: boolean): NativePlaybackRequest {
  const target = new URL(url, base);
  if (!['http:', 'https:'].includes(target.protocol)) throw new Error('不支持的播放地址');
  const origin = new URL(base).origin;
  const alreadyProxied = target.origin === origin && target.pathname.startsWith('/api/proxy');
  return { url: target.href,
    proxyUrl: alreadyProxied || getNativeHttpBridge() ? undefined : `${origin}/api/proxy?url=${encodeURIComponent(target.href)}`,
    headers: {}, position: Number.isFinite(position) ? Math.max(0, position) : 0, adFilter };
}
/** sessionId 隔离换集后的迟到回调。 */
export class NativePlaybackMedia extends EventTarget implements PlaybackMedia {
  state: NativePlaybackState;
  fit: 'contain' | 'cover' = 'contain';
  constructor(readonly sessionId: string, private readonly bridge: NativePlayerBridge) {
    super();
    this.state = { sessionId, position: 0, duration: 0, bufferedPosition: 0, bufferedDuration: 0, paused: false,
      buffering: true, ended: false, ready: false, rate: 1, height: 0, bytesPerSecond: 0, error: '', rendered: false };
  }
  command(type: string, payload: Record<string, unknown> = {}): void {
    this.bridge.postMessage(JSON.stringify({ version: 1, sessionId: this.sessionId, type, ...payload }));
  }
  get currentTime() { return this.state.position; }
  set currentTime(position: number) { if (Number.isFinite(position)) this.command('seek', { position: Math.max(0, position) }); }
  get duration() { return this.state.duration; }
  get paused() { return this.state.paused; }
  get ended() { return this.state.ended; }
  get readyState() { return this.state.error ? 0 : this.state.ready ? 4 : this.state.duration > 0 ? 2 : 0; }
  get error() { return this.state.error ? { message: this.state.error } : null; }
  get playbackRate() { return this.state.rate; }
  set playbackRate(rate: number) { if (Number.isFinite(rate)) this.command('rate', { rate }); }
  get buffered(): TimeRanges {
    const end = this.state.bufferedPosition, start = Math.max(0, end - this.state.bufferedDuration);
    const length = end > start ? 1 : 0;
    const value = (index: number, time: number) => {
      if (index !== 0 || !length) throw new DOMException('No buffered range', 'IndexSizeError');
      return time;
    };
    return { length, start: (i) => value(i, start), end: (i) => value(i, end) };
  }
  async play(): Promise<void> { this.command('play'); }
  pause(): void { this.command('pause'); }
  setFit(fit: 'contain' | 'cover'): void { this.fit = fit; this.command('fit', { fit }); }
  update(next: NativePlaybackState): boolean {
    if (next.sessionId !== this.sessionId) return false;
    const before = this.state;
    this.state = next;
    if (next.duration !== before.duration) this.dispatchEvent(new Event('durationchange'));
    if (next.paused !== before.paused) this.dispatchEvent(new Event(next.paused ? 'pause' : 'play'));
    if (next.rate !== before.rate) this.dispatchEvent(new Event('ratechange'));
    this.dispatchEvent(new Event('timeupdate'));
    this.dispatchEvent(new Event('progress'));
    if (next.buffering && !before.buffering) this.dispatchEvent(new Event('waiting'));
    if (next.ready && (!before.ready || before.paused && !next.paused)) this.dispatchEvent(new Event(next.paused ? 'canplay' : 'playing'));
    if (next.error && next.error !== before.error) this.dispatchEvent(new Event('error'));
    if (next.ended && !before.ended) this.dispatchEvent(new Event('ended'));
    return true;
  }
}
