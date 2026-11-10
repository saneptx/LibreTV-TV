import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseMaster, parseMedia, sliceMedia, targetSize, repeatedInsertLines } from './hls.mjs';
import { compactRemovals, cleanPosition, processedPlaylist, readPlaylist } from './processing.mjs';
import { Readable } from 'node:stream';
const list=(lines)=>'#EXTM3U\n#EXT-X-TARGETDURATION:4\n'+lines.join('\n')+'\n#EXT-X-ENDLIST\n';
test('清单拒绝访问与失效错误保留源站 HTTP 状态，不误报电脑连接失败',async()=>{
 for(const [status,reason] of [[403,'片源拒绝访问'],[404,'片源地址已失效'],[429,'片源请求过于频繁'],[503,'电脑读取视频清单失败']]) {
  const response=Readable.from(['private upstream body']);response.statusCode=status;
  await assert.rejects(readPlaylist(response),error=>error.status===502 && error.message.includes(reason) && error.message.includes(`HTTP ${status}`) && !error.message.includes('private upstream body'));
  assert(response.destroyed);
 }
 const response=Readable.from([Buffer.from('#EXTM3U\n')]);response.statusCode=200;assert.equal(await readPlaylist(response),'#EXTM3U\n');
});
test('只删除明确 CUE/广告地址片段，不按长度猜测正片',()=>{
 const body=list(['#EXTINF:4,','film0.ts','#EXT-X-DISCONTINUITY','#EXTINF:1,','short-scene.ts','#EXT-X-CUE-OUT:2','#EXTINF:2,','commercial.ts','#EXT-X-CUE-IN','#EXTINF:4,','film1.ts','#EXTINF:2,','ads/clip.ts']);
 const filtered=parseMedia(body,'https://video.test/a/index.m3u8');
 assert.equal(filtered.duration,9);assert.equal(filtered.removedSeconds,4);assert.equal(filtered.removed.length,2);
 assert(filtered.segments.some(s=>s.url.endsWith('short-scene.ts')));
 assert.equal(parseMedia(body,'https://video.test/a/index.m3u8',{adFilter:false}).duration,13);
 assert.deepEqual(sliceMedia(filtered,5,2).segments.map(s=>s.url),['https://video.test/a/film1.ts']);
});
test('广告删除后仍使用原始媒体序号推导 AES IV，保留 MAP 和 Range',()=>{
 const source=list(['#EXT-X-MEDIA-SEQUENCE:100','#EXT-X-KEY:METHOD=AES-128,URI="key.bin"','#EXTINF:2,','first.ts','#EXT-X-CUE-OUT:2','#EXTINF:2,','ad.ts','#EXT-X-CUE-IN','#EXTINF:2,','last.ts']);
 const parsed=parseMedia(source,'https://v.test/index.m3u8');
 assert.equal(parsed.segments[1].sequence,102);assert.equal(parsed.segments[1].key.iv,'0x'+(102).toString(16).padStart(32,'0'));
 const range=parseMedia(list(['#EXT-X-MAP:URI="init.mp4",BYTERANGE="10@0"','#EXTINF:2,','#EXT-X-BYTERANGE:20@10','media.mp4','#EXTINF:2,','#EXT-X-BYTERANGE:20','media.mp4']),'https://v.test/a.m3u8');
 assert.deepEqual(range.segments[1].range,{url:'https://v.test/media.mp4',start:30,end:49});assert.equal(range.segments[0].map.range.end,9);
});
test('主列表保留清晰度排序与独立音轨，拒绝直播/DRM/无效 Range',()=>{
 const variants=parseMaster('#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",DEFAULT=YES,URI="audio.m3u8"\n#EXT-X-STREAM-INF:BANDWIDTH=100,RESOLUTION=640x360,AUDIO="a"\n360.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=200,RESOLUTION=1280x720\n720.m3u8','https://v.test/a/');
 assert.equal(variants[0].height,720);assert.equal(variants[1].audio,'https://v.test/a/audio.m3u8');
 assert.throws(()=>parseMedia('#EXTM3U\n#EXTINF:2,\na.ts','https://v.test/'),/直播/);
 assert.throws(()=>parseMedia(list(['#EXT-X-KEY:METHOD=SAMPLE-AES,URI="key"','#EXTINF:2,','a.ts']),'https://v.test/'),/DRM/);
 assert.throws(()=>parseMedia(list(['#EXTINF:2,','#EXT-X-BYTERANGE:10','a.ts']),'https://v.test/'),/偏移/);
});
test('完整 VOD 时间轴可任意快进，保留宽高比并区分真正超分与转码',()=>{
 assert.deepEqual(targetSize(1280,720),{width:1920,height:1080,superResolution:true});
 assert.deepEqual(targetSize(1920,800,1080),{width:1920,height:800,superResolution:false});
 assert.deepEqual(targetSize(1920,1080,2160),{width:3840,height:2160,superResolution:true});
 assert.deepEqual(targetSize(1920,800,2160),{width:3840,height:1600,superResolution:true});
 const body=processedPlaylist(9,4,'/processed');assert(body.includes('#EXTINF:1.000000,\n/processed/2.ts'));assert(body.endsWith('#EXT-X-ENDLIST\n'));
 const removed=compactRemovals([{start:2,duration:1},{start:3,duration:1},{start:8,duration:2}]);assert.deepEqual(removed,[{start:2,duration:2},{start:8,duration:2}]);assert.equal(cleanPosition(9,removed),6);
});
test('默认只提高一个档位，宽屏按原始画幅归档，4K不再放大',()=>{
 for(const [width,height,outputWidth,outputHeight] of [[640,360,854,480],[854,480,1280,720],[1024,576,1280,720],[1280,720,1920,1080],
   [1280,544,1920,816],[1366,768,1920,1080],[1920,1080,3840,2160],[1920,800,3840,1600],[1888,1056,3840,2148],[2560,1440,3840,2160]]) {
  const size=targetSize(width,height);assert.equal(size.width,outputWidth,`${width}x${height}`);assert.equal(size.height,outputHeight,`${width}x${height}`);assert(size.superResolution);
 }
 assert.deepEqual(targetSize(3840,2160),{width:3840,height:2160,superResolution:false});
 assert.deepEqual(targetSize(3840,1600),{width:3840,height:1600,superResolution:false});
 assert.deepEqual(targetSize(4096,2160),{width:4096,height:2160,superResolution:false});
 assert.deepEqual(targetSize(1280,720,2160),{width:3840,height:2160,superResolution:true});
});

