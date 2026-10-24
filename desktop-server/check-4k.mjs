// Real GPU output check; intentionally opt-in because 4K may be slower than playback.
import assert from 'node:assert/strict';
import http from 'node:http';
import { createReadStream } from 'node:fs';
import { mkdtemp, readFile, writeFile, rm, copyFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { toolConfig, run } from './runtime.mjs';
import { createProcessor } from './processing.mjs';
import { createDesktopServer } from './server.mjs';

process.chdir(resolve(import.meta.dirname, '..'));
const dir = await mkdtemp(join(tmpdir(), 'libretv-4k-'));
const tools = await toolConfig();
const checks = [], ids = [];
const token = '4k-test-only-pairing-code-123456789';
const headers = { Authorization:'Bearer '+token, 'Content-Type':'application/json' };
let upstream, app;
try {
  for (const [width,height] of [[1920,1080],[1280,720]]) {
    await run(tools.ffmpeg, ['-hide_banner','-loglevel','error','-threads','2','-f','lavfi','-i',`testsrc2=s=${width}x${height}:r=24:d=4`,
      '-f','lavfi','-i','sine=frequency=440:sample_rate=48000:duration=4','-c:v','libx264','-preset','veryfast','-pix_fmt','yuv420p','-g','24','-c:a','aac','-y',join(dir,`${height}.mp4`)], { env:tools.env });
  }
  upstream = http.createServer((req,res)=>{
    if (!/^\/(1080|720)\.mp4$/.test(req.url)) { res.writeHead(404);res.end();return; }
    res.setHeader('Content-Type','video/mp4');createReadStream(join(dir,req.url.slice(1))).pipe(res);
  });
  await new Promise(r=>upstream.listen(0,'127.0.0.1',r));
  const origin = `http://127.0.0.1:${upstream.address().port}`;
  app = createDesktopServer({token,allowTestOrigin:origin,processor:createProcessor(tools,{cacheDir:join(dir,'cache'),prefetch:0})});
  await new Promise(r=>app.server.listen(0,'127.0.0.1',r));
  const base=`http://127.0.0.1:${app.server.address().port}`;
  for (const height of [1080,720]) {
    tools.targetHeight = height === 1080 ? 'auto' : 2160;
    const began=performance.now();
    const response=await fetch(base+'/v1/sessions',{method:'POST',headers,body:JSON.stringify({url:`${origin}/${height}.mp4`})});
    assert.equal(response.status,201);const job=await response.json();ids.push(job.id);
    let state;
    for (let n=0;n<600;n++) {
      state=await(await fetch(base+'/v1/sessions/'+job.id,{headers})).json();
      if (state.state==='error') throw Error(state.error);
      if (state.state==='ready') break;
      await new Promise(r=>setTimeout(r,300));
    }
    assert.equal(state.state,'ready');
    const startupSeconds=(performance.now()-began)/1000;
    const playlist=await(await fetch(base+job.path,{headers})).text();
    const segment=playlist.split('\n').find(line=>line.startsWith('/v1/'));
    const file=join(dir,`output-${height}.ts`);
    await writeFile(file,Buffer.from(await(await fetch(base+segment,{headers})).arrayBuffer()));
    const metadata=JSON.parse((await run(tools.ffprobe,['-v','error','-show_streams','-of','json',file],{env:tools.env})).stdout);
    const video=metadata.streams.find(s=>s.codec_type==='video'),audio=metadata.streams.find(s=>s.codec_type==='audio');
    assert.equal(video.width,3840);assert.equal(video.height,2160);assert.equal(video.codec_name,'h264');assert.equal(audio.codec_name,'aac');
    assert(Math.abs(Number(video.duration)-4)<.1);assert(Math.abs(Number(video.start_time)-Number(audio.start_time))<.05);
    assert.equal(state.processingInfo.superResolution,true);assert.equal(state.processingInfo.sourceHeight,height);assert.equal(state.processingInfo.outputHeight,2160);
    const result={target:tools.targetHeight,inputHeight:height,outputWidth:video.width,outputHeight:video.height,model:state.output.model,processingSpeed:state.speed,startupSeconds};
    checks.push(result);console.log('PASS '+JSON.stringify(result));
    if(height===1080)await copyFile(file,resolve('.desktop-server/4k-sample.ts'));
    await fetch(base+'/v1/sessions/'+job.id,{method:'DELETE',headers});ids.pop();
  }
  await writeFile(resolve('.desktop-server/4k-validation.json'),JSON.stringify({date:new Date().toISOString(),checks},null,2));
} finally {
  app?.closeSessions();
  await Promise.all([app?.server,upstream].filter(Boolean).map(s=>new Promise(r=>{s.close(r);s.closeAllConnections();})));
  await rm(dir,{recursive:true,force:true});
}
