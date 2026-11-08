#!/usr/bin/env node
// Real emulator UI + local CMS + Media3: codec-mismatch ad removal and remote-marked ad learning.
// Snapshots and restores settings, sources, learned ads and watch/search records.
import assert from 'node:assert/strict';
import {execFile,execFileSync} from 'node:child_process';
import {promisify} from 'node:util';
import {readFile,readdir,writeFile,mkdtemp,mkdir,rm} from 'node:fs/promises';
import http from 'node:http';
import {join,resolve} from 'node:path';
import {tmpdir} from 'node:os';
import {run,toolConfig} from '../../desktop-server/runtime.mjs';
process.chdir(resolve(import.meta.dirname,'../..'));
const adb=resolve('android-tv/.android-sdk/platform-tools/adb'),serial='emulator-5554',exec=promisify(execFile);
const shell=async(...args)=>(await exec(adb,['-s',serial,...args],{encoding:'utf8',timeout:20000,maxBuffer:4*1024**2})).stdout;
assert.equal(execFileSync(adb,['-s',serial,'emu','avd','name'],{encoding:'utf8'}).split('\n')[0].trim(),'LibreTV-TV-API30');
const delay=ms=>new Promise(r=>setTimeout(r,ms)),checks=[];const pass=(name,detail)=>{checks.push({name,detail});console.log('PASS '+name+(detail?' '+JSON.stringify(detail):''));};
const dir=await mkdtemp(join(tmpdir(),'libretv-ad-analysis-'));let server,ws,forward,reverse,backup,passed=false,sequence=0;const pending=new Map();
async function evaluate(expression){const id=++sequence;let timer;try{const r=await new Promise((resolve,reject)=>{timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout'));},12000);pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression,awaitPromise:true,returnByValue:true}}));});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;}finally{clearTimeout(timer);}}
async function waitFor(test,label,ms=25000){const deadline=Date.now()+ms;while(Date.now()<deadline){const r=await test();if(r)return r;await delay(200);}throw Error('等待超时：'+label);}
async function attach(){const pid=(await shell('shell','pidof','org.libretv.tv')).trim();forward=(await shell('forward','tcp:0','localabstract:webview_devtools_remote_'+pid)).trim();const page=await waitFor(async()=>{try{return (await(await fetch('http://127.0.0.1:'+forward+'/json/list')).json()).find(p=>p.type==='page');}catch{return false}},'应用数据页');ws=new WebSocket(page.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j});ws.onmessage=({data})=>{const r=JSON.parse(data),p=pending.get(r.id);if(p){pending.delete(r.id);r.error?p.reject(r.error):p.resolve(r.result);}};await waitFor(()=>evaluate('!!window.LibreTvBackend'),'业务层就绪');}
async function detach(){ws?.close();ws=null;if(forward)await shell('forward','--remove','tcp:'+forward);forward=null;}
async function restart(){await delay(4000);await detach();await shell('shell','am','force-stop','org.libretv.tv');await shell('shell','am','start','-n','org.libretv.tv/.MainActivity');await delay(1000);await attach();}
const state=()=>evaluate('window.__libreTvNativePlayerState??null');
const snapshot=`(async()=>{const db=await new Promise((r,j)=>{const q=indexedDB.open('libretv');q.onsuccess=()=>r(q.result);q.onerror=j;});const tables={};for(const name of ['history','progress','searchHistory'])tables[name]=await new Promise((r,j)=>{const q=db.transaction(name).objectStore(name).getAll();q.onsuccess=()=>r(q.result);q.onerror=j;});db.close();return {local:Object.fromEntries(Object.entries(localStorage)),tables};})()`;
async function writeTables(tables){await evaluate(`(async()=>{const data=${JSON.stringify(tables)},db=await new Promise((r,j)=>{const q=indexedDB.open('libretv');q.onsuccess=()=>r(q.result);q.onerror=j});await new Promise((r,j)=>{const t=db.transaction(Object.keys(data),'readwrite');t.oncomplete=r;t.onerror=j;for(const [name,rows]of Object.entries(data)){const store=t.objectStore(name);store.clear();for(const row of rows)store.put(row);}});db.close();})()`);}
const decode=v=>v.replace(/&#(\d+);/g,(_,n)=>String.fromCharCode(+n)).replace(/&quot;/g,'"').replace(/&amp;/g,'&').replace(/&lt;/g,'<').replace(/&gt;/g,'>');
async function nodes(){const out=await shell('shell','uiautomator','dump','/sdcard/ad-ui-test.xml');assert(!out.includes('ERROR'),out);return [...(await shell('exec-out','cat','/sdcard/ad-ui-test.xml')).matchAll(/<node\b[^>]*>/g)].map(([tag])=>Object.fromEntries([...tag.matchAll(/([\w-]+)="([^"]*)"/g)].map(([,k,v])=>[k,decode(v)])));}
async function key(...codes){await shell('shell','input','keyevent',...codes.map(String));await delay(250);}
async function tap(match,label){const n=await waitFor(async()=>(await nodes()).find(match),label);const b=n.bounds.match(/-?\d+/g).map(Number);await shell('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));await delay(300);return n;}
async function home(){for(let n=0;n<4&&(await state());n++)await key(4);assert(!(await state()));await delay(500);}
const near=(a,b,tolerance=.6)=>Math.abs(a-b)<=tolerance;
async function play(title,session){await tap(n=>n.focusable==='true'&&n['content-desc']==='历史观看：'+title,title);await key(23);
 return waitFor(async()=>{const s=await state();if(s?.error)throw Error(s.error);return s?.rendered&&s.ready&&s.sessionId!==session?s:false;},'播放 '+title);}
// 视频播放时 uiautomator 等不到空闲，取控件树前先暂停
async function pause(s){await evaluate(`window.LibreTvNativePlayer.postMessage(JSON.stringify({version:1,sessionId:${JSON.stringify(s.sessionId)},type:'pause'}))`);await waitFor(async()=>(await state())?.paused,'暂停');}
const removals=s=>(s.timeMap||[]).map(r=>[Math.round(r.start*10)/10,Math.round(r.duration*10)/10]);
try{
 await attach();backup=await evaluate(snapshot);await writeFile(resolve('.desktop-server/ad-analysis-backup.json'),JSON.stringify(backup),{mode:0o600});assert(!(await state()),'请先退出模拟器正在播放的影片');
 // 正片 1280×720；广告一为 640×360（编码不同），广告二与正片同编码（只能靠学习识别）。
 const tools=await toolConfig();
 const encode=async(name,source,size,seconds)=>{await mkdir(join(dir,name),{recursive:true});await run(tools.ffmpeg,['-hide_banner','-loglevel','error','-f','lavfi','-i',`${source}=size=${size}:rate=24`,'-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t',String(seconds),'-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-g','48','-c:a','aac','-b:a','64k','-f','hls','-hls_time','2','-hls_list_size','0','-hls_segment_filename',join(dir,name,'%03d.ts'),'-y',join(dir,name,'index.m3u8')],{env:tools.env});
  return (await readdir(join(dir,name))).filter(f=>f.endsWith('.ts')).sort();};
 const film=await encode('film','testsrc2','1280x720',120),spot=await encode('spot','smptebars','640x360',12),promo=await encode('promo','rgbtestsrc','1280x720',10);
 const seg=(path,files)=>files.map(f=>`#EXTINF:2.000000,\n${path}/${f}`).join('\n');
 const playlist=(adPath,files)=>['#EXTM3U','#EXT-X-VERSION:3','#EXT-X-TARGETDURATION:2','#EXT-X-MEDIA-SEQUENCE:0',seg('/media/film',film.slice(0,30)),'#EXT-X-DISCONTINUITY',seg(adPath,files),'#EXT-X-DISCONTINUITY',seg('/media/film',film.slice(30)),'#EXT-X-ENDLIST'].join('\n');
 const playlists={codec:playlist('/media/spot',spot),learn:playlist('/media/promo',promo),again:playlist('/cdn2/promo',promo)};
 let origin;const titles={codec:'去广告测试·编码不同',learn:'去广告测试·学习',again:'去广告测试·复用广告'};
 server=http.createServer(async(req,res)=>{const url=new URL(req.url,'http://localhost');
  const m=url.pathname.match(/^\/(\w+)\.m3u8$/);if(m&&playlists[m[1]]){res.setHeader('Content-Type','application/vnd.apple.mpegurl');res.end(playlists[m[1]]);return;}
  const file=url.pathname.match(/^\/(?:media|cdn2)\/(film|spot|promo)\/(\d{3}\.ts)$/);
  if(file){res.setHeader('Content-Type','video/mp2t');res.end(await readFile(join(dir,file[1],file[2])));return;}
  if(url.pathname==='/api'){const id=url.searchParams.get('ids')||'codec';res.setHeader('Content-Type','application/json');res.end(JSON.stringify({code:1,page:1,pagecount:1,list:[{vod_id:id,vod_name:titles[id]||titles.codec,vod_pic:'',vod_remarks:'测试',vod_play_from:'m3u8',vod_play_url:`正片$${origin}/${id in titles?id:'codec'}.m3u8`}]}));return;}
  res.writeHead(404);res.end();});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));reverse=String(server.address().port);origin='http://127.0.0.1:'+reverse;await shell('reverse','tcp:'+reverse,'tcp:'+reverse);
 const now=Date.now(),sourceKey='ad_analysis_fixture';
 const records=Object.keys(titles).map((id,n)=>({id:sourceKey+'_'+id,sourceKey,sourceUrl:origin+'/api',vodId:id,title:titles[id],pic:'',episodeIndex:0,totalEpisodes:1,playbackPosition:0,duration:0,timestamp:now-n*1000}));
 await writeTables({history:records,progress:[],searchHistory:[]});
 await evaluate(`(()=>{localStorage.removeItem('libretv-learned-ads-v1');const data=JSON.parse(localStorage.getItem('libretv-settings'));Object.assign(data.state,{playbackMode:'direct',adFilter:true,selectedKeys:['${sourceKey}']});data.state.customAPIs.push({key:'${sourceKey}',name:'去广告测试源',url:'${origin}/api'});localStorage.setItem('libretv-settings',JSON.stringify(data));location.reload();})()`);await restart();

 let s=await play(titles.codec,'');
 assert.deepEqual(removals(s),[[60,12]]);assert(near(s.duration,120,1),String(s.duration));
 pass('与正片分辨率不同的插入段被自动删除，Media3 时间轴缩短为正片长度',{timeMap:s.timeMap,duration:s.duration});
 await pause(s);await key(20);const label=await waitFor(async()=>(await nodes()).find(n=>n.text?.startsWith('去广告：')),'去广告标注').catch(async(error)=>{console.error((await nodes()).map(n=>n.text).filter(Boolean));throw error;});
 assert.match(label.text,/已过滤 1 处 · 12 秒（含编码比对 1 段）/);pass('播放标注显示实际删除量与编码比对来源',{label:label.text});
 await home();

 s=await play(titles.learn,'');
 assert.deepEqual(removals(s),[],'同编码广告不应被编码比对误删');assert(near(s.duration,130,1));
 await evaluate(`window.LibreTvNativePlayer.postMessage(JSON.stringify({version:1,sessionId:${JSON.stringify(s.sessionId)},type:'seek',position:63}))`);
 await pause(s);
 await waitFor(async()=>{const x=await state();return x&&x.position>=62.5&&x.position<70?x:false;},'跳到广告段');
 pass('编码相同的广告不会被编码比对误判，需要用户标记');
 await key(82);await tap(n=>n.text==='标记当前片段为广告','标记按钮');await key(23);
 const learned=await waitFor(async()=>{const x=await state();return x&&x.sessionId!==s.sessionId&&x.rendered&&x.ready?x:false;},'标记后重新打开').catch(async(error)=>{console.error((await nodes()).map(n=>n.text).filter(Boolean),await state());throw error;});
 assert.deepEqual(removals(learned),[[60,10]]);assert(near(learned.position,60.5,2),String(learned.position));
 const stored=await evaluate(`JSON.parse(localStorage.getItem('libretv-learned-ads-v1')||'[]')`);assert.equal(stored.length,1);assert.equal(stored[0].duration,10);
 pass('遥控器标记后保存广告指纹，从广告结束处继续且本片立即去除该段',{timeMap:learned.timeMap,position:learned.position});
 await delay(5500);await home();
 const progress=await evaluate(`(async()=>{const db=await new Promise((r,j)=>{const q=indexedDB.open('libretv');q.onsuccess=()=>r(q.result);q.onerror=j;});const v=await new Promise((r,j)=>{const q=db.transaction('progress').objectStore('progress').get('${sourceKey}_learn_0');q.onsuccess=()=>r(q.result);q.onerror=j;});db.close();return v;})()`);
 assert(progress&&progress.position>=70&&near(progress.duration,130,1),JSON.stringify(progress));
 pass('进度按原片时间保存（含被删广告时长）',{position:progress.position,duration:progress.duration});

 s=await play(titles.again,'');
 assert.deepEqual(removals(s),[[60,10]]);await pause(s);pass('同一广告文件出现在其他影片、其他目录时自动删除',{timeMap:s.timeMap});
 await key(82);await tap(n=>n.text?.startsWith('清除已学习（1）'),'清除按钮');await key(23);
 await waitFor(async()=>(await evaluate(`localStorage.getItem('libretv-learned-ads-v1')`))===null,'清除学习');pass('设置中可清除已学习的广告');
 await key(4);await home();
 passed=true;
}finally{
 server?.close();if(reverse)await shell('reverse','--remove','tcp:'+reverse).catch(()=>{});
 if(backup){try{await home().catch(()=>{});await delay(1200);await writeTables(backup.tables);await evaluate(`(()=>{localStorage.clear();for(const [key,value]of Object.entries(${JSON.stringify(backup.local)}))localStorage.setItem(key,value);location.reload();})()`);await restart();const restored=await evaluate(snapshot);assert.deepEqual(restored.tables,backup.tables);assert.deepEqual(JSON.parse(restored.local['libretv-settings']),JSON.parse(backup.local['libretv-settings']));pass('测试结束已完整恢复模拟器设置、来源、学习库及观看和搜索记录');}catch(error){passed=false;console.error('恢复失败，备份在 .desktop-server/ad-analysis-backup.json',error);process.exitCode=1;}}
 await detach().catch(()=>{});await rm(dir,{recursive:true,force:true});
 await writeFile(resolve('.desktop-server/ad-analysis-emulator.json'),JSON.stringify({passed,checks,at:new Date().toISOString()},null,2));
 if(!passed)process.exitCode=1;
}
