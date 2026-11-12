import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,rm,readdir} from 'node:fs/promises';
import {join,dirname} from 'node:path';
import {tmpdir} from 'node:os';
import {Readable} from 'node:stream';
import {createProcessor} from './processing.mjs';
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};
const until=async(p)=>{let timer;try{return await Promise.race([p,new Promise((_,j)=>timer=setTimeout(()=>j(Error('等待流水线事件超时')),3000))]);}finally{clearTimeout(timer);}};
function response(body){const r=Readable.from([Buffer.from(body)]);r.statusCode=200;r.headers={'content-length':Buffer.byteLength(body),'content-type':'application/octet-stream'};return {response:r};}
async function fixture(t,{download,encode,prefetch=4,startupBufferSeconds=0,cleanup,input={},frames}={}){
 const root=await mkdtemp(join(tmpdir(),'libretv-pipeline-')),calls=[];
 const metadata={streams:[{codec_type:'video',codec_name:'h264',width:1280,height:720,avg_frame_rate:'24/1',duration:'4',start_time:'0'},{codec_type:'audio',duration:'4',start_time:'0'}],format:{duration:'48'}};
 const tools={ffmpeg:'ffmpeg-test',ffprobe:'ffprobe-test',env:{},engine:'libplacebo-vulkan',model:'fsrcnnx',modelSignature:'fixture',encoder:'h264_nvenc',chunkSeconds:4,targetHeight:720};
 const processor=createProcessor(tools,{cacheDir:root,prefetch,startupBufferSeconds,runner:async(executable,args,options)=>{
   if(executable===tools.ffprobe)return {stdout:JSON.stringify(metadata)};
   if(args.includes('rawvideo'))return {stdout:frames?frames():Buffer.alloc(0)};
   const file=args.at(-1),index=+dirname(file).split('/').at(-1);calls.push({type:'encode',index,vf:args[args.indexOf('-vf')+1]});
   await encode?.(index,options.signal);if(options.signal.aborted)throw new DOMException('取消','AbortError');
   await writeFile(file,'encoded');return {stdout:''};
 }});
 const network={open:async(url,range,signal)=>{
  assert(signal,'取流必须关联取消信号');if(signal.aborted)throw new DOMException('取消','AbortError');
  if(url.endsWith('.m3u8')){calls.push({type:'playlist',url});return {...response('#EXTM3U\n#EXT-X-TARGETDURATION:4\n'+Array.from({length:12},(_,i)=>`#EXTINF:4,\nhttps://fixture.test/${i}.ts\n`).join('')+'#EXT-X-ENDLIST\n'),url};}
  const index=+new URL(url).pathname.slice(1,-3);calls.push({type:'download',index});await download?.(index,signal);
  if(signal.aborted)throw new DOMException('取消','AbortError');return {...response('source-'+index),url};
 }};
 const session=processor.create({id:'pipeline-test',headers:{}},{url:'https://fixture.test/film.m3u8',overlayRemoval:false,...input},network);
 t.after(async()=>{cleanup?.();session.close();await Promise.allSettled([...session.jobs.values()].map(j=>j.promise));await session.preparing;await rm(root,{recursive:true,force:true});});
 return {session,processor,calls,root};
}
test('下一段的片源下载与当前段编码重叠，同时GPU编码仍严格串行',async t=>{
 const encoding=deferred(),downloaded=deferred(),release=deferred();let active=0,max=0;
 const {session,calls}=await fixture(t,{cleanup:()=>{encoding.resolve();downloaded.resolve();release.resolve();},startupBufferSeconds:8,download:async index=>{if(index===2){await encoding.promise;downloaded.resolve();}},encode:async index=>{active++;max=Math.max(active,max);try{if(index===0){encoding.resolve();await until(downloaded.promise);await release.promise;}}finally{active--;}}});
 await until(downloaded.promise);
 assert(calls.some(c=>c.type==='download'&&c.index===2));assert(!calls.some(c=>c.type==='encode'&&c.index===1));
 release.resolve();await session.preparing;assert.equal(session.info.state,'ready');await session.serve(2);assert.equal(max,1);
 const status=session.status();assert(status.sourcePreparationSeconds>=0);assert(status.encodeSeconds>=0);assert(status.validationSeconds>=0);
 assert(status.processingSeconds>0);assert(status.speed>0);
});
test('快进取消旧的在途取流和排队预取，目标分片优先处理',async t=>{
 const waiting=deferred(),cancelled=deferred();
 const {session,calls}=await fixture(t,{download:async(index,signal)=>{if(index===2){waiting.resolve();await new Promise((r,j)=>{signal.addEventListener('abort',()=>{cancelled.resolve();j(new DOMException('取消','AbortError'));},{once:true});});}}});
 await session.preparing;await session.serve(0);await until(waiting.promise);const seek=session.serve(8);await until(cancelled.promise);await until(seek);
 assert(calls.some(c=>c.type==='encode'&&c.index===8));assert(!calls.some(c=>c.type==='download'&&(c.index===3||c.index===4)));
});
test('关闭任务取消下载与编码队列，清理临时目录且不再启动预取',async t=>{
 const waiting=deferred();let aborted=false;
 const {session,processor,root}=await fixture(t,{download:async(index,signal)=>{if(index===2){waiting.resolve();await new Promise((r,j)=>signal.addEventListener('abort',()=>{aborted=true;j(new DOMException('取消','AbortError'));},{once:true}));}}});
 await session.preparing;await until(waiting.promise);session.close();await Promise.allSettled([...session.jobs.values()].map(j=>j.promise));
 assert(aborted);assert.equal(processor.sourceQueue.pending.length,0);assert.equal(processor.queue.pending.length,0);assert.equal(session.activeWork,0);
 assert.deepEqual(await readdir(join(root,'work','pipeline-test')),[]);
});
test('使用设备分析过的媒体列表原文，设备标记的广告分片按原文删除',async t=>{
 const text='#EXTM3U\n#EXT-X-TARGETDURATION:4\n'+Array.from({length:6},(_,i)=>`#EXTINF:4,\nhttps://fixture.test/${i}.ts\n`).join('')+'#EXT-X-DISCONTINUITY\n#EXTINF:4,\nhttps://ads.test/spot.ts\n#EXT-X-DISCONTINUITY\n'+Array.from({length:6},(_,i)=>`#EXTINF:4,\nhttps://fixture.test/${i+6}.ts\n`).join('')+'#EXT-X-ENDLIST\n';
 const {session,calls}=await fixture(t,{input:{playlists:[{url:'https://fixture.test/film.m3u8',text}],adSegments:['https://ads.test/spot.ts']}});
 await session.preparing;assert.equal(session.info.state,'ready');
 assert(!calls.some(c=>c.type==='playlist'),'不应再次下载媒体列表');
 assert.deepEqual(session.timeMap,[{start:24,duration:4}]);assert.equal(session.duration,48);assert(session.usedDevicePlaylist);
});
test('抽帧识别出固定水印后，编码滤镜在缩放前加入 delogo，并标注处理结果',async t=>{
 // 320×180 分析图：每个场景为不同的平滑背景，右上角始终有同一个台标
 let n=0;const frames=()=>{const w=320,h=180,buf=Buffer.alloc(2*w*h);n++;const base=(n*53)%160,sx=((n*7)%5-2)*.3,sy=((n*3)%5-2)*.3;for(let i=0;i<buf.length;i++){const p=i%(w*h),x=p%w,y=(p-x)/w;buf[i]=Math.max(0,Math.min(255,Math.round(base+40+x*sx+y*sy)));}
  for(const f of [0,1])for(let y=10;y<30;y++)for(let xx=270;xx<305;xx++)buf[f*w*h+y*w+xx]=(y===10||y===29||xx===270||xx===304||(xx-270)%6<2)?255:0;return buf;};
 const {session,calls}=await fixture(t,{input:{overlayRemoval:true},frames});
 await session.preparing;assert.equal(session.info.state,'ready');
 const regions=session.info.processingInfo.overlays;assert.equal(regions.length,1,JSON.stringify(regions));
 assert.equal(regions[0].kind,'logo');const r=regions[0];assert(r.x<=1080&&r.x+r.w>=1220&&r.y<=40&&r.y+r.h>=116&&r.w*r.h<=260*160,JSON.stringify(r));
 const vf=calls.find(c=>c.type==='encode').vf;assert.match(vf,/setpts=PTS-STARTPTS[^,]*,delogo=x=\d+:y=\d+:w=\d+:h=\d+,(fps|format)=/);
 assert.match(session.label,/去水印 1 处/);
});
test('设备给的列表分片在电脑上被拒（按 IP 签名）时，改用电脑自己下载的列表重新准备',async t=>{
 const text='#EXTM3U\n#EXT-X-TARGETDURATION:4\n'+Array.from({length:12},(_,i)=>`#EXTINF:4,\nhttps://fixture.test/${i}.ts?sign=device\n`).join('')+'#EXT-X-ENDLIST\n';
 const {session,calls}=await fixture(t,{input:{playlists:[{url:'https://fixture.test/film.m3u8',text}]},download:async()=>{}});
 const original=session.network.open;
 session.network.open=async(url,range,signal)=>{ if(url.includes('sign=device')){const r=Readable.from([]);r.statusCode=403;r.headers={};return {response:r,url};} return original(url,range,signal); };
 await session.preparing;assert.equal(session.info.state,'ready',session.info.error);
 assert(session.info.devicePlaylistRejected);assert(calls.some(c=>c.type==='playlist'),'应重新下载媒体列表');
});
