export interface SourceOrderValue { height?: number; ok?: boolean; ms?: number }
/** Native source resolution only; unknown values never outrank a measured source. */
export function compareSourceResolution(a: SourceOrderValue, b: SourceOrderValue): number {
  const height = (value?: number) => Number.isFinite(value) && value! > 0 ? value! : 0;
  const latency = (value?: number) => Number.isFinite(value) && value! >= 0 ? value! : Number.MAX_SAFE_INTEGER;
  return Number(a.ok === false) - Number(b.ok === false) || height(b.height) - height(a.height) || latency(a.ms) - latency(b.ms);
}
