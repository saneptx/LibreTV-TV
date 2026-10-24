#!/usr/bin/env node
// Isolated UI fixtures; intentionally limited to an emulator. No settings or database writes.
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
const serial='emulator-5554', adb=resolve('android-tv/.android-sdk/platform-tools/adb');
const shell=(...args)=>execFileSync(adb,['-s',serial,...args],{encoding:'utf8',timeout:15000,maxBuffer:4*1024**2});
const wait=ms=>new Promise(r=>setTimeout(r,ms));
const pid=shell('shell','pidof','org.libretv.tv').trim();assert(pid,'请先在模拟器打开原生预览');
const port=shell('forward','tcp:0','localabstract:webview_devtools_remote_'+pid).trim();
const page=(await(await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(p=>p.type==='page');
assert(page);const ws=new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
let seq=0;const pending=new Map();
ws.onmessage=({data})=>{const r=JSON.parse(data),p=pending.get(r.id);if(p){pending.delete(r.id);r.error?p.reject(r.error):p.resolve(r.result);}};
async function evaluate(expression){const id=++seq;const r=await new Promise((resolve,reject)=>{pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression,awaitPromise:true,returnByValue:true}}));});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;}
const decode=s=>s.replace(/&#(\d+);/g,(_,n)=>String.fromCharCode(Number(n))).replace(/&quot;/g,'"').replace(/&amp;/g,'&').replace(/&lt;/g,'<').replace(/&gt;/g,'>');
function nodes(){const output=shell('shell','uiautomator','dump','/sdcard/source-order-qa.xml');assert(!output.includes('ERROR'),output);const xml=shell('exec-out','cat','/sdcard/source-order-qa.xml');return [...xml.matchAll(/<node\b[^>]*>/g)].map(([tag])=>Object.fromEntries([...tag.matchAll(/([\w-]+)="([^"]*)"/g)].map(([,k,v])=>[k,decode(v)])));}
function snapshot(){const list=nodes();const sources=list.filter(n=>n.class==='android.widget.Button'&&n.text.startsWith('QA ')).map(n=>{const xy=n.bounds.match(/-?\d+/g).map(Number);return {name:n.text.split('\n')[0],focused:n.focused==='true',selected:n.selected==='true',x:xy[0],y:xy[1]};}).sort((a,b)=>a.y-b.y||a.x-b.x);return {order:sources.map(n=>n.name),focused:list.find(n=>n.focused==='true')?.text.split('\n')[0],selected:sources.find(n=>n.selected)?.name};}
const checks=[];const pass=(name,detail)=>{checks.push({name,detail});console.log('PASS '+name+' '+JSON.stringify(detail));};
let passed=false;
const timeout=setTimeout(()=>{ws.close();process.exitCode=1;},60000);
try{
 await evaluate(`(()=>{
  window.__qaOpenCount=0;window.__qaRequest=null;
  const labels=['QA Current 720','QA High 4K','QA Mid 1080','QA Late','QA Unknown','QA Failed'];
  const items=labels.map((name,n)=>({sourceKey:'qa-'+n,sourceName:name,vodId:'test',name:'QA Source Order',pic:''}));
  const heights=[720,2160,1080,0,0,2160],latency=[1,300,100,10,20,1];
  const emit=(id,event,value,error)=>window.LibreTvNativeUi.postMessage(JSON.stringify({version:1,id,event,value,error}));
  const original=window.LibreTvBackend.request.bind(window.LibreTvBackend);
  window.LibreTvBackend.request=r=>{
   if(r.op==='search'){emit(r.id,'result',{groups:[{key:'qa',name:'QA Source Order',items:[items[0]]}],done:true,completed:1,total:1,failures:0});return;}
   if(r.op==='detail'){emit(r.id,'result',{item:items[0],detail:{episodes:['https://fixture.invalid/a.m3u8'],videoInfo:{}}});return;}
   if(r.op==='open-playback'){window.__qaOpenCount++;emit(r.id,'error',null,'片源拒绝访问（HTTP 403），请换源');return;}
   if(r.op==='switch-sources'){
    window.__qaRequest=r.id;emit(r.id,'candidates',items.map(item=>({item})));
    items.forEach((item,n)=>emit(r.id,'source-result',{item,ok:n!==5,episodes:1,height:heights[n],quality:heights[n]===2160?'4K':heights[n]?heights[n]+'P':'未知',ms:latency[n]}));
    emit(r.id,'sources-tested',{count:items.length});return;
   }
   original(r);
  };
  window.__qaSnapshot=()=>{emit(window.__qaRequest,'candidates',items.map(item=>({item})));items.forEach((item,n)=>emit(window.__qaRequest,'source-result',{item,ok:n!==5,episodes:2,height:heights[n],quality:heights[n]===2160?'4K':heights[n]?heights[n]+'P':'未知',ms:latency[n]}));emit(window.__qaRequest,'sources-tested',{count:items.length,cached:true,refreshing:true});};
  window.__qaLate=(index,height)=>emit(window.__qaRequest,'quality-result',{item:items[index],height,quality:height===2160?'4K':height+'P'});
  return true;
 })()`);
 shell('shell','input','text','QA');shell('shell','input','keyevent','66');await wait(300);
 const card=nodes().find(n=>n['content-desc']==='QA Source Order'&&n.focusable==='true');assert(card,'找不到测试卡片');
 const xy=card.bounds.match(/-?\d+/g).map(Number);shell('shell','input','tap',String((xy[0]+xy[2])/2),String((xy[1]+xy[3])/2));shell('shell','input','keyevent','23');await wait(300);
 shell('shell','input','keyevent','23');await wait(400);
 let first=snapshot();assert.deepEqual(first.order,['QA High 4K','QA Mid 1080','QA Current 720','QA Late','QA Unknown','QA Failed']);assert.equal(first.focused,'QA Current 720');pass('按分辨率排序，低清当前源不置顶',first);
 await evaluate('window.__qaLate(3,2160)');await wait(250);
 const late=snapshot();assert.deepEqual(late.order,['QA Late','QA High 4K','QA Mid 1080','QA Current 720','QA Unknown','QA Failed']);assert.equal(late.focused,'QA Current 720');assert.equal(late.selected,'QA Current 720');pass('迟到的4K探测结果重排，焦点保持原来源',late);
 shell('shell','input','keyevent','19','19');await wait(200);const before=snapshot();assert.equal(before.focused,'返回播放');
 await evaluate('window.__qaLate(2,2160)');await wait(250);
 const outside=snapshot();assert.equal(outside.focused,'返回播放');assert.equal(outside.selected,before.selected);assert.deepEqual(outside.order,['QA Late','QA Mid 1080','QA High 4K','QA Current 720','QA Unknown','QA Failed']);pass('重排不抢占列表外按钮焦点',outside);
 await evaluate('window.__qaSnapshot()');await wait(250);
 const refreshed=snapshot();assert.equal(refreshed.order.length,6);assert.equal(new Set(refreshed.order).size,6);assert.equal(refreshed.focused,'返回播放');assert.equal(refreshed.selected,outside.selected);pass('缓存列表后台刷新复用按钮，无重复条目且不抢焦点',refreshed);
 assert.equal(await evaluate('window.__qaOpenCount'),1);pass('方向浏览和画质重排不触发换源播放',{openRequests:1});
 passed=true;
}finally{
 clearTimeout(timeout);ws.close();shell('forward','--remove','tcp:'+port);
 shell('shell','am','force-stop','org.libretv.tv');shell('shell','am','start','-n','org.libretv.tv/.MainActivity');
 writeFileSync(resolve('.desktop-server/source-order-emulator.json'),JSON.stringify({date:new Date().toISOString(),passed,checks},null,2));
}
