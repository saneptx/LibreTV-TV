/** Only confirmed server output may be described as enhanced or ad-filtered. */
export interface ProcessingInfo {
  sourceWidth: number;
  sourceHeight: number;
  outputWidth: number;
  outputHeight: number;
  superResolution: boolean;
  superResolutionEnabled?: boolean;
  model: string | null;
  adRemovalEnabled: boolean;
  adRemovalSupported: boolean;
  removedSegments: number;
  removedSeconds: number;
}
export interface ProcessingPresentation { video: string; ads: string }
export function parseProcessingInfo(value: unknown): ProcessingInfo | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const info = value as ProcessingInfo;
  if (![info.sourceWidth, info.sourceHeight, info.outputWidth, info.outputHeight].every(n => Number.isInteger(n) && n > 0 && n <= 8192) ||
    typeof info.superResolution !== 'boolean' || info.superResolutionEnabled !== undefined && typeof info.superResolutionEnabled !== 'boolean' ||
    info.superResolutionEnabled === false && info.superResolution || typeof info.adRemovalEnabled !== 'boolean' || typeof info.adRemovalSupported !== 'boolean' ||
    !Number.isInteger(info.removedSegments) || info.removedSegments < 0 || !Number.isFinite(info.removedSeconds) || info.removedSeconds < 0 ||
    info.model !== null && (typeof info.model !== 'string' || info.model.length > 64) ||
    info.superResolution && (!info.model || info.outputWidth <= info.sourceWidth || info.outputHeight <= info.sourceHeight) ||
    info.removedSegments > 0 && (!info.adRemovalEnabled || !info.adRemovalSupported)) return undefined;
  return info;
}
export function processingPresentation(info: ProcessingInfo): ProcessingPresentation {
  const model = info.model === 'fsrcnnx' ? 'FSRCNNX' : info.model;
  const output = `${info.outputWidth}×${info.outputHeight}${info.outputWidth >= 3840 ? info.outputHeight >= 2160 ? '（4K）' : '（4K 宽屏）' : ''}`;
  return {
    video: `原始 ${info.sourceWidth}×${info.sourceHeight} → 当前 ${output} · ${info.superResolution ? `已超分（${model}）` : info.superResolutionEnabled === false ? '超分已关闭（原始分辨率）' : '未超分（仅转码）'}`,
    ads: !info.adRemovalEnabled ? '去广告：已关闭' : !info.adRemovalSupported ? '去广告：此片源不支持分片过滤' : info.removedSegments > 0
      ? `已去广告：${info.removedSegments} 个分片 / ${Number(info.removedSeconds.toFixed(1))} 秒（仅分片广告）` : '去广告：未发现可识别广告分片',
  };
}
