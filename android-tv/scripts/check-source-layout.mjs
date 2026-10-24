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
const timeout=setTimeout(()=>{ws.close();process.exitCode=1;},120000);
try{
 await evaluate(`(()=>{
  window.__qaOpenCount=0;window.__qaRequest=null;
  const labels=Array.from({length:37},(_,n)=>'QA 来源 '+n);labels[0]='QA Current 720';labels[1]='QA High 4K';labels[5]='QA 不可用来源（很长的来源名称用于换行检查）';
  const items=labels.map((name,n)=>({sourceKey:'qa-'+n,sourceName:name,vodId:'test',name:'QA Source Order',pic:''}));
  const heights=labels.map((_,n)=>n===1?2160:1080),latency=labels.map((_,n)=>100+n);
  const emit=(id,event,value,error)=>window.LibreTvNativeUi.postMessage(JSON.stringify({version:1,id,event,value,error}));
  const original=window.LibreTvBackend.request.bind(window.LibreTvBackend);
  window.LibreTvBackend.request=r=>{
   if(r.op==='search'){emit(r.id,'result',{groups:[{key:'qa',name:'QA Source Order',items:[items[0]]}],done:true,completed:1,total:1,failures:0});return;}
   if(r.op==='detail'){emit(r.id,'result',{item:items[0],detail:{episodes:['https://fixture.invalid/a.m3u8'],videoInfo:{}}});return;}
   if(r.op==='open-playback'){window.__qaOpenCount++;emit(r.id,'error',null,'片源拒绝访问（HTTP 403），请换源');return;}
   if(r.op==='switch-sources'){
    window.__qaRequest=r.id;emit(r.id,'candidates',items.map(item=>({item})));
    items.forEach((item,n)=>emit(r.id,'source-result',{item,ok:n!==5,episodes:n===5?0:1,error:n===5?'片源拒绝访问（HTTP 403），请重新检测或选择其他来源。'.repeat(8):undefined,height:heights[n],quality:heights[n]===2160?'4K':heights[n]?heights[n]+'P':'未知',ms:latency[n]}));
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

 const box=n=>n.bounds.match(/-?\d+/g).map(Number);
 function layout(){const all=nodes();const footer=all.find(n=>n.text.startsWith('方向键选择')),back=all.find(n=>n.text==='返回播放'),refresh=all.find(n=>n.text==='刷新来源');const scrolls=all.filter(n=>n.class==='android.widget.ScrollView');assert.equal(scrolls.length,2,'换源面板只有左右两个独立滚动区');const viewport=scrolls.find(n=>box(n)[0]<500);const b=box(viewport),f=box(footer);assert(b[3]<f[1]);assert.equal(box(back)[1],box(refresh)[1]);return{all,viewport:b,footer:f,back:box(back)};}
 function capture(name,state){
  const file=resolve('.desktop-server/'+name+'.png');shell('shell','screencap','-p','/sdcard/source-layout.png');shell('pull','/sdcard/source-layout.png',file);
  const points=[200,300,500,700,900].map(x=>[x,Math.floor((state.viewport[3]+state.footer[1])/2)]);
  const pixels=JSON.parse(execFileSync('python3',['-c','from PIL import Image; import json,sys; im=Image.open(sys.argv[1]).convert("RGB"); print(json.dumps([im.getpixel(tuple(p)) for p in json.loads(sys.argv[2])]))',file,JSON.stringify(points)],{encoding:'utf8'}));
  assert(pixels.every(p=>p.every((v,i)=>Math.abs(v-[16,25,37][i])<7)),'列表卡片不应绘制在视口外/页脚间隔里：'+JSON.stringify(pixels));return file;
 }
 const top=layout();const topImage=capture('source-layout-top',top);pass('37来源只在列表视口绘制，页脚间隔没有卡片像素',{viewport:top.viewport,footer:top.footer,image:topImage});
 shell('shell','input','keyevent',...Array(20).fill('20'));await wait(400);
 const bottom=layout(),focus=bottom.all.find(n=>n.focused==='true');assert(focus?.text.startsWith('QA 不可用来源'));const focusedBox=box(focus);assert(focusedBox[1]>=bottom.viewport[1]&&focusedBox[3]<=bottom.viewport[3]-8,'最后来源焦点完整位于列表里');assert.deepEqual(bottom.footer,top.footer);assert.deepEqual(bottom.back,top.back);const bottomImage=capture('source-layout-bottom',bottom);pass('滚动到底可完整看到最后来源，标题页脚位置保持固定',{focused:focus.text,viewport:bottom.viewport,footer:bottom.footer,image:bottomImage});
 shell('shell','input','keyevent','22');await wait(200);assert.equal(nodes().find(n=>n.focused==='true')?.text,'重新检测此来源');pass('右方向键能进入失败来源的重试按钮',{});
 assert.equal(await evaluate('window.__qaOpenCount'),1);pass('滚动和焦点浏览不触发播放或换源',{openRequests:1});
 passed=true;
}finally{
 clearTimeout(timeout);ws.close();shell('forward','--remove','tcp:'+port);
 shell('shell','am','force-stop','org.libretv.tv');shell('shell','am','start','-n','org.libretv.tv/.MainActivity');
 writeFileSync(resolve('.desktop-server/source-layout-emulator.json'),JSON.stringify({date:new Date().toISOString(),passed,checks},null,2));


}