test('关闭超分保留原始尺寸，裁边宽屏也不缩小',()=>{
 for(const [width,height] of [[1280,720],[1920,1080],[1920,800],[3840,1600]]) assert.deepEqual(targetSize(width,height,'auto',false),{width,height,superResolution:false});
});
test('设备标记的广告分片按原文行删除，并计入时间轴',()=>{
 const body=list(['#EXTINF:4,','film0.ts','#EXT-X-DISCONTINUITY','#EXTINF:3,','https://ad.test/x/0.ts','#EXTINF:3,','https://ad.test/x/1.ts','#EXT-X-DISCONTINUITY','#EXTINF:4,','film1.ts']);
 const parsed=parseMedia(body,'https://video.test/a/index.m3u8',{adSegments:['https://ad.test/x/0.ts','https://ad.test/x/1.ts']});
 assert.deepEqual(parsed.segments.map(s=>s.url),['https://video.test/a/film0.ts','https://video.test/a/film1.ts']);
 assert.deepEqual(parsed.removed.map(r=>[r.start,r.duration,r.reason]),[[4,3,'device'],[7,3,'device']]);
 assert.equal(parseMedia(body,'https://video.test/a/index.m3u8',{adFilter:false,adSegments:['https://ad.test/x/0.ts']}).removed.length,0);
});
test('电脑端重复插入规则：同一非正片目录、时长相同的短段组出现两次以上',()=>{
 const film=(n,from)=>Array.from({length:n},(_,i)=>`#EXTINF:10,\n/film/${from+i}.ts`).join('\n');
 const ad=(i)=>`#EXT-X-DISCONTINUITY\n#EXTINF:8.8,\nhttps://ads.test/x/${i}a.ts\n#EXTINF:8.8,\nhttps://ads.test/x/${i}b.ts\n#EXT-X-DISCONTINUITY`;
 const body=list([film(30,0),ad(1),film(30,30),ad(2),film(30,60)]);
 assert.deepEqual(repeatedInsertLines(body,'https://v.test/a.m3u8'),['https://ads.test/x/1a.ts','https://ads.test/x/1b.ts','https://ads.test/x/2a.ts','https://ads.test/x/2b.ts']);
 assert(Math.abs(parseMedia(body,'https://v.test/a.m3u8',{repeatFilter:true}).removedSeconds-35.2)<1e-6);
 assert.deepEqual(repeatedInsertLines(list([film(30,0),ad(1),film(30,30)]),'https://v.test/a.m3u8'),[]);
});
