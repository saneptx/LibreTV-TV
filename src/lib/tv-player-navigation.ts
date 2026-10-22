export type TvDirection = 'ArrowLeft' | 'ArrowRight' | 'ArrowUp' | 'ArrowDown';

interface FocusRect { left: number; top: number; width: number; height: number }

/** 按控件中心点选相应方向的邻居，兼容旧 WebView，无需浏览器空间导航。 */
export function nextTvFocus(rects: FocusRect[], current: number, direction: TvDirection): number {
  const from = rects[current];
  if (!from) return rects.length ? 0 : -1;
  const horizontal = direction === 'ArrowLeft' || direction === 'ArrowRight';
  let best = current;
  let score = Infinity;
  rects.forEach((rect, index) => {
    if (index === current) return;
    const dx = rect.left + rect.width / 2 - from.left - from.width / 2;
    const dy = rect.top + rect.height / 2 - from.top - from.height / 2;
    const valid = (direction === 'ArrowLeft' && dx < -8) || (direction === 'ArrowRight' && dx > 8)
      || (direction === 'ArrowUp' && dy < -8) || (direction === 'ArrowDown' && dy > 8);
    if (!valid) return;
    const distance = (horizontal ? Math.abs(dx) : Math.abs(dy)) + (horizontal ? Math.abs(dy) : Math.abs(dx)) * 2.4;
    if (distance < score) { best = index; score = distance; }
  });
  return best;
}

export function clampTvSeek(position: number, duration: number): number {
  if (!Number.isFinite(duration) || duration <= 0) return 0;
  return Math.max(0, Math.min(Number.isFinite(position) ? position : 0, Math.max(0, duration - 0.25)));
}
