#!/usr/bin/env node
// Emulator-only end-to-end check: real CMS HTTP, GPU processing, HLS and Media3.
// Uses isolated ports/cache, then restores the emulator's settings and history.
import assert from 'node:assert/strict';
import {execFile,execFileSync} from 'node:child_process';
import {promisify,parseArgs} from 'node:util';
import {createReadStream} from 'node:fs';
import {mkdtemp, mkdir, readFile, writeFile, rm, stat} from 'node:fs/promises';
import http from 'node:http';
import {tmpdir} from 'node:os';
import {resolve, join} from 'node:path';
import {randomBytes} from 'node:crypto';
import {toolConfig, run} from '../../desktop-server/runtime.mjs';
import {createProcessor} from '../../desktop-server/processing.mjs';
import {createDesktopServer} from '../../desktop-server/server.mjs';

process.chdir(resolve(import.meta.dirname,'../..'));
const {values}=parseArgs({options:{performance:{type:'boolean',default:false},report:{type:'string'},seconds:{type:'string',default:'60'}}});
const sampleSeconds=values.performance?Number(values.seconds):20;
assert(Number.isFinite(sampleSeconds)&&sampleSeconds>=10&&sampleSeconds<=180,'采样时间须为10–180秒');
const serial='emulator-5554', adb=resolve('android-tv/.android-sdk/platform-tools/adb');
const shell=(...args)=>execFileSync(adb,['-s',serial,...args],{encoding:'utf8',timeout:20000,maxBuffer:8*1024**2});
const execAsync=promisify(execFile);
const shellAsync=async(...args)=>(await execAsync(adb,['-s',serial,...args],{encoding:'utf8',timeout:20000,maxBuffer:8*1024**2})).stdout;
assert.equal(shell('emu','avd','name').split('\n')[0].trim(),'LibreTV-TV-API30');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const checks=[], results=[], reversePorts=[];
const pass=(name,detail)=>{checks.push({name,detail});console.log('PASS '+name+(detail?' '+JSON.stringify(detail):''));};
const report=resolve(values.report||`.desktop-server/super-resolution-${values.performance?'performance':'emulator'}.json`);
const dir=await mkdtemp(join(tmpdir(),'libretv-sr-emulator-'));
let app, upstream, ws, forwardPort, baseline, config, fixtureKey, passed=false;
let sequence=0;const pending=new Map();
async function evaluate(expression) {
  const id=++sequence;
  const response=await new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout'));},15000);
    pending.set(id,{resolve:r=>{clearTimeout(timer);resolve(r);},reject:e=>{clearTimeout(timer);reject(e);}});
    ws.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression,awaitPromise:true,returnByValue:true}}));
  });
  if(response.exceptionDetails)throw Error(JSON.stringify(response.exceptionDetails));
  return response.result.value;
}
async function attach() {
  const pid=shell('shell','pidof','org.libretv.tv').trim();
  forwardPort=shell('forward','tcp:0','localabstract:webview_devtools_remote_'+pid).trim();
  let page;
  for(let n=0;n<60&&!page;n++) {
    try {page=(await(await fetch(`http://127.0.0.1:${forwardPort}/json/list`)).json()).find(p=>p.type==='page');}catch{}
    if(!page)await delay(200);
  }
  assert(page,'找不到模拟器 WebView');ws=new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
  ws.onmessage=({data})=>{const r=JSON.parse(data),p=pending.get(r.id);if(p){pending.delete(r.id);r.error?p.reject(r.error):p.resolve(r.result);}};
  ws.onclose=()=>{for(const p of pending.values())p.reject(Error('CDP disconnected'));pending.clear();};
  await waitFor(()=>evaluate('!!window.LibreTvBackend'),'backend boot');
  await evaluate(`(()=>{
    window.__srEvents=[];
    window.__srRequests=[];
    window.__srHttpErrors=[];
    const fetchMessage=window.LibreTvNativeHttp.postMessage.bind(window.LibreTvNativeHttp);
    window.LibreTvNativeHttp.postMessage=raw=>{
      const callback=window.LibreTvNativeHttp.onmessage;
      if(callback&&!callback.__srWrapped){const wrapped=e=>{const v=JSON.parse(e.data);if(v.error)window.__srHttpErrors.push(v.error);callback(e);};wrapped.__srWrapped=true;window.LibreTvNativeHttp.onmessage=wrapped;}
      fetchMessage(raw);
    };
    const request=window.LibreTvBackend.request.bind(window.LibreTvBackend);
    window.LibreTvBackend.request=r=>{window.__srRequests.push({id:r.id,op:r.op});request(r);};
    const original=window.LibreTvNativeUi.postMessage.bind(window.LibreTvNativeUi);
    window.LibreTvNativeUi.postMessage=raw=>{window.__srEvents.push(JSON.parse(raw));original(raw);};
    window.__srRpc=async(op,args={})=>{
      const id='sr-qa-'+Date.now()+'-'+Math.random();window.LibreTvBackend.request({id,op,args});
      for(let n=0;n<1400;n++){
        const e=window.__srEvents.find(e=>e.id===id&&(e.event==='result'||e.event==='error'));
        if(e){if(e.event==='error')throw Error(e.error);return e.value;}
        await new Promise(r=>setTimeout(r,100));
      }throw Error('Backend timeout '+op);
    };
  })()`);
}
function detach() {if(ws){ws.onclose=null;ws.close();}if(forwardPort)shell('forward','--remove','tcp:'+forwardPort);forwardPort=null;}
const rpc=(op,args={})=>evaluate(`window.__srRpc(${JSON.stringify(op)},${JSON.stringify(args)})`);
const state=()=>evaluate('window.__libreTvNativePlayerState');
async function waitFor(test,label,timeout=120000) {
  const end=Date.now()+timeout;
  do{const value=await test();if(value)return value;await delay(200);}while(Date.now()<end);
  throw Error('等待超时：'+label);
}
const key=async(...codes)=>{await shellAsync('shell','input','keyevent',...codes.map(String));await delay(200);};
async function nodes() {
  const output=await shellAsync('shell','uiautomator','dump','/sdcard/sr-qa.xml');assert(!output.includes('ERROR'),output);
  const xml=await shellAsync('exec-out','cat','/sdcard/sr-qa.xml');
  return [...xml.matchAll(/<node\b[^>]*>/g)].map(([tag])=>Object.fromEntries([...tag.matchAll(/([\w-]+)="([^"]*)"/g)].map(([,k,v])=>[k,v.replace(/&#(\d+);/g,(_,n)=>String.fromCharCode(Number(n))).replace(/&quot;/g,'"').replace(/&amp;/g,'&').replace(/&lt;/g,'<').replace(/&gt;/g,'>')])));
}
async function tapNode(match) {
  const node=(await nodes()).find(match);assert(node,'找不到测试控件');
  const b=node.bounds.match(/-?\d+/g).map(Number);await shellAsync('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));await delay(250);
}
async function home() {
  for(let n=0;n<4&&(await state());n++)await key(4);
  assert(!(await state()),'无法退出视频');await delay(400);
}
async function restart() {
  // Chromium commits DOM storage asynchronously; allow it to reach disk before force-stop.
  await delay(6000);
  detach();shell('shell','am','force-stop','org.libretv.tv');shell('shell','am','start','-n','org.libretv.tv/.MainActivity');await delay(1500);await attach();
}
async function search() {
  // Only used once per fresh activity, so no keyboard editing shortcuts are needed.
  await delay(1500);
  await tapNode(n=>n.class==='android.widget.EditText');
  shell('shell','input','text','SRQA');await key(66);
  await waitFor(()=>evaluate(`(()=>{const request=window.__srRequests.filter(r=>r.op==='search').at(-1);if(!request)return false;const result=window.__srEvents.find(e=>e.id===request.id&&(e.event==='result'||e.event==='error'));if(result?.event==='error')throw Error(result.error);return result?.value?.done;})()`),'搜索完成');
  await delay(500);
  if(!(await nodes()).some(n=>n['content-desc']?.startsWith('SRQA '))) {
    console.log('SEARCH_DIAGNOSTIC '+JSON.stringify(await evaluate(`({errors:window.__srHttpErrors,result:window.__srEvents.filter(e=>e.event==='result'&&e.value?.done).at(-1)?.value})`)));
    throw Error('测试片源搜索无结果');
  }
}
async function toggle(enabled) {
  await home();await key(82);
  const before=(await nodes()).find(n=>n.text.startsWith('AI 超分：'));assert(before);
  if(before.text.includes('已开启')!==enabled) {
    await tapNode(n=>n.text.startsWith('AI 超分：'));
    if((await rpc('config')).superResolution!==enabled)await key(23);
  }
  const after=(await nodes()).find(n=>n.text.startsWith('AI 超分：'));
  assert.equal(after.text.includes('已开启'),enabled);await key(4);
  assert.equal((await rpc('config')).superResolution,enabled);
}
async function capture(name) {
  const file=resolve('.desktop-server/sr-emulator-'+name+'.png');
  await shellAsync('shell','screencap','-p','/sdcard/sr-qa.png');await shellAsync('pull','/sdcard/sr-qa.png',file);return file;
}

try {
  await attach();config=await rpc('config');
  baseline=await evaluate(`(async()=>{
    const db=await new Promise((r,j)=>{const q=indexedDB.open('libretv');q.onsuccess=()=>r(q.result);q.onerror=()=>j(q.error);});
    const tables={};for(const name of ['history','progress','searchHistory'])tables[name]=await new Promise((r,j)=>{const q=db.transaction(name).objectStore(name).getAll();q.onsuccess=()=>r(q.result);q.onerror=()=>j(q.error);});db.close();
    return {local:Object.fromEntries(Object.entries(localStorage)),tables};
  })()`);
  await writeFile(resolve('.desktop-server/sr-emulator-backup.json'),JSON.stringify(baseline),{mode:0o600});
  const tools=await toolConfig();
  const cases=[
    {id:'360',title:'SRQA 01 360P',width:640,height:360,out:[854,480],duration:16},
    {id:'480',title:'SRQA 02 480P',width:854,height:480,out:[1280,720],duration:16},
    {id:'720',title:'SRQA 03 720P',width:1280,height:720,out:[1920,1080],duration:64},
    {id:'1080',title:'SRQA 04 1080P',width:1920,height:1080,out:[3840,2160],duration:16},
    {id:'wide',title:'SRQA 05 Cinema',width:1920,height:800,out:[3840,1600],duration:16},
    {id:'4k',title:'SRQA 06 Native4K',width:3840,height:2160,out:[3840,2160],duration:16},
    {id:'ntsc',title:'SRQA 07 2997FPS',width:1280,height:720,out:[1920,1080],fps:'30000/1001',duration:16},
    {id:'silent',title:'SRQA 08 Silent',width:640,height:360,out:[854,480],silent:true,duration:16},
  ].filter(c=>!values.performance||['720','1080'].includes(c.id));
  if(values.performance)for(const c of cases)c.duration=sampleSeconds+70;
  for(const c of cases) {
    console.log('GENERATE '+c.id);
    await run(tools.ffmpeg,['-hide_banner','-loglevel','error','-threads','2','-f','lavfi','-i',`testsrc2=s=${c.width}x${c.height}:r=${c.fps||24}:d=${c.duration}`,
      ...(c.silent?[]:['-f','lavfi','-i',`sine=frequency=440:sample_rate=48000:duration=${c.duration}`]),
      '-c:v','libx264','-preset','ultrafast','-crf','26','-g','48','-pix_fmt','yuv420p',...(c.silent?['-an']:['-c:a','aac']),'-movflags','+faststart','-y',join(dir,c.id+'.mp4')],{env:tools.env});
  }
  // Keep ordinary segments outside /ads/: that path itself is an ad marker.
  await mkdir(join(dir,'hls'));
  await run(tools.ffmpeg,['-hide_banner','-loglevel','error','-threads','2','-f','lavfi','-i','testsrc2=s=1280x720:r=24:d=22','-f','lavfi','-i','sine=frequency=880:sample_rate=48000:duration=22',
    '-c:v','libx264','-preset','ultrafast','-g','48','-sc_threshold','0','-pix_fmt','yuv420p','-c:a','aac','-f','hls','-hls_time','2','-hls_list_size','0','-hls_segment_filename',join(dir,'hls','%d.ts'),'-y',join(dir,'hls','film.m3u8')],{env:tools.env});
  const manifest=await readFile(join(dir,'hls','film.m3u8'),'utf8');
  await writeFile(join(dir,'hls','film.m3u8'),manifest.replace(/#EXTINF:([^\n]+)\n1\.ts/,'#EXT-X-CUE-OUT:2\n#EXTINF:$1\n1.ts\n#EXT-X-CUE-IN'));
  if(!values.performance)cases.push({id:'ads',title:'SRQA 09 AdMarkers',width:1280,height:720,out:[1920,1080],duration:22});
  let origin;
  upstream=http.createServer(async(req,res)=>{
    try {
      const url=new URL(req.url,'http://test');
      if(url.pathname==='/api') {
        console.log('CMS '+url.search);
        const items=cases.filter(c=>!url.searchParams.has('ids')||c.id===url.searchParams.get('ids')).map(c=>({vod_id:c.id,vod_name:c.title,vod_pic:'',vod_year:'2026',vod_remarks:'超分实测',vod_play_from:'SRQA',vod_play_url:'正片$'+origin+'/'+(c.id==='ads'?'hls/film.m3u8':c.id+'.mp4')}));
        res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({code:1,page:1,pagecount:1,list:items}));return;
      }
      if(!/^\/(360|480|720|1080|wide|4k|ntsc|silent)\.mp4$|^\/hls\/(film\.m3u8|\d+\.ts)$/.test(url.pathname)){res.writeHead(404);res.end();return;}
      const file=join(dir,url.pathname.slice(1)),size=(await stat(file)).size;
      const range=req.headers.range?.match(/^bytes=(\d+)-(\d*)$/),start=range?+range[1]:0,end=range&&range[2]?Math.min(+range[2],size-1):size-1;
      res.statusCode=range?206:200;res.setHeader('Content-Length',end-start+1);res.setHeader('Accept-Ranges','bytes');
      res.setHeader('Content-Type',file.endsWith('m3u8')?'application/vnd.apple.mpegurl':file.endsWith('ts')?'video/mp2t':'video/mp4');
      if(range)res.setHeader('Content-Range',`bytes ${start}-${end}/${size}`);
      const stream=createReadStream(file,{start,end});stream.on('error',()=>res.destroy());res.on('close',()=>stream.destroy());stream.pipe(res);
    }catch{res.writeHead(500);res.end();}
  });
  await new Promise(r=>upstream.listen(0,'127.0.0.1',r));origin=`http://127.0.0.1:${upstream.address().port}`;
  const token=randomBytes(24).toString('hex'),headers={Authorization:'Bearer '+token};
  const pipelines=new Map(),encoded=new Set();
  const processor=createProcessor(tools,{cacheDir:join(dir,'cache'),runner:async(executable,args,options)=>{
    const output=args.at(-1)?.match(/\/work\/([a-f0-9]{32})\/(\d+)\/encoded\.ts$/);
    if(output)encoded.add(output[1]+'/'+output[2]);
    return run(executable,args,options);
  }});
  const create=processor.create;
  processor.create=(session,input,network)=>{const pipeline=create(session,input,network);pipelines.set(session.id,pipeline);return pipeline;};
  app=createDesktopServer({token,allowTestOrigin:origin,processor});await new Promise(r=>app.server.listen(0,'127.0.0.1',r));
  const base=`http://127.0.0.1:${app.server.address().port}`;
  for(const port of [upstream.address().port,app.server.address().port]){shell('reverse',`tcp:${port}`,`tcp:${port}`);reversePorts.push(port);}
  const serverStatus=async()=>await(await fetch(base+'/v1/status',{headers})).json();
  await rpc('settings',{playbackMode:'desktop',desktopServerUrl:base,desktopPairingToken:token,adFilter:true,superResolution:true,autoplayNext:false});
  const added=await rpc('add-source',{name:'SRQA isolated fixtures',url:origin+'/api'});
  fixtureKey=added.sources.find(s=>s.url===origin+'/api').key;
  for(const k of added.selectedKeys)if(k!==fixtureKey)await rpc('toggle-source',{key:k});
  let current=await rpc('config');console.log('SETUP '+JSON.stringify({enabled:current.enabled,selected:current.selectedKeys,fixtureKey}));
  assert.deepEqual(current.selectedKeys,[fixtureKey]);
  await restart();
  current=await rpc('config');console.log('RESTART '+JSON.stringify({enabled:current.enabled,selected:current.selectedKeys,fixtureKey}));
  assert.deepEqual(current.selectedKeys,[fixtureKey]);await search();
  pass('真实片源接口与增强服务已连接，使用独立端口和缓存',{engine:tools.engine,model:tools.model,encoder:tools.encoder});
  async function play(c,{sr=true,cached=false,direct=false}={}) {
    await home();await evaluate('window.__srEvents=[]');
    let card;
    for(let n=0;n<4&&!card;n++){
      card=(await nodes()).find(n=>n['content-desc']===c.title&&n.focusable==='true');
      if(!card){await shellAsync('shell','input','swipe','950','920','950','380','250');await delay(300);}
    }
    assert(card,'找不到影片卡片 '+c.title);
    const b=card.bounds.match(/-?\d+/g).map(Number);
    await shellAsync('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));await delay(250);
    await key(23);const began=performance.now();
    const rendered=await waitFor(async()=>{
      const s=await state();if(s?.error)throw Error(s.error);
      const error=await evaluate('window.__srEvents.find(e=>e.event==="error")?.error');if(error)throw Error(error);
      return s?.rendered&&s.ready?s:null;
    },'实际出画面 '+c.title);
    const startupSeconds=(performance.now()-began)/1000;
    if(!rendered.paused)await key(85);
    await waitFor(async()=>(await state())?.paused,'暂停');
    const descriptor=await evaluate('window.__srEvents.filter(e=>e.event==="result"&&e.value?.playback).at(-1)?.value');
    assert(descriptor,'没有实际播放结果');const delivery=descriptor.playback;
    const expected=sr&&!direct?c.out:[c.width,c.height];
    assert.deepEqual([rendered.width,rendered.height],expected);
    assert.equal(rendered.route,direct?'direct':'desktop');
    const labels=(await nodes()).map(n=>n.text).filter(Boolean);
    if(direct){assert(labels.some(s=>s.includes('原始直连')&&s.includes('未超分')));return {state:rendered,startupSeconds};}
    const status=await(await fetch(base+'/v1/sessions/'+delivery.desktopSession.id,{headers})).json();
    assert.equal(status.state,'ready');assert.deepEqual([status.output.width,status.output.height],expected);
    const upgraded=sr&&expected[0]>c.width;
    assert.equal(status.processingInfo.superResolution,upgraded);assert.equal(status.processingInfo.superResolutionEnabled,sr);
    assert(labels.includes(delivery.presentation.video));assert(labels.includes(delivery.presentation.ads));
    assert(delivery.presentation.video.includes(upgraded?'已超分':sr?'未超分':'超分已关闭'));
    const warm=await evaluate('window.__srEvents.filter(e=>e.event==="processing-progress"&&e.value?.state==="ready").at(-1)?.value');
    const pipeline=pipelines.get(delivery.desktopSession.id),index=Math.floor(status.position/pipeline.chunkDuration);
    const warmEncoded=encoded.has(delivery.desktopSession.id+'/'+index);
    if(cached){assert(warm?.cacheHits>0,'首段应复用已处理缓存');assert(!warmEncoded,'缓存重播的首段不应重新编码');}
    // Probe the exact file Media3 consumed. A second HTTP consumer seeking to zero
    // would otherwise cancel the player's speculative work and distort buffering.
    const file=join(pipeline.directory,index+'.ts');
    const metadata=JSON.parse((await run(tools.ffprobe,['-v','error','-show_streams','-of','json',file],{env:tools.env})).stdout);
    const video=metadata.streams.find(s=>s.codec_type==='video'),audio=metadata.streams.find(s=>s.codec_type==='audio');
    assert.deepEqual([video.width,video.height],expected);assert.equal(video.codec_name,'h264');
    assert.equal(!!audio,!c.silent);if(audio){assert.equal(audio.codec_name,'aac');assert(Math.abs(+audio.start_time- +video.start_time)<.05);}
    const result={id:c.id,sr,source:[c.width,c.height],output:expected,upgraded,startupSeconds,speed:status.speed,cacheHits:status.cacheHits,warmCacheHits:warm?.cacheHits,warmEncoded,audio:!!audio,
      segmentMbps:(await stat(file)).size*8/Math.min(pipeline.chunkDuration,pipeline.duration-index*pipeline.chunkDuration)/1_000_000,
      maxBitrate:status.output.maxBitrate,startupPreparedSeconds:status.startupPreparedSeconds,presentation:delivery.presentation};
    results.push(result);pass(c.title+(sr?' 自动档':' 超分关闭'),result);
    return {state:rendered,status,delivery};
  }
  async function duringPlayback(enabled) {
    const before=await state();await evaluate('window.__srEvents=[]');await key(82);
    const all=await nodes(),label=all.find(n=>n.text==='AI 超分');assert(label);
    const box=label.bounds.match(/-?\d+/g).map(Number),cy=(box[1]+box[3])/2;
    const button=all.find(n=>n.class==='android.widget.Button'&&/^已(开启|关闭)$/.test(n.text)&&Math.abs(n.bounds.match(/-?\d+/g).map(Number).slice(1).filter((_,i)=>i%2===0).reduce((a,b)=>a+b,0)/2-cy)<30);
    assert(button,'找不到播放设置中的超分开关');
    const b=button.bounds.match(/-?\d+/g).map(Number);await shellAsync('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));await delay(250);
    if((await rpc('config')).superResolution!==enabled)await key(23);
    const next=await waitFor(async()=>{const s=await state();if(s?.error)throw Error(s.error);return s?.sessionId!==before.sessionId&&s?.rendered&&s.ready?s:null;},'播放中切换超分');
    if(!next.paused)await key(85);
    assert.equal(next.height,enabled?1080:720);assert(Math.abs(next.position-before.position)<3,'切换超分丢失了原片进度');
    await waitFor(async()=>(await serverStatus()).activeSessions===1,'旧任务回收');
    const labelText=(await nodes()).map(n=>n.text).find(t=>t.includes('原始 1280×720'));
    assert(labelText?.includes(enabled?'已超分':'超分已关闭'));
    pass('播放设置'+(enabled?'开启':'关闭')+'超分，保留进度且仅有一个任务',{before:before.position,after:next.position,height:next.height});
  }
  async function continuous(name) {
    await key(85);
    await waitFor(async()=>{const s=await state();if(s.error)throw Error(s.error);return s.ready&&!s.buffering&&!s.paused;},'冷快进缓冲完成');
    const start=await state(),began=performance.now(),samples=[];
    for(let n=0;n<Math.ceil(sampleSeconds*2);n++){
      await delay(500);const s=await state();assert(!s.error&&s.rendered,JSON.stringify(s));
      samples.push({elapsed:(performance.now()-began)/1000,position:s.position,buffering:s.buffering,bufferedDuration:s.bufferedDuration,droppedFrames:s.droppedFrames,renderedFrames:s.renderedFrames});
    }
    const after=await state(),elapsedSeconds=(performance.now()-began)/1000;
    assert(after.position-start.position>1,'连续播放没有推进');await key(85);
    const detail={advancedSeconds:after.position-start.position,elapsedSeconds,bufferingSamples:samples.filter(s=>s.buffering).length,totalSamples:samples.length,
      minBufferSeconds:Math.min(...samples.map(s=>s.bufferedDuration)),startDroppedFrames:start.droppedFrames,endDroppedFrames:after.droppedFrames,renderedFrames:after.renderedFrames};
    pass(name,detail);if(values.performance)results.push({id:name,performance:detail,samples});
  }
  for(const c of cases) {
    const value=await play(c);
    if(c.id==='720') {
      // Actual remote keys; no injected player commands.
      const before=(await state()).position;
      await key(4,22,22);assert(Math.abs((await state()).position-before)<.3);
      await key(23);await waitFor(async()=>Math.abs((await state()).position-(before+20))<1,'跨段快进确认');
      await continuous('720P→1080P 冷快进后连续播放');
      await capture('1080p');
      if(!values.performance){await duringPlayback(false);await duringPlayback(true);}
    }
    if(c.id==='1080'){if(values.performance)await continuous('1080P→4K 连续播放');await capture('4k');}
    if(c.id==='ntsc')assert(Math.abs(value.status.output.fps-30000/1001)<.001);
    if(c.id==='ads') {
      assert.equal(value.status.removedSegments,1);assert.equal(value.status.duration,20);assert.equal(value.status.removedSeconds,2);
      pass('超分与广告分片过滤同时生效，实际时间轴22秒变20秒');
    }
  }
  if(!values.performance){
  await toggle(false);await restart();assert.equal((await rpc('config')).superResolution,false);await search();
  pass('遥控器关闭超分，重启后开关保持关闭');
  await play(cases[2],{sr:false});await play(cases[2],{sr:false,cached:true});
  const adOff=await play(cases.at(-1),{sr:false});assert.equal(adOff.status.removedSegments,1);assert.equal(adOff.status.duration,20);
  pass('关闭超分仍去广告，720P原尺寸编码和缓存独立');
  await toggle(true);await play(cases[2],{cached:true});
  pass('重新开启超分恢复1080P，复用开启状态的缓存');
  await home();await rpc('settings',{playbackMode:'direct'});
  const created=(await serverStatus()).created;await play(cases[2],{direct:true});
  assert.equal((await serverStatus()).created,created);pass('独立直连始终播放720P原画，不创建电脑增强任务');
  }
  await home();await waitFor(async()=>(await serverStatus()).activeSessions===0,'退出任务回收');
  pass('退出播放已回收全部增强任务');passed=true;
} catch(error) {
  console.error(error.stack);
  try{console.log('FAIL_SCREENSHOT '+await capture('failure'));}catch{}
  process.exitCode=1;
} finally {
  if(baseline) {
    try {
      try{await home();}catch{await restart();}
      await delay(1100);
      await evaluate(`(async()=>{
        const backup=${JSON.stringify(baseline)};
        const db=await new Promise((r,j)=>{const q=indexedDB.open('libretv');q.onsuccess=()=>r(q.result);q.onerror=()=>j(q.error);});
        await new Promise((r,j)=>{const t=db.transaction(Object.keys(backup.tables),'readwrite');t.oncomplete=r;t.onerror=()=>j(t.error);for(const [name,rows]of Object.entries(backup.tables)){const s=t.objectStore(name);s.clear();for(const row of rows)s.put(row);}});db.close();
        localStorage.clear();for(const [k,v]of Object.entries(backup.local))localStorage.setItem(k,v);location.reload();
      })()`);
      await delay(1500);await restart();
      const restored=await rpc('config');assert(JSON.stringify(restored)===JSON.stringify(config),'恢复后的设置与测试前不一致');
      const tables=await evaluate(`(async()=>{
        const db=await new Promise((r,j)=>{const q=indexedDB.open('libretv');q.onsuccess=()=>r(q.result);q.onerror=()=>j(q.error);});
        const tables={};for(const name of ['history','progress','searchHistory'])tables[name]=await new Promise((r,j)=>{const q=db.transaction(name).objectStore(name).getAll();q.onsuccess=()=>r(q.result);q.onerror=()=>j(q.error);});db.close();return tables;
      })()`);
      assert(JSON.stringify(tables)===JSON.stringify(baseline.tables),'恢复后的观看/搜索记录与测试前不一致');
      pass('已恢复模拟器原有设置、片源和观看/搜索记录');
    }catch(error){console.error('恢复现场失败，备份位于 .desktop-server/sr-emulator-backup.json：'+error.stack);process.exitCode=1;}
  }
  if(ws)detach();app?.closeSessions();
  await Promise.all([app?.server,upstream].filter(Boolean).map(s=>new Promise(r=>{s.close(r);s.closeAllConnections();})));
  for(const port of reversePorts)shell('reverse','--remove',`tcp:${port}`);
  await writeFile(report,JSON.stringify({date:new Date().toISOString(),serial,passed:passed&&!process.exitCode,checks,results},null,2));
  await rm(dir,{recursive:true,force:true});
  console.log('REPORT '+report);
}
