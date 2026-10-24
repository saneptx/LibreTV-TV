import { describe, expect, it } from 'vitest';
import { parseProcessingInfo, processingPresentation, type ProcessingInfo } from './playback-processing';
const base: ProcessingInfo = { sourceWidth:1280,sourceHeight:720,outputWidth:1920,outputHeight:1080,superResolution:true,model:'fsrcnnx',adRemovalEnabled:true,adRemovalSupported:true,removedSegments:0,removedSeconds:0 };
describe('播放处理标注',()=>{
  it('区分原始尺寸、超分输出与实际删除的广告',()=>{
    const result=processingPresentation({...base,removedSegments:3,removedSeconds:12.34});
    expect(result.video).toBe('原始 1280×720 → 当前 1920×1080 · 已超分（FSRCNNX）');
    expect(result.ads).toBe('已去广告：3 个分片 / 12.3 秒（仅分片广告）');
  });
  it('本来就为1080P的视频仅转码，不标注已超分或已去广告',()=>{
    const result=processingPresentation({...base,sourceWidth:1920,sourceHeight:1080,superResolution:false,model:null});
    expect(result.video).toContain('原始 1920×1080 → 当前 1920×1080 · 未超分（仅转码）');
    expect(result.ads).toBe('去广告：未发现可识别广告分片');
  });
  it('区分去广告关闭与MP4不支持分片过滤',()=>{
    expect(processingPresentation({...base,adRemovalEnabled:false}).ads).toBe('去广告：已关闭');
    expect(processingPresentation({...base,adRemovalSupported:false}).ads).toBe('去广告：此片源不支持分片过滤');
  });
  it('4K是输出尺寸，仍保留原始分辨率',()=>{
    expect(processingPresentation({...base,outputWidth:3840,outputHeight:2160}).video).toContain('原始 1280×720 → 当前 3840×2160（4K） · 已超分');
  });
  it('裁边1080P升至4K宽屏时标清真实像素尺寸',()=>{
    expect(processingPresentation({...base,sourceWidth:1920,sourceHeight:800,outputWidth:3840,outputHeight:1600}).video).toBe('原始 1920×800 → 当前 3840×1600（4K 宽屏） · 已超分（FSRCNNX）');
  });
  it('缺少或矛盾的处理结果不能生成成功标注',()=>{
    expect(parseProcessingInfo(undefined)).toBeUndefined();
    expect(parseProcessingInfo({})).toBeUndefined();
    expect(parseProcessingInfo({...base,outputWidth:1280,outputHeight:720})).toBeUndefined();
    expect(parseProcessingInfo({...base,removedSegments:1,adRemovalEnabled:false})).toBeUndefined();
    expect(parseProcessingInfo({...base,sourceWidth:NaN})).toBeUndefined();
    expect(parseProcessingInfo(base)).toEqual(base);
  });
});
