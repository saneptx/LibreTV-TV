// Real GPU/FFmpeg integration: synthetic input, ads, audio, seeking and cache reuse.
import assert from 'node:assert/strict';import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';import {createReadStream} from 'node:fs';import http from 'node:http';import {join,resolve} from 'node:path';import {tmpdir} from 'node:os';
import {toolConfig,run} from './runtime.mjs';import {createProcessor} from './processing.mjs';import {createDesktopServer} from './server.mjs';
const dir=await mkdtemp(join(tmpdir(),'libretv-enhance-')),tools=await toolConfig({chunkSeconds:4});const checks=[];
const pass=(name,detail)=>{checks.push({name,detail});console.log('PASS '+name+(detail?' '+JSON.stringify(detail):''));};
const token='integration-test-only-token-123456789',headers={Authorization:'Bearer '+token,'Content-Type':'application/json'};
let upstream,app;const sessions=[];
try{
 await run(tools.ffmpeg,['-hide_banner','-loglevel','error','-threads','2','-f','lavfi','-i','color=c=blue:s=1280x720:r=24:d=2','-f','lavfi','-i','color=c=red:s=1280x720:r=24:d=2','-f','lavfi','-i','color=c=green:s=1280x720:r=24:d=6','-f','lavfi','-i','sine=frequency=440:sample_rate=48000:duration=10','-filter_complex','[0:v][1:v][2:v]concat=n=3:v=1:a=0[v]','-map','[v]','-map','3:a','-c:v','libx264','-preset','veryfast','-g','48','-sc_threshold','0','-pix_fmt','yuv420p','-c:a','aac','-f','hls','-hls_time','2','-hls_list_size','0','-hls_segment_filename',join(dir,'%d.ts'),'-y',join(dir,'film.m3u8')],{env:tools.env});
 let manifest=await readFile(join(dir,'film.m3u8'),'utf8');manifest=manifest.replace(/#EXTINF:([^\n]+)\n1\.ts/,'#EXT-X-CUE-OUT:2\n#EXTINF:$1\n1.ts\n#EXT-X-CUE-IN');await writeFile(join(dir,'film.m3u8'),manifest);
 await run(tools.ffmpeg,['-hide_banner','-loglevel','error','-f','lavfi','-i','sine=frequency=880:sample_rate=48000:duration=10','-c:a','aac','-f','hls','-hls_time','2','-hls_list_size','0','-hls_segment_filename',join(dir,'audio%d.ts'),'-y',join(dir,'audio.m3u8')],{env:tools.env});
 let audioManifest=await readFile(join(dir,'audio.m3u8'),'utf8');audioManifest=audioManifest.replace(/#EXTINF:([^\n]+)\naudio1\.ts/,'#EXT-X-CUE-OUT:2\n#EXTINF:$1\naudio1.ts\n#EXT-X-CUE-IN');await writeFile(join(dir,'audio.m3u8'),audioManifest);
 await writeFile(join(dir,'master.m3u8'),'#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="external",DEFAULT=YES,URI="audio.m3u8"\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO="external"\nfilm.m3u8\n');
 upstream=http.createServer((req,res)=>{const name=req.url.slice(1);if(!/^((film|master|audio)\.m3u8|(?:audio)?\d+\.ts)$/.test(name)){res.writeHead(404);res.end();return;}res.setHeader('Content-Type',name.endsWith('m3u8')?'application/vnd.apple.mpegurl':'video/mp2t');createReadStream(join(dir,name)).pipe(res);});await new Promise(r=>upstream.listen(0,'127.0.0.1',r));const origin=`http://127.0.0.1:${upstream.address().port}`;
 const processor=createProcessor(tools,{cacheDir:join(dir,'cache'),prefetch:0});app=createDesktopServer({token,allowTestOrigin:origin,processor});await new Promise(r=>app.server.listen(0,'127.0.0.1',r));const base=`http://127.0.0.1:${app.server.address().port}`;
 const request=(path,options={})=>fetch(base+path,{...options,headers:{...headers,...options.headers}});
 async function create(adFilter=true,position=0,superResolution=true,playlist='film.m3u8'){const response=await request('/v1/sessions',{method:'POST',body:JSON.stringify({url:origin+'/'+playlist,adFilter,position,superResolution})});assert.equal(response.status,201);const value=await response.json();sessions.push(value.id);for(let i=0;i<180;i++){const status=await(await request('/v1/sessions/'+value.id)).json();if(status.state==='error')throw Error(status.message);if(status.state==='ready')return {...value,status};await new Promise(r=>setTimeout(r,300));}throw Error('Processing timeout');}
 const movie=await create();assert.equal(movie.status.duration,8);assert.equal(movie.status.removedSeconds,2);assert.equal(movie.status.removedSegments,1);assert.equal(movie.status.output.model,'fsrcnnx');assert.equal(movie.status.output.height,1080);pass('明确广告分片已删除，10秒输入变为8秒正片',movie.status.output);
 const hls=await(await request(movie.path)).text();assert(hls.includes('#EXT-X-ENDLIST'));assert(!hls.includes(origin));pass('完整 VOD 时间轴仅引用处理后视频');
 const segmentPaths=hls.split('\n').filter(x=>x.startsWith('/v1/'));assert.equal(segmentPaths.length,2);
 async function clip(path,name){const response=await request(path);assert.equal(response.status,200);const file=join(dir,name+'.ts');await writeFile(file,Buffer.from(await response.arrayBuffer()));const metadata=JSON.parse((await run(tools.ffprobe,['-v','error','-show_streams','-show_format','-of','json',file],{env:tools.env})).stdout);const video=metadata.streams.find(x=>x.codec_type==='video'),audio=metadata.streams.find(x=>x.codec_type==='audio');assert.equal(video.width,1920);assert.equal(video.height,1080);assert.equal(video.codec_name,'h264');assert.equal(audio.codec_name,'aac');assert(Math.abs(Number(video.start_time)-Number(audio.start_time))<.05);assert(Math.abs(Number(metadata.format.duration)-4)<.08,JSON.stringify({name,duration:metadata.format.duration,streams:metadata.streams.map(s=>({type:s.codec_type,start:s.start_time,duration:s.duration}))}));return file;}
 const first=await clip(segmentPaths[0],'first'),last=await clip(segmentPaths[1],'last');pass('首段和快进后的片段均为1080P H.264/AAC，音画起点差小于50ms');
 // Probe frame colors through JSON metadata from signalstats rather than interpreting binary stdout as UTF-8.
 const stats=async(file,start=0)=>{const output=await run(tools.ffmpeg,['-hide_banner','-i',file,'-vf',`trim=start=${start},signalstats,metadata=print`,'-frames:v','1','-f','null','-'],{env:tools.env});return output.stderr;};
 const firstStats=await stats(first),lastStats=await stats(last);
 assert(Number(firstStats.match(/lavfi.signalstats.UAVG=([\d.]+)/)[1])>200);assert(Number(lastStats.match(/lavfi.signalstats.UAVG=([\d.]+)/)[1])<110);assert(Number((await stats(first,2.5)).match(/lavfi.signalstats.UAVG=([\d.]+)/)[1])<110);pass('跨广告切点仍连续：蓝色正片后直接进入绿色正片，红色广告未播放');
 const ranged=await request(segmentPaths[0],{headers:{Range:'bytes=0-187'}});assert.equal(ranged.status,206);assert.equal((await ranged.arrayBuffer()).byteLength,188);pass('处理后分段支持Range');
 const replay=await create();assert(replay.status.cacheHits>0);pass('再次播放复用已处理缓存',{cacheHits:replay.status.cacheHits});
 const disabled=await create(false,6);assert.equal(disabled.status.duration,10);assert.equal(disabled.status.removedSegments,0);pass('关闭广告过滤恢复原时长，按指定进度预处理');
 const seeked=await create(true,6);assert.equal(seeked.status.position,4);pass('原始6秒进度映射到去广告后的4秒');
 const srOff=await create(true,0,false);assert.equal(srOff.status.output.height,720);assert.equal(srOff.status.output.superResolution,false);assert.equal(srOff.status.processingInfo.superResolutionEnabled,false);assert.equal(srOff.status.removedSegments,1);assert.equal(srOff.status.duration,8);
 const offPath=(await(await request(srOff.path)).text()).split('\n').find(x=>x.startsWith('/v1/'));
 const offFile=join(dir,'sr-off.ts');await writeFile(offFile,Buffer.from(await(await request(offPath)).arrayBuffer()));
 const offProbe=JSON.parse((await run(tools.ffprobe,['-v','error','-show_streams','-of','json',offFile],{env:tools.env})).stdout);
 assert.equal(offProbe.streams.find(s=>s.codec_type==='video').height,720);assert(offProbe.streams.some(s=>s.codec_type==='audio'));pass('关闭超分实际输出720P并保留音轨，广告过滤仍生效，未误用1080P缓存');
 const srOnAgain=await create();assert.equal(srOnAgain.status.output.height,1080);assert(srOnAgain.status.cacheHits>0);pass('重新开启超分恢复1080P并复用各自缓存');
 // An already sufficient source must use plain transcoding, without frame-image intermediates or false SR claims.
 tools.targetHeight=720;
 const sufficient=await create();assert.equal(sufficient.status.output.superResolution,false);assert.equal(sufficient.status.output.model,null);
 const plainPath=(await(await request(sufficient.path)).text()).split('\n').find(x=>x.startsWith('/v1/'));
 const plain=join(dir,'transcoded.ts');await writeFile(plain,Buffer.from(await(await request(plainPath)).arrayBuffer()));
 const plainMetadata=JSON.parse((await run(tools.ffprobe,['-v','error','-show_streams','-of','json',plain],{env:tools.env})).stdout);
 assert.equal(plainMetadata.streams.find(s=>s.codec_type==='video').height,720);assert(plainMetadata.streams.some(s=>s.codec_type==='audio'));pass('源已达到目标尺寸时直接转码，保留音轨且不虚标超分');
 tools.targetHeight='auto';
 const external=await create(true,0,true,'master.m3u8');assert.equal(external.status.duration,8);assert.equal(external.status.startupPreparedSeconds,8);
 const externalPath=(await(await request(external.path)).text()).split('\n').find(x=>x.startsWith('/v1/'));
 const externalFile=await clip(externalPath,'external-audio');
 const soundStats=(await run(tools.ffmpeg,['-hide_banner','-i',externalFile,'-vn','-af','astats=metadata=1:reset=0,ametadata=print:key=lavfi.astats.1.Zero_crossings_rate','-f','null','-'],{env:tools.env})).stderr;
 const crossing=Number([...soundStats.matchAll(/lavfi\.astats\.1\.Zero_crossings_rate=([\d.e-]+)/g)].at(-1)?.[1]);
 assert(crossing>.03&&crossing<.05,'必须使用880Hz独立音轨，不能误用原视频的440Hz音轨：'+crossing);
 pass('单进程同时处理独立音轨和视频，保留正确音轨、广告时间轴与8秒启动缓冲',{zeroCrossingRate:crossing});
 for(const id of sessions)await request('/v1/sessions/'+id,{method:'DELETE'});sessions.length=0;
 // Default 8s chunks preserve filtering, external audio and the full cleaned timeline.
 tools.chunkSeconds=8;
 const eight=await create(true,0,true,'master.m3u8');assert.equal(eight.status.duration,8);assert.equal(eight.status.startupPreparedSeconds,8);
 const eightPaths=(await(await request(eight.path)).text()).split('\n').filter(x=>x.startsWith('/v1/'));assert.equal(eightPaths.length,1);
 const eightFile=join(dir,'eight-seconds.ts');await writeFile(eightFile,Buffer.from(await(await request(eightPaths[0])).arrayBuffer()));
 const eightMetadata=JSON.parse((await run(tools.ffprobe,['-v','error','-show_streams','-of','json',eightFile],{env:tools.env})).stdout);
 const eightVideo=eightMetadata.streams.find(s=>s.codec_type==='video'),eightAudio=eightMetadata.streams.find(s=>s.codec_type==='audio');
 assert.equal(eightVideo.height,1080);assert(Math.abs(+eightVideo.duration-8)<.1);assert(Math.abs(+eightVideo.start_time-(+eightAudio.start_time))<.05);
 const eightReplay=await create(true,0,true,'master.m3u8');assert(eightReplay.status.cacheHits>0);
 pass('8秒分段保留完整去广告时间轴与独立音轨，音画起点差小于50ms，重播复用对应缓存');
 for(const id of sessions)await request('/v1/sessions/'+id,{method:'DELETE'});sessions.length=0;assert.equal((await(await request('/v1/status')).json()).activeSessions,0);pass('任务退出完成清理');
 const report={date:new Date().toISOString(),tools:{model:tools.model,encoder:tools.encoder},checks};await writeFile(resolve('.desktop-server/enhancement-integration.json'),JSON.stringify(report,null,2));
}finally{app?.closeSessions();await Promise.all([app?.server,upstream].filter(Boolean).map(s=>new Promise(r=>{s.close(r);s.closeAllConnections();})));if(process.env.KEEP_TMP)console.log('TMP '+dir);else await rm(dir,{recursive:true,force:true});}
