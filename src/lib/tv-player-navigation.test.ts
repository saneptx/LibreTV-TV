import { describe, expect, it } from 'vitest';
import { clampTvSeek, nextTvFocus } from './tv-player-navigation';

describe('电视播放页遥控器导航', () => {
  const grid = Array.from({ length: 12 }, (_, i) => ({ left: (i % 4) * 120, top: Math.floor(i / 4) * 70, width: 100, height: 50 }));
  it('按左右选择同一行，按上下保留列位置', () => {
    expect(nextTvFocus(grid, 5, 'ArrowRight')).toBe(6);
    expect(nextTvFocus(grid, 5, 'ArrowLeft')).toBe(4);
    expect(nextTvFocus(grid, 5, 'ArrowDown')).toBe(9);
    expect(nextTvFocus(grid, 5, 'ArrowUp')).toBe(1);
  });
  it('边界不绕到另一端，没有焦点时落到第一个控件', () => {
    expect(nextTvFocus(grid, 0, 'ArrowLeft')).toBe(0);
    expect(nextTvFocus(grid, 11, 'ArrowDown')).toBe(11);
    expect(nextTvFocus(grid, -1, 'ArrowDown')).toBe(0);
    expect(nextTvFocus([], -1, 'ArrowDown')).toBe(-1);
  });
  it('快进快退不会越过影片边界，直播/未知时长不能预览跳转', () => {
    expect(clampTvSeek(-10, 120)).toBe(0);
    expect(clampTvSeek(200, 120)).toBe(119.75);
    expect(clampTvSeek(45, 120)).toBe(45);
    expect(clampTvSeek(20, Infinity)).toBe(0);
    expect(clampTvSeek(20, NaN)).toBe(0);
  });
});
