#!/usr/bin/env node
// Real emulator UI + local CMS + Media3; snapshot and restore all touched data.
import assert from 'node:assert/strict';
import {execFile,execFileSync} from 'node:child_process';
import {promisify} from 'node:util';
import {readFile,writeFile,mkdtemp,rm} from 'node:fs/promises';
import {createReadStream} from 'node:fs';
import http from 'node:http';
import {join,resolve} from 'node:path';
import {tmpdir} from 'node:os';
import {run,toolConfig} from '../../desktop-server/runtime.mjs';
process.chdir(resolve(import.meta.dirname,'../..'));
const adb=resolve('android-tv/.android-sdk/platform-tools/adb'),serial='emulator-5554',exec=promisify(execFile);
const shell=async(...args)=>(await exec(adb,['-s',serial,...args],{encoding:'utf8',timeout:20000,maxBuffer:4*1024**2})).stdout;
assert.equal(execFileSync(adb,['-s',serial,'emu','avd','name'],{encoding:'utf8'}).split('\n')[0].trim(),'LibreTV-TV-API30');
const delay=ms=>new Promise(r=>setTimeout(r,ms)),checks=[];const pass=(name,detail)=>{checks.push({name,detail});console.log('PASS '+name+(detail?' '+JSON.stringify(detail):''));};
const dir=await mkdtemp(join(tmpdir(),'libretv-history-ui-'));let server,ws,forward,reverse,backup,passed=false,sequence=0;const pending=new Map();
async function evaluate(expression){const id=++sequence;let timer;try{const r=await new Promise((resolve,reject)=>{timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout'));},12000);pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression,awaitPromise:true,returnByValue:true}}));});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;}finally{clearTimeout(timer);}}
async function waitFor(test,label){const deadline=Date.now()+20000;while(Date.now()<deadline){const r=await test();if(r)return r;await delay(200);}throw Error('等待超时：'+label);}
async function attach(){const pid=(await shell('shell','pidof','org.libretv.tv')).trim();forward=(await shell('forward','tcp:0','localabstract:webview_devtools_remote_'+pid)).trim();const page=await waitFor(async()=>{try{return (await(await fetch('http://127.0.0.1:'+forward+'/json/list')).json()).find(p=>p.type==='page');}catch{return false}},'应用数据页');ws=new WebSocket(page.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j});ws.onmessage=({data})=>{const r=JSON.parse(data),p=pending.get(r.id);if(p){pending.delete(r.id);r.error?p.reject(r.error):p.resolve(r.result);}};await waitFor(()=>evaluate('!!window.LibreTvBackend'),'业务层就绪');}
async function detach(){ws?.close();ws=null;if(forward)await shell('forward','--remove','tcp:'+forward);forward=null;}
async function restart(){await delay(6000);await detach();await shell('shell','am','force-stop','org.libretv.tv');await shell('shell','am','start','-n','org.libretv.tv/.MainActivity');await delay(1000);await attach();}
const state=()=>evaluate('window.__libreTvNativePlayerState??null');
const snapshot=`(async()=>{const db=await new Promise((r,j)=>{const q=indexedDB.open('libretv');q.onsuccess=()=>r(q.result);q.onerror=j;});const tables={};for(const name of ['history','progress','searchHistory'])tables[name]=await new Promise((r,j)=>{const q=db.transaction(name).objectStore(name).getAll();q.onsuccess=()=>r(q.result);q.onerror=j;});db.close();return {local:Object.fromEntries(Object.entries(localStorage)),tables};})()`;
async function writeTables(tables){await evaluate(`(async()=>{const data=${JSON.stringify(tables)},db=await new Promise((r,j)=>{const q=indexedDB.open('libretv');q.onsuccess=()=>r(q.result);q.onerror=j});await new Promise((r,j)=>{const t=db.transaction(Object.keys(data),'readwrite');t.oncomplete=r;t.onerror=j;for(const [name,rows]of Object.entries(data)){const store=t.objectStore(name);store.clear();for(const row of rows)store.put(row);}});db.close();})()`);}
const decode=v=>v.replace(/&#(\d+);/g,(_,n)=>String.fromCharCode(+n)).replace(/&quot;/g,'"').replace(/&amp;/g,'&').replace(/&lt;/g,'<').replace(/&gt;/g,'>');
async function nodes(){const out=await shell('shell','uiautomator','dump','/sdcard/history-ui-test.xml');assert(!out.includes('ERROR'),out);return [...(await shell('exec-out','cat','/sdcard/history-ui-test.xml')).matchAll(/<node\b[^>]*>/g)].map(([tag])=>Object.fromEntries([...tag.matchAll(/([\w-]+)="([^"]*)"/g)].map(([,k,v])=>[k,decode(v)])));}
async function key(...codes){await shell('shell','input','keyevent',...codes.map(String));await delay(250);}
async function focus(match){const n=(await nodes()).find(match);assert(n,'找不到测试控件');const b=n.bounds.match(/-?\d+/g).map(Number);await shell('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));await delay(200);return n;}
async function home(){for(let n=0;n<4&&(await state());n++)await key(4);assert(!(await state()));await delay(500);}
async function play(title,position){await focus(n=>n.focusable==='true'&&n['content-desc']==='历史观看：'+title);await key(23);const s=await waitFor(async()=>{const s=await state();if(s?.error)throw Error(s.error);return s?.rendered&&s.ready?s:false;},'历史续播');assert(Math.abs(s.position-position)<3,JSON.stringify({expected:position,actual:s.position}));if(!s.paused)await key(85);return s;}
try{
 await attach();backup=await evaluate(snapshot);await writeFile(resolve('.desktop-server/history-ui-backup.json'),JSON.stringify(backup),{mode:0o600});assert(!(await state()),'请先退出模拟器正在播放的影片');
 const tools=await toolConfig();await run(tools.ffmpeg,['-hide_banner','-loglevel','error','-f','lavfi','-i','color=c=blue:s=640x360:r=24:d=80','-f','lavfi','-i','sine=frequency=440:sample_rate=48000:duration=80','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-c:a','aac','-movflags','+faststart','-y',join(dir,'video.mp4')],{env:tools.env});
 const media=await readFile(join(dir,'video.mp4'));let origin;
 const titles={movie:'历史测试电影',series:'历史测试剧集',legacy:'历史旧记录',search:'HistoryQA 搜索测试片'};
 server=http.createServer((req,res)=>{const url=new URL(req.url,'http://localhost');if(url.pathname==='/video.mp4') {const m=req.headers.range?.match(/bytes=(\d+)-(\d*)/),start=m?+m[1]:0,end=m&&m[2]?Math.min(+m[2],media.length-1):media.length-1;res.writeHead(m?206:200,{'Content-Type':'video/mp4','Content-Length':end-start+1,'Accept-Ranges':'bytes',...(m?{'Content-Range':`bytes ${start}-${end}/${media.length}`}:{})});res.end(media.subarray(start,end+1));}
 else if(url.pathname==='/poster.png'){res.setHeader('Content-Type','image/png');createReadStream(resolve('public/player-poster.png')).pipe(res);}
 else if(url.pathname==='/api'){const id=url.searchParams.get('ids')||'search',episodes=['series','legacy'].includes(id)?3:1;res.setHeader('Content-Type','application/json');res.end(JSON.stringify({code:1,page:1,pagecount:1,list:[{vod_id:id,vod_name:titles[id],vod_pic:origin+'/poster.png',vod_remarks:'测试',vod_play_from:'m3u8',vod_play_url:Array.from({length:episodes},(_,i)=>`第${i+1}集$${origin}/video.mp4`).join('#')}]}));}else{res.writeHead(404);res.end();}});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));reverse=String(server.address().port);origin='http://127.0.0.1:'+reverse;await shell('reverse','tcp:'+reverse,'tcp:'+reverse);
 const now=Date.now(),sourceKey='history_ui_fixture';
 const records=['movie','series','legacy'].map((id,n)=>({id:sourceKey+'_'+id,sourceKey,sourceUrl:origin+'/api',vodId:id,title:titles[id],pic:origin+'/poster.png',episodeIndex:n===0?0:n,totalEpisodes:n===0?1:3,playbackPosition:[75,22,38][n],duration:80,timestamp:now-(n+1)*1000}));
 await writeTables({history:records,progress:[{key:sourceKey+'_series_1',position:24,duration:80,updatedAt:now}],searchHistory:[]});
 await evaluate(`(()=>{const data=JSON.parse(localStorage.getItem('libretv-settings'));data.state.playbackMode='direct';data.state.selectedKeys=['${sourceKey}'];data.state.customAPIs.push({key:'${sourceKey}',name:'历史测试源名称很长用于验证进度不会被挤走',url:'${origin}/api'});localStorage.setItem('libretv-settings',JSON.stringify(data));location.reload();})()`);await restart();
 let all=await nodes();const cards=all.filter(n=>n.focusable==='true'&&n['content-desc'].startsWith('历史观看：'));
 assert.deepEqual(cards.map(n=>n['content-desc']),records.map(r=>'历史观看：'+r.title));
 for(const n of all.filter(n=>Object.values(titles).includes(n.text)||n.text.startsWith('已看 ')||n.text.includes('第 2 / 3 集'))){const b=n.bounds.match(/-?\d+/g).map(Number);assert(b[1]>=0&&b[3]<=1080,'首页片名和进度应完整位于首屏');}
 assert(all.some(n=>n.text.includes('已看 00:22')));assert(all.some(n=>n.text.includes('第 2 / 3 集')));assert(all.some(n=>n.text==='已看完'));
 pass('首页按最近观看排序，首屏显示片名、上次集数、进度和已看完状态');
 await focus(n=>n['content-desc']==='历史观看：历史测试电影');await key(22);assert.equal((await nodes()).find(n=>n.focused==='true')?.['content-desc'],'历史观看：历史测试剧集');await key(21);assert.equal((await nodes()).find(n=>n.focused==='true')?.['content-desc'],'历史观看：历史测试电影');pass('真实左右方向键可移动历史卡片焦点');
 await play(titles.series,24);await home();all=await nodes();assert.equal(all.find(n=>n.focused==='true')?.['content-desc'],'历史观看：历史测试剧集');assert.equal(all.find(n=>n.focusable==='true'&&n['content-desc'].startsWith('历史观看：'))?.['content-desc'],'历史观看：历史测试剧集');pass('历史剧集从上次集数和最新进度续播，退出后更新排序并恢复焦点');
 await play(titles.legacy,38);await home();pass('缺少逐集进度的旧观看记录也可续播');
 await play(titles.movie,0);await home();pass('已看完的历史电影确认后从头重播');
 await focus(n=>n.class==='android.widget.EditText');await shell('shell','input','text','HistoryQA');await key(66);await waitFor(async()=>(await nodes()).some(n=>n['content-desc']===titles.search),'搜索结果');
 assert(!(await nodes()).some(n=>n.focusable==='true'&&n['content-desc'].startsWith('历史观看：')));await focus(n=>n.text==='历史观看'&&n.class==='android.widget.Button');await key(23);assert((await nodes()).some(n=>n.focusable==='true'&&n['content-desc'].startsWith('历史观看：')));pass('搜索结果与历史列表可切换，返回历史不需要联网搜影片');
 await restart();assert((await nodes()).some(n=>n['content-desc']==='历史观看：历史旧记录'));pass('重启后历史列表和已保存进度仍存在');
 await writeTables({history:[],progress:[]});await evaluate('location.reload()');await delay(1000);all=await nodes();assert(all.some(n=>n.text.includes('暂无观看记录')));assert(!all.some(n=>n.focusable==='true'&&n['content-desc'].startsWith('历史观看：')));pass('没有观看记录时显示明确空态');
 passed=true;
}finally{
 if(backup){try{await home();await delay(1200);await evaluate("window.dispatchEvent(new Event('beforeunload'))");await writeTables(backup.tables);await evaluate(`(()=>{localStorage.clear();for(const [key,value]of Object.entries(${JSON.stringify(backup.local)}))localStorage.setItem(key,value);location.reload();})()`);await restart();const restored=await evaluate(snapshot);assert.deepEqual(restored.tables,backup.tables);assert.deepEqual(JSON.parse(restored.local['libretv-settings']),JSON.parse(backup.local['libretv-settings']));pass('测试结束已完整恢复模拟器设置、来源及观看和搜索记录');await waitFor(async()=>(await nodes()).some(n=>n.text.includes('个来源已启用')),'首页数据加载');await shell('shell','screencap','-p','/sdcard/history-home.png');await shell('pull','/sdcard/history-home.png',resolve('.desktop-server/watch-history-home.png'));}catch(error){passed=false;console.error('恢复失败，备份在 .desktop-server/history-ui-backup.json',error);process.exitCode=1;}}
 await detach();if(reverse)await shell('reverse','--remove','tcp:'+reverse);if(server)await new Promise(r=>{server.close(r);server.closeAllConnections();});await rm(dir,{recursive:true,force:true});await writeFile(resolve('.desktop-server/watch-history-emulator.json'),JSON.stringify({date:new Date().toISOString(),passed,checks},null,2));
}
