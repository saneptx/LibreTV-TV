#!/usr/bin/env node
// Uses real enabled sources; never replaces network responses or changes source settings.
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';
const serial=process.argv[2] || 'emulator-5554';
const adb=resolve('android-tv/.android-sdk/platform-tools/adb');
const shell=(...args)=>execFileSync(adb,['-s',serial,...args],{encoding:'utf8',timeout:15000});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
let ws,port,seq=0;const pending=new Map(),checks=[];
async function evaluate(expression){const id=++seq;const result=await new Promise((resolve,reject)=>{pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression,awaitPromise:true,returnByValue:true}}));});if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));return result.result.value;}
async function connect(){
 for(let n=0;n<30;n++){
  try{
   const pid=shell('shell','pidof','org.libretv.tv').trim();assert(pid);
   port=shell('forward','tcp:0','localabstract:webview_devtools_remote_'+pid).trim();
   const page=(await(await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(p=>p.type==='page');assert(page);
   ws=new WebSocket(page.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
   ws.onmessage=({data})=>{const m=JSON.parse(data),p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(m.error):p.resolve(m.result);}};
   if(!await evaluate('!!window.LibreTvBackend'))throw Error('Backend starting');
   await evaluate(`(()=>{
    window.__cacheQaEvents=[];window.__cacheQaNetwork=0;
    const emit=window.LibreTvNativeUi.postMessage.bind(window.LibreTvNativeUi);
    window.__cacheQaOriginalEmit=emit;
    window.LibreTvNativeUi.postMessage=raw=>{const e=JSON.parse(raw);window.__cacheQaEvents.push({...e,at:performance.now()});emit(raw);};
    const http=window.LibreTvNativeHttp.postMessage.bind(window.LibreTvNativeHttp);
    window.__cacheQaOriginalHttp=http;
    window.LibreTvNativeHttp.postMessage=raw=>{if(JSON.parse(raw).type==='fetch')window.__cacheQaNetwork++;http(raw);};
    return true;
   })()`);return;
  }catch(error){ws?.close();if(port)shell('forward','--remove','tcp:'+port);port=undefined;if(n===29)throw error;await sleep(500);}
 }
}
async function disconnect(){if(ws?.readyState===1)await evaluate(`(()=>{window.LibreTvNativeUi.postMessage=window.__cacheQaOriginalEmit;window.LibreTvNativeHttp.postMessage=window.__cacheQaOriginalHttp;delete window.__cacheQaEvents;return true;})()`);ws?.close();if(port)shell('forward','--remove','tcp:'+port);port=undefined;}
async function rpc(op,args){
 const id='cache-qa-'+Date.now();await evaluate(`(()=>{window.__cacheQaStart=performance.now();window.__cacheQaNetwork=0;window.__cacheQaEvents=[];window.LibreTvBackend.request(${JSON.stringify({id,op,args})});return true;})()`);
 const deadline=Date.now()+200000;let printed=false;
 while(Date.now()<deadline){
  const result=await evaluate(`(()=>{const es=window.__cacheQaEvents.filter(e=>e.id===${JSON.stringify(id)}),list=es.find(e=>e.event==='sources-tested'&&(!${JSON.stringify(args.refresh === true)}||!e.value.cached)),done=es.find(e=>e.event==='result'||e.event==='error');return {done:!!done,error:done?.error,value:done?.value,list:list?{...list.value,ms:Math.round(list.at-window.__cacheQaStart)}:null,requests:window.__cacheQaNetwork,totalMs:Math.round(performance.now()-window.__cacheQaStart)};})()`);
  if(result.list&&!printed){console.log('LIST '+JSON.stringify(result.list));printed=true;}
  if(result.done){if(result.error)throw Error(result.error);return result;}
  await sleep(500);
 }
 throw Error('真实来源请求超时');
}
try{
 await connect();const home=await rpc('home',{});const title=home.value.history[0]?.title || '庆余年 第二季';
 console.log('CONFIG '+JSON.stringify({enabled:home.value.config.enabled,mode:home.value.config.playbackMode,title}));
 const cold=await rpc('switch-sources',{title,refresh:process.argv.includes('--refresh-first')});assert(cold.list?.count>0,'没有可验证的来源');checks.push({name:'首次真实来源检查',...cold,value:undefined});console.log('COLD '+JSON.stringify(checks.at(-1)));
 const warm=await rpc('switch-sources',{title});assert.equal(warm.list.cached,true);assert.equal(warm.requests,0);checks.push({name:'再次打开复用缓存且无网络请求',...warm,value:undefined});console.log('WARM '+JSON.stringify(checks.at(-1)));
 await sleep(500);await disconnect();shell('shell','am','force-stop','org.libretv.tv');shell('shell','am','start','-n','org.libretv.tv/.MainActivity');await connect();
 await rpc('home',{});const reopened=await rpc('switch-sources',{title});assert.equal(reopened.list.cached,true);assert.equal(reopened.requests,0);checks.push({name:'重启应用仍复用持久化缓存',...reopened,value:undefined});console.log('RESTART '+JSON.stringify(checks.at(-1)));
 writeFileSync(resolve('.desktop-server/source-cache-'+serial.replace(/[^a-z0-9]/gi,'-')+'.json'),JSON.stringify({date:new Date().toISOString(),title,checks},null,2));
}finally{await disconnect().catch(()=>{});}
