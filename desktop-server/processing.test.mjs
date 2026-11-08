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
async function fixture(t,{download,encode,prefetch=4,startupBufferSeconds=0,cleanup}={}){
 const root=await mkdtemp(join(tmpdir(),'libretv-pipeline-')),calls=[];
 const metadata={streams:[{codec_type:'video',width:1280,height:720,avg_frame_rate:'24/1',duration:'4',start_time:'0'},{codec_type:'audio',duration:'4',start_time:'0'}],format:{duration:'48'}};
 const tools={ffmpeg:'ffmpeg-test',ffprobe:'ffprobe-test',env:{},engine:'libplacebo-vulkan',model:'fsrcnnx',modelSignature:'fixture',encoder:'h264_nvenc',chunkSeconds:4,targetHeight:720};
 const processor=createProcessor(tools,{cacheDir:root,prefetch,startupBufferSeconds,runner:async(executable,args,options)=>{
   if(executable===tools.ffprobe)return {stdout:JSON.stringify(metadata)};
   const file=args.at(-1),index=+dirname(file).split('/').at(-1);calls.push({type:'encode',index});
   await encode?.(index,options.signal);if(options.signal.aborted)throw new DOMException('取消','AbortError');
   await writeFile(file,'encoded');return {stdout:''};
 }});
 const network={open:async(url,range,signal)=>{
  assert(signal,'取流必须关联取消信号');if(signal.aborted)throw new DOMException('取消','AbortError');
  if(url.endsWith('.m3u8'))return {...response('#EXTM3U\n#EXT-X-TARGETDURATION:4\n'+Array.from({length:12},(_,i)=>`#EXTINF:4,\nhttps://fixture.test/${i}.ts\n`).join('')+'#EXT-X-ENDLIST\n'),url};
  const index=+new URL(url).pathname.slice(1,-3);calls.push({type:'download',index});await download?.(index,signal);
  if(signal.aborted)throw new DOMException('取消','AbortError');return {...response('source-'+index),url};
 }};
 const session=processor.create({id:'pipeline-test',headers:{}},{url:'https://fixture.test/film.m3u8'},network);
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
