import { describe, expect, it } from 'vitest';
import { DownloadSpeedMeter, formatDownloadSpeed } from './download-speed';

describe('视频实际下载速度', () => {
  it('累计并发请求字节，使用滚动窗口并在没有数据时归零', () => {
    let now = 0;
    const meter = new DownloadSpeedMeter(() => now);
    now = 500;
    meter.record(1024 * 100);
    meter.record(1024 * 50);
    now = 1000;
    expect(meter.bytesPerSecond()).toBe(1024 * 150);
    now = 2000;
    expect(meter.bytesPerSecond()).toBe(1024 * 75);
    now = 2500;
    expect(meter.bytesPerSecond()).toBe(0);
  });

  it('XHR 进度按增量计数，load 不重复统计已报告字节', () => {
    let now = 0;
    const meter = new DownloadSpeedMeter(() => now);
    const listeners: Record<string, (event: ProgressEvent) => void> = {};
    meter.observe({ addEventListener: (type: string, handler: (event: ProgressEvent) => void) => { listeners[type] = handler; } } as XMLHttpRequest);
    now = 500;
    listeners.progress({ loaded: 1000 } as ProgressEvent);
    listeners.progress({ loaded: 2500 } as ProgressEvent);
    listeners.load({ loaded: 3000 } as ProgressEvent);
    now = 1000;
    expect(meter.bytesPerSecond()).toBe(3000);
  });

  it('新请求重试的计数互不影响，未收到数据不伪造网速', () => {
    let now = 0;
    const meter = new DownloadSpeedMeter(() => now);
    expect(meter.bytesPerSecond()).toBe(0);
    meter.record(NaN);
    meter.record(-1);
    now = 1000;
    expect(meter.bytesPerSecond()).toBe(0);
    meter.record(1024);
    expect(meter.bytesPerSecond()).toBe(1024);
    const replacement = new DownloadSpeedMeter(() => now);
    expect(replacement.bytesPerSecond()).toBe(0);
  });

  it('以 KB/s、MB/s 显示接收速率', () => {
    expect(formatDownloadSpeed(0)).toBe('0 KB/s');
    expect(formatDownloadSpeed(100)).toBe('< 1 KB/s');
    expect(formatDownloadSpeed(512 * 1024)).toBe('512 KB/s');
    expect(formatDownloadSpeed(1.25 * 1024 * 1024)).toBe('1.25 MB/s');
    expect(formatDownloadSpeed(Infinity)).toBe('0 KB/s');
  });
});
