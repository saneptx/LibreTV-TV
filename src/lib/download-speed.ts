/** 最近两秒内视频请求实际收到的字节数；空闲后归零，不沿用历史带宽估计。 */
export class DownloadSpeedMeter {
  private samples: { time: number; bytes: number }[] = [];
  private readonly startedAt: number;

  constructor(private readonly now = () => performance.now(), private readonly windowMs = 2000) {
    this.startedAt = now();
  }

  record(bytes: number): void {
    if (!Number.isFinite(bytes) || bytes <= 0) return;
    const time = this.now();
    this.samples.push({ time, bytes });
    this.prune(time);
  }

  private prune(time: number): void {
    this.samples = this.samples.filter((sample) => sample.time > time - this.windowMs);
  }

  bytesPerSecond(): number {
    const time = this.now();
    this.prune(time);
    const elapsed = Math.max(250, Math.min(this.windowMs, time - this.startedAt));
    return this.samples.reduce((sum, sample) => sum + sample.bytes, 0) * 1000 / elapsed;
  }

  /** progress/load 的 loaded 均为累计值；完成事件不能重复计数。缓存命中不创建 XHR。 */
  observe(xhr: XMLHttpRequest): void {
    let received = 0;
    const progress = (event: ProgressEvent) => {
      if (!Number.isFinite(event.loaded) || event.loaded <= received) return;
      this.record(event.loaded - received);
      received = event.loaded;
    };
    xhr.addEventListener('progress', progress);
    xhr.addEventListener('load', progress);
  }
}

export function formatDownloadSpeed(bytesPerSecond: number): string {
  if (!Number.isFinite(bytesPerSecond) || bytesPerSecond <= 0) return '0 KB/s';
  if (bytesPerSecond < 1024) return '< 1 KB/s';
  if (bytesPerSecond < 1024 * 1024) return `${Math.round(bytesPerSecond / 1024)} KB/s`;
  return `${(bytesPerSecond / (1024 * 1024)).toFixed(2)} MB/s`;
}
