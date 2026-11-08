// Controlled real-GPU comparison: same 1080P/29.97 source, model and encoder.
// Serial control uses one queue for both stages; source latency is explicitly injected.
import assert from 'node:assert/strict';
import http from 'node:http';
import {createReadStream} from 'node:fs';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {tmpdir} from 'node:os';
import {toolConfig,run} from './runtime.mjs';
import {createProcessor} from './processing.mjs';
const dir=await mkdtemp(join(tmpdir(),'libretv-pipeline-gpu-')),tools=await toolConfig();
const sourceDelayMs=1000,results=[];let upstream,current,pipeline;
try {
 await run(tools.ffmpeg,['-hide_banner','-loglevel','error','-threads','2','-f','lavfi','-i','testsrc2=s=1920x1080:r=30000/1001:d=24.024','-f','lavfi','-i','sine=frequency=880:sample_rate=48000:duration=24.024','-map','0:v','-map','1:a','-c:v','libx264','-preset','ultrafast','-crf','26','-pix_fmt','yuv420p','-g','120','-sc_threshold','0','-c:a','aac','-f','hls','-hls_time','4.004','-hls_list_size','0','-hls_segment_filename',join(dir,'%d.ts'),'-y',join(dir,'film.m3u8')],{env:tools.env});
 upstream=http.createServer((req,res)=>{
  if(!/^\/(film\.m3u8|\d+\.ts)$/.test(req.url)){res.writeHead(404);res.end();return;}
  const movie=req.url.endsWith('.ts');res.setHeader('Content-Type',movie?'video/mp2t':'application/vnd.apple.mpegurl');
  const timer=setTimeout(()=>{if(res.destroyed)return;if(movie&&current){current.downloads++;if(current.encoding)current.downloadsDuringEncoding++;}createReadStream(join(dir,req.url.slice(1))).on('error',()=>res.destroy()).pipe(res);},movie?sourceDelayMs:0);
  res.on('close',()=>clearTimeout(timer));
 });await new Promise(r=>upstream.listen(0,'127.0.0.1',r));
 const url=`http://127.0.0.1:${upstream.address().port}/film.m3u8`;
 const network={open:(url,range,signal)=>new Promise((resolve,reject)=>{
  const req=http.get(url,{signal,headers:range?{Range:`bytes=${range.start}-${range.end}`}:{ }},response=>resolve({response,url}));req.on('error',reject);
 })};
 for(const variant of [{name:'serial-4s',chunkSeconds:4,overlapDownloads:false},{name:'overlap-4s',chunkSeconds:4,overlapDownloads:true},{name:'overlap-8s',chunkSeconds:8,overlapDownloads:true}]) {
  console.log('RUN '+variant.name);current={encoding:0,maxEncoding:0,downloads:0,downloadsDuringEncoding:0};
  const config={...tools,chunkSeconds:variant.chunkSeconds};
  const processor=createProcessor(config,{cacheDir:join(dir,variant.name),prefetch:0,overlapDownloads:variant.overlapDownloads,runner:async(executable,args,options)=>{
   const encoding=executable===tools.ffmpeg&&args.includes('-vf');if(encoding){current.encoding++;current.maxEncoding=Math.max(current.maxEncoding,current.encoding);}
   try{return await run(executable,args,options);}finally{if(encoding)current.encoding--;}
  }});
  const began=performance.now();pipeline=processor.create({id:variant.name,headers:{}},{url},network);await pipeline.preparing;
  assert.equal(pipeline.status().state,'ready');const startupSeconds=(performance.now()-began)/1000;
  const count=Math.ceil((pipeline.duration-.000001)/pipeline.chunkDuration);
  const files=await Promise.all(Array.from({length:count},(_,index)=>pipeline.segment(index)));
  const elapsedSeconds=(performance.now()-began)/1000,status=pipeline.status();
  assert.equal(current.maxEncoding,1);
  for(const file of [files[0],files.at(-1)]){
   const metadata=JSON.parse((await run(tools.ffprobe,['-v','error','-show_streams','-of','json',file],{env:tools.env})).stdout);
   const video=metadata.streams.find(s=>s.codec_type==='video'),audio=metadata.streams.find(s=>s.codec_type==='audio');
   assert.equal(video.width,3840);assert.equal(video.height,2160);assert.equal(video.codec_name,'h264');assert.equal(audio.codec_name,'aac');
   assert(Math.abs(+video.start_time-(+audio.start_time))<.05);
  }
  const result={...variant,sourceDelayMs,duration:status.duration,elapsedSeconds,startupSeconds,endToEndSpeed:status.duration/elapsedSeconds,activeSpeed:status.speed,sourcePreparationSeconds:status.sourcePreparationSeconds,encodeSeconds:status.encodeSeconds,validationSeconds:status.validationSeconds,downloads:current.downloads,downloadsDuringEncoding:current.downloadsDuringEncoding,segments:count,output:status.output};results.push(result);console.log('PASS '+JSON.stringify(result));
  pipeline.close();await Promise.allSettled([...pipeline.jobs.values()].map(j=>j.promise));pipeline=null;
 }
 assert.equal(results[0].downloadsDuringEncoding,0);assert(results[1].downloadsDuringEncoding>0);assert(results[2].downloadsDuringEncoding>0);
 await writeFile(resolve('.desktop-server/pipeline-gpu-validation.json'),JSON.stringify({date:new Date().toISOString(),note:'Synthetic source with injected 1s per uncached HLS segment; not a projector smoothness or public Internet benchmark.',results},null,2));
}finally {
 pipeline?.close();if(pipeline)await Promise.allSettled([...pipeline.jobs.values()].map(j=>j.promise));
 if(upstream)await new Promise(r=>{upstream.close(r);upstream.closeAllConnections();});await rm(dir,{recursive:true,force:true});
}
