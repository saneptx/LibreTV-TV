#!/usr/bin/env node
// 模拟器 + 本机电脑服务（GPU）端到端：多来源比对跳过夹在连续编码里的广告，电脑串流去水印与滚动字幕。
// 两个本地片源提供同一集：A 在 150 秒处夹了 20 秒广告并叠加台标/滚动广告字幕，B 为干净版本。
// 备份并恢复模拟器的设置、来源、学习库、比对缓存及观看/搜索记录。
import assert from 'node:assert/strict';
import {execFile,execFileSync} from 'node:child_process';
import {promisify} from 'node:util';
import {readFile,writeFile,mkdtemp,mkdir,rm} from 'node:fs/promises';
import http from 'node:http';
import {randomBytes} from 'node:crypto';
import {join,resolve} from 'node:path';
import {tmpdir} from 'node:os';
import {run,toolConfig} from '../../desktop-server/runtime.mjs';
import {createProcessor} from '../../desktop-server/processing.mjs';
import {createDesktopServer} from '../../desktop-server/server.mjs';
process.chdir(resolve(import.meta.dirname,'../..'));
const adb=resolve('android-tv/.android-sdk/platform-tools/adb'),serial='emulator-5554',exec=promisify(execFile);
const shell=async(...args)=>(await exec(adb,['-s',serial,...args],{encoding:'utf8',timeout:20000,maxBuffer:4*1024**2})).stdout;
assert.equal(execFileSync(adb,['-s',serial,'emu','avd','name'],{encoding:'utf8'}).split('\n')[0].trim(),'LibreTV-TV-API30');
const delay=ms=>new Promise(r=>setTimeout(r,ms)),checks=[];const pass=(name,detail)=>{checks.push({name,detail});console.log('PASS '+name+(detail?' '+JSON.stringify(detail):''));};
const dir=await mkdtemp(join(tmpdir(),'libretv-desktop-ads-'));let fixture,desktop,ws,forward,reverses=[],backup,passed=false,sequence=0;const pending=new Map();
async function evaluate(expression){const id=++sequence;let timer;try{const r=await new Promise((resolve,reject)=>{timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout'));},15000);pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression,awaitPromise:true,returnByValue:true}}));});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;}finally{clearTimeout(timer);}}
async function waitFor(test,label,ms=30000){const deadline=Date.now()+ms;while(Date.now()<deadline){const r=await test();if(r)return r;await delay(250);}throw Error('等待超时：'+label);}
async function attach(){const pid=(await shell('shell','pidof','org.libretv.tv')).trim();forward=(await shell('forward','tcp:0','localabstract:webview_devtools_remote_'+pid)).trim();const page=await waitFor(async()=>{try{return (await(await fetch('http://127.0.0.1:'+forward+'/json/list')).json()).find(p=>p.type==='page');}catch{return false}},'应用数据页');ws=new WebSocket(page.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j});ws.onmessage=({data})=>{const r=JSON.parse(data),p=pending.get(r.id);if(p){pending.delete(r.id);r.error?p.reject(r.error):p.resolve(r.result);}};await waitFor(()=>evaluate('!!window.LibreTvBackend'),'业务层就绪');}
async function detach(){ws?.close();ws=null;if(forward)await shell('forward','--remove','tcp:'+forward);forward=null;}
async function restart(){await delay(3000);await detach();await shell('shell','am','force-stop','org.libretv.tv');await shell('shell','am','start','-n','org.libretv.tv/.MainActivity');await delay(1000);await attach();}
const state=()=>evaluate('window.__libreTvNativePlayerState??null');
const send=(s,body)=>evaluate(`window.LibreTvNativePlayer.postMessage(JSON.stringify(${JSON.stringify({version:1,sessionId:s.sessionId,...body})}))`);
const snapshot=`(async()=>{const db=await new Promise((r,j)=>{const q=indexedDB.open('libretv');q.onsuccess=()=>r(q.result);q.onerror=j;});const tables={};for(const name of ['history','progress','searchHistory'])tables[name]=await new Promise((r,j)=>{const q=db.transaction(name).objectStore(name).getAll();q.onsuccess=()=>r(q.result);q.onerror=j;});db.close();return {local:Object.fromEntries(Object.entries(localStorage)),tables};})()`;
async function writeTables(tables){await evaluate(`(async()=>{const data=${JSON.stringify(tables)},db=await new Promise((r,j)=>{const q=indexedDB.open('libretv');q.onsuccess=()=>r(q.result);q.onerror=j});await new Promise((r,j)=>{const t=db.transaction(Object.keys(data),'readwrite');t.oncomplete=r;t.onerror=j;for(const [name,rows]of Object.entries(data)){const store=t.objectStore(name);store.clear();for(const row of rows)store.put(row);}});db.close();})()`);}
const decode=v=>v.replace(/&#(\d+);/g,(_,n)=>String.fromCharCode(+n)).replace(/&quot;/g,'"').replace(/&amp;/g,'&').replace(/&lt;/g,'<').replace(/&gt;/g,'>');
async function nodes(){const out=await shell('shell','uiautomator','dump','/sdcard/desktop-ads-ui.xml');if(out.includes('ERROR'))return [];return [...(await shell('exec-out','cat','/sdcard/desktop-ads-ui.xml')).matchAll(/<node\b[^>]*>/g)].map(([tag])=>Object.fromEntries([...tag.matchAll(/([\w-]+)="([^"]*)"/g)].map(([,k,v])=>[k,decode(v)])));}
async function key(...codes){await shell('shell','input','keyevent',...codes.map(String));await delay(250);}
async function tap(match,label){const n=await waitFor(async()=>(await nodes()).find(match),label);const b=n.bounds.match(/-?\d+/g).map(Number);await shell('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));await delay(300);}
async function home(){for(let n=0;n<5&&(await state());n++)await key(4);await delay(500);}
async function pause(s){await send(s,{type:'pause'});await waitFor(async()=>(await state())?.paused,'暂停');}
async function label(){await key(20);return (await waitFor(async()=>(await nodes()).find(n=>n.text?.startsWith('去广告')||n.text?.startsWith('已去广告')),'处理标注',20000)).text;}
/** 从原片 145 秒附近播放，确认跳过 150–170 秒的广告；返回最终播放器状态 */
async function crossAd(s,startAt){
 await send(s,{type:'seek',position:startAt});await send(s,{type:'play'});const seen=[];
 const after=await waitFor(async()=>{const x=await state();if(x?.error)throw Error(x.error);if(x)seen.push(x.position);return x&&x.skipped>0&&x.position>startAt+8?x:false;},'跨过广告',90000);
 return {after,seen};
}
try{
 await attach();backup=await evaluate(snapshot);await writeFile(resolve('.desktop-server/desktop-ads-backup.json'),JSON.stringify(backup),{mode:0o600});assert(!(await state()),'请先退出模拟器正在播放的影片');
 const tools=await toolConfig(),font='/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc';
 const ffmpeg=(...args)=>run(tools.ffmpeg,['-hide_banner','-loglevel','error',...args],{env:tools.env,timeout:600_000});
 const base=join(dir,'base.mkv'),ad=join(dir,'ad.mkv');
 await ffmpeg('-f','lavfi','-i','testsrc2=size=640x360:rate=24:d=300','-f','lavfi','-i',"anoisesrc=d=300:c=pink:seed=42:a=0.3,lowpass=f=3000,volume=enable='lt(mod(t,7),3)':volume=0.3",'-c:v','libx264','-preset','ultrafast','-c:a','aac','-shortest','-y',base);
 await ffmpeg('-f','lavfi','-i','smptebars=size=640x360:rate=24:d=20','-f','lavfi','-i','sine=f=880:d=20,volume=0.5','-c:v','libx264','-preset','ultrafast','-c:a','aac','-shortest','-y',ad);
 const overlays=`drawbox=x=545:y=14:w=80:h=28:color=white@0.85:t=fill,drawtext=fontfile=${font}:text='台标':x=560:y=16:fontsize=20:fontcolor=black,drawbox=x=0:y=56:w=640:h=24:color=black@0.6:t=fill,drawtext=fontfile=${font}:text='澳门首家线上赌场 网址 www.example-ad.com 澳门首家线上赌场':x=w-mod(t*80\\,w+900):y=58:fontsize=17:fontcolor=yellow`;
 for(const [name,inputs,filter] of [['clean',[base],null],['dirty',[base,ad],`[0:v]trim=0:150,setpts=PTS-STARTPTS[v0];[0:a]atrim=0:150,asetpts=PTS-STARTPTS[a0];[0:v]trim=150,setpts=PTS-STARTPTS[v1];[0:a]atrim=150,asetpts=PTS-STARTPTS[a1];[v0][a0][1:v][1:a][v1][a1]concat=n=3:v=1:a=1[c][a];[c]${overlays}[v]`]]){
  await mkdir(join(dir,name));
  await ffmpeg(...inputs.flatMap(i=>['-i',i]),...(filter?['-filter_complex',filter,'-map','[v]','-map','[a]']:[]),'-c:v','libx264','-preset','ultrafast','-g','48','-c:a','aac','-f','hls','-hls_time','6','-hls_list_size','0','-hls_segment_filename',join(dir,name,'%03d.ts'),'-y',join(dir,name,'index.m3u8'));
 }
 const title='多来源比对测试';let origin;
 fixture=http.createServer(async(req,res)=>{const url=new URL(req.url,'http://localhost');
  const api=url.pathname.match(/^\/api(1|2)$/);
  if(api){res.setHeader('Content-Type','application/json');const name=api[1]==='1'?'dirty':'clean';res.end(JSON.stringify({code:1,page:1,pagecount:1,list:[{vod_id:'film'+api[1],vod_name:title,vod_pic:'',vod_remarks:'测试',vod_play_from:'m3u8',vod_play_url:`正片$${origin}/${name}/index.m3u8`}]}));return;}
  const file=url.pathname.match(/^\/(dirty|clean)\/(index\.m3u8|\d{3}\.ts)$/);
  if(file){res.setHeader('Content-Type',file[2].endsWith('.ts')?'video/mp2t':'application/vnd.apple.mpegurl');res.end(await readFile(join(dir,file[1],file[2])));return;}
  res.writeHead(404);res.end();});
 await new Promise(r=>fixture.listen(0,'127.0.0.1',r));const fport=fixture.address().port;origin='http://127.0.0.1:'+fport;
 const token=randomBytes(24).toString('hex'),processor=createProcessor(tools,{cacheDir:join(dir,'cache')});
 desktop=createDesktopServer({token,allowTestOrigin:origin,processor});await new Promise(r=>desktop.server.listen(0,'127.0.0.1',r));const dport=desktop.server.address().port;
 for(const port of [fport,dport]){await shell('reverse','tcp:'+port,'tcp:'+port);reverses.push(port);}
 const keys=['desktop_ads_a','desktop_ads_b'];
 await writeTables({history:[{id:keys[0]+'_film1',sourceKey:keys[0],sourceUrl:origin+'/api1',vodId:'film1',title,pic:'',episodeIndex:0,totalEpisodes:1,playbackPosition:0,duration:0,timestamp:Date.now()}],progress:[],searchHistory:[]});
 await evaluate(`(()=>{for(const k of ['libretv-learned-ads-v1','libretv-compare-v1'])localStorage.removeItem(k);const data=JSON.parse(localStorage.getItem('libretv-settings'));Object.assign(data.state,{playbackMode:'direct',adFilter:true,overlayRemoval:true,superResolution:false,desktopServerUrl:'http://127.0.0.1:${dport}',desktopPairingToken:'${token}',selectedKeys:${JSON.stringify(keys)}});data.state.customAPIs.push({key:'${keys[0]}',name:'夹带广告源',url:'${origin}/api1'},{key:'${keys[1]}',name:'干净参考源',url:'${origin}/api2'});localStorage.setItem('libretv-settings',JSON.stringify(data));location.reload();})()`);await restart();

 // 直连：设备自动请求电脑比对，Media3 播放到 150 秒时跳过 20 秒
 await tap(n=>n['content-desc']==='历史观看：'+title,'历史卡片');await key(23);
 let s=await waitFor(async()=>{const x=await state();if(x?.error)throw Error(x.error);return x?.rendered&&x.ready?x:false;},'直连出画面');
 const compared=await waitFor(async()=>{const x=await state();return x?.skips>0?x:false;},'多来源比对结果',180000);
 const stored=await evaluate(`JSON.parse(localStorage.getItem('libretv-compare-v1')||'{}')`);const result=Object.values(stored)[0];
 assert.equal(result?.intervals?.length,1,JSON.stringify(stored));assert(Math.abs(result.intervals[0].start-150)<1.5&&Math.abs(result.intervals[0].duration-20)<.6,JSON.stringify(result));
 pass('直连播放时设备自动找到同一集的另一个来源，电脑定位夹在连续编码中的广告',{interval:result.intervals[0],references:result.references});
 let crossed=await crossAd(compared,144);
 assert(crossed.after.position>=169&&!crossed.seen.some(p=>p>151.6&&p<168.5),JSON.stringify(crossed.seen.slice(-12)));
 pass('Media3 播放到广告起点时自动跳到广告结束处',{skipped:crossed.after.skipped,lastPositions:crossed.seen.slice(-6).map(p=>Math.round(p*10)/10)});
 await pause(crossed.after);let text=await label();assert.match(text,/多来源比对：跳过 1 段 · 20 秒/);pass('播放标注显示多来源比对结果',{label:text});
 await home();

 // 电脑串流：服务端去水印 + 同一比对结果经服务端时间轴换算后跳过
 await evaluate(`(()=>{const data=JSON.parse(localStorage.getItem('libretv-settings'));data.state.playbackMode='desktop';localStorage.setItem('libretv-settings',JSON.stringify(data));})()`);await restart();
 await tap(n=>n['content-desc']==='历史观看：'+title,'历史卡片');await key(23);
 s=await waitFor(async()=>{const x=await state();if(x?.error)throw Error(x.error);return x?.rendered&&x.ready&&x.route==='desktop'?x:false;},'电脑串流出画面',240000);
 await waitFor(async()=>(await state())?.skips>0,'载入比对缓存',30000);
 await pause(await state());text=await label();
 assert.match(text,/去水印：已处理 台标\/水印 1 处、滚动字幕 1 条/,text);pass('电脑串流识别并处理台标与滚动广告字幕，标注实际处理结果',{label:text});
 crossed=await crossAd(await state(),144);
 assert(crossed.after.position>=169&&!crossed.seen.some(p=>p>151.6&&p<168.5),JSON.stringify(crossed.seen.slice(-12)));
 pass('电脑串流时同样跳过夹带广告',{skipped:crossed.after.skipped});
 await shell('shell','screencap','-p','/sdcard/desktop-ads.png').catch(()=>{});await shell('pull','/sdcard/desktop-ads.png',resolve('.desktop-server/desktop-ads-emulator.png')).catch(()=>{});
 await home();
 passed=true;
}catch(error){console.error('FAIL',error);process.exitCode=1;}finally{
 if(backup){try{await home().catch(()=>{});await delay(1000);await writeTables(backup.tables);await evaluate(`(()=>{localStorage.clear();for(const [key,value]of Object.entries(${JSON.stringify(backup.local)}))localStorage.setItem(key,value);location.reload();})()`);await restart();const restored=await evaluate(snapshot);assert.deepEqual(restored.tables,backup.tables);assert.deepEqual(JSON.parse(restored.local['libretv-settings']),JSON.parse(backup.local['libretv-settings']));pass('测试结束已完整恢复模拟器设置、来源、学习库、比对缓存及观看和搜索记录');}catch(error){passed=false;console.error('恢复失败，备份在 .desktop-server/desktop-ads-backup.json',error);process.exitCode=1;}}
 for(const port of reverses)await shell('reverse','--remove','tcp:'+port).catch(()=>{});
 await detach().catch(()=>{});desktop?.closeSessions();desktop?.server.close();desktop?.server.closeAllConnections();fixture?.close();await rm(dir,{recursive:true,force:true});
 await writeFile(resolve('.desktop-server/desktop-ads-emulator.json'),JSON.stringify({passed,checks,at:new Date().toISOString()},null,2));
 if(!passed)process.exitCode=1;
 process.exit();
}
