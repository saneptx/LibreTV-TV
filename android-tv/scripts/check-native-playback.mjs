#!/usr/bin/env node
// 在原生预览已经打开视频后运行；实际操作都经 ADB 遥控器按键，CDP 只读状态及最后恢复现场。
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';

const args=process.argv.slice(2);
let serial='emulator-5554',reportPath;
for(let i=0;i<args.length;i++) {
  if(args[i]==='--serial' && args[i+1]) serial=args[++i];
  else if(args[i]==='--report' && args[i+1]) reportPath=args[++i];
  else throw new Error('用法：node android-tv/scripts/check-native-playback.mjs [--serial 设备] [--report 文件]');
}
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const adb=path.join(root,'android-tv/.android-sdk/platform-tools/adb');
const shell=(...values)=>execFileSync(adb,['-s',serial,...values],{encoding:'utf8',timeout:12000,maxBuffer:4*1024*1024});
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const checks=[];
const pass=(name,detail)=>{ checks.push({name,detail}); console.log('PASS '+name+(detail?' '+JSON.stringify(detail):'')); };
const pid=shell('shell','pidof','org.libretv.tv').trim();
assert(pid,'请先在原生预览中打开视频');
assert(shell('shell','dumpsys','activity','activities').split('\n').some(line=>line.includes('mResumedActivity')&&line.includes('NativeTvActivity')),'当前前台必须是原生电视界面');
const port=shell('forward','tcp:0','localabstract:webview_devtools_remote_'+pid).trim();
let socket,initial,panelOpen=false,reverseChanged=false,passed=false;
let sequence=0;
const pending=new Map();
const send=(method,params)=>new Promise((resolve,reject)=>{
  const id=++sequence; pending.set(id,{resolve,reject}); socket.send(JSON.stringify({id,method,params}));
});
const evaluate=async expression=>{
  const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
  if(result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
  return result.result.value;
};
const state=()=>evaluate('window.__libreTvNativePlayerState');
const key=async(...codes)=>{ shell('shell','input','keyevent',...codes.map(String)); await delay(160); };
const waitFor=async(test,label,timeout=10000)=>{
  const end=Date.now()+timeout;
  do { const value=await test(); if(value) return value; await delay(200); } while(Date.now()<end);
  throw new Error('等待超时：'+label);
};
const decode=text=>text.replace(/&quot;/g,'"').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&');
function ui() {
  const output=shell('shell','uiautomator','dump','/sdcard/libretv-regression.xml');
  assert(!output.includes('ERROR'),'界面未稳定，无法读取焦点');
  const xml=shell('exec-out','cat','/sdcard/libretv-regression.xml');
  return [...xml.matchAll(/<node\b[^>]*>/g)].map(([tag])=>{
    const n=Object.fromEntries([...tag.matchAll(/([\w-]+)="([^"]*)"/g)].map(([,k,v])=>[k,decode(v)]));
    const xy=n.bounds?.match(/-?\d+/g)?.map(Number);
    if(xy) { n.x=(xy[0]+xy[2])/2; n.y=(xy[1]+xy[3])/2; }
    return n;
  });
}
const focused=nodes=>nodes.find(n=>n.focused==='true');
function neighbor(nodes,index,code) {
  const from=nodes[index]; let best=index,score=Infinity;
  nodes.forEach((n,i)=>{
    if(i===index) return;
    const dx=n.x-from.x,dy=n.y-from.y;
    if(!(code===21&&dx< -16 || code===22&&dx>16 || code===19&&dy< -16 || code===20&&dy>16)) return;
    const d=code===21||code===22?Math.abs(dx)+2.4*Math.abs(dy):Math.abs(dy)+2.4*Math.abs(dx);
    if(d<score) {score=d;best=i;}
  });
  return best;
}
async function goTo(label) {
  for(let attempt=0;attempt<12;attempt++) {
    const nodes=ui().filter(n=>n.focusable==='true'&&n.enabled==='true');
    const start=nodes.findIndex(n=>n.focused==='true'),target=nodes.findIndex(n=>n.text===label);
    assert(start>=0&&target>=0,'找不到焦点或按钮：'+label);
    if(start===target) return;
    const seen=new Set([start]),queue=[[start,[]]]; let route;
    while(queue.length&&!route) {
      const [at,steps]=queue.shift();
      for(const code of [21,22,19,20]) {
        const next=neighbor(nodes,at,code);
        if(seen.has(next)) continue;
        const candidate=[...steps,code];
        if(next===target) {route=candidate;break;}
        seen.add(next);queue.push([next,candidate]);
      }
    }
    assert(route,'方向键无法到达：'+label);
    await key(...route);
  }
  throw new Error('无法聚焦：'+label);
}
const timer=setTimeout(()=>{console.error('回归脚本超时');process.exitCode=1;socket?.close();},120000);
try {
  const target=(await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(t=>t.type==='page');
  assert(target,'找不到本地数据页');
  socket=new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject;});
  socket.onclose=()=>{for(const p of pending.values())p.reject(new Error('调试连接已关闭'));pending.clear();};
  socket.onmessage=({data})=>{
    const response=JSON.parse(data),p=pending.get(response.id);if(!p)return;
    pending.delete(response.id); response.error?p.reject(response.error):p.resolve(response.result);
  };
  assert((await evaluate('location.pathname'))==='/native-tv','当前不是原生预览数据页');
  initial=await state();
  assert(initial?.rendered&&initial.duration>60&&!initial.error,'请先打开可正常出画面的长视频');
  pass('Media3 已输出画面',{width:initial.width,height:initial.height});
  if(!initial.paused) await key(85);
  await waitFor(async()=>(await state()).paused,'暂停');
  await key(20);
  assert(focused(ui()).text.includes('继续播放'));
  pass('下键聚焦播放按钮');
  const before=(await state()).position;
  await key(22);
  assert(!focused(ui()).text.includes('继续播放'));
  assert(Math.abs((await state()).position-before)<.2);
  pass('控制栏左右只移动焦点');
  await key(20,19);
  assert(focused(ui())['content-desc'].startsWith('播放进度'));
  await key(22,22);
  assert(Math.abs((await state()).position-before)<.2);
  assert(ui().some(n=>n.text.includes('确认跳转')));
  pass('两次右键只预览进度');
  await key(23);
  const expected=Math.min(before+20,initial.duration-.25);
  await waitFor(async()=>Math.abs((await state()).position-expected)<.4,'确认跳转');
  await key(22,4);
  assert(Math.abs((await state()).position-expected)<.4);
  pass('确认提交、返回取消预览');
  await key(82); panelOpen=true;
  assert(ui().some(n=>n.text==='播放设置'));
  const rates=[.75,1,1.25,1.5,2],index=rates.indexOf(initial.rate);
  assert(index>=0,'倍速不在设置预设中');
  const next=index===rates.length-1?index-1:index+1,code=next>index?22:21;
  await key(code,23);
  await waitFor(async()=>(await state()).rate===rates[next],'应用倍速');
  await key(code===22?21:22,23);
  await waitFor(async()=>(await state()).rate===initial.rate,'还原倍速');
  pass('倍速可应用并还原');
  const other=initial.fit==='contain'?'cover':'contain';
  await goTo(other==='cover'?'铺满屏幕 · 裁切边缘':'原始比例 · 完整显示'); await key(23);
  await waitFor(async()=>(await state()).fit===other,'切换画面比例');
  await goTo(initial.fit==='cover'?'铺满屏幕 · 裁切边缘':'原始比例 · 完整显示'); await key(23);
  await waitFor(async()=>(await state()).fit===initial.fit,'还原画面比例');
  pass('完整显示/裁切铺满可切换并还原');
  await key(4);panelOpen=false;
  assert(focused(ui()).text.includes('继续播放'));
  await key(19,19);panelOpen=true;
  let nodes=ui(); assert(nodes.some(n=>n.text==='选集'));
  assert(focused(nodes).selected==='true');
  pass('控制栏上键到进度条，再上键打开当前集');
  const reverse=nodes.find(n=>n.text==='切为倒序'||n.text==='切为正序');
  if(reverse) {
    await goTo(reverse.text);await key(23);reverseChanged=true;
    assert(focused(ui()).text!==reverse.text);
    await key(23);reverseChanged=false;
    assert(focused(ui()).text===reverse.text);
    pass('正倒序切换保持焦点且可还原');
  }
  await key(4);panelOpen=false;
  assert(focused(ui()).text.includes('继续播放'));
  pass('返回关闭面板并归还播放按钮焦点');
  assert((await state()).sessionId===initial.sessionId,'回归中意外切换了剧集');
  passed=true;
} finally {
  if(socket?.readyState===WebSocket.OPEN&&initial) {
    try {
      if(reverseChanged) await key(23);
      if(panelOpen) await key(4);
      await evaluate(`(function(){const state=window.__libreTvNativePlayerState;if(state&&state.sessionId===${JSON.stringify(initial.sessionId)})for(const command of ${JSON.stringify([{type:'seek',position:initial.position},{type:'rate',rate:initial.rate},{type:'fit',fit:initial.fit},{type:initial.paused?'pause':'play'}])})window.LibreTvNativePlayer.postMessage(JSON.stringify({version:1,sessionId:state.sessionId,...command}));})()`);
      await delay(500);
      pass('恢复测试前进度、倍速、比例与暂停状态');
    } catch(error) {console.error('恢复现场失败：'+error.message);process.exitCode=1;}
  }
  clearTimeout(timer);socket?.close();shell('forward','--remove','tcp:'+port);
  if(reportPath) fs.writeFileSync(reportPath,JSON.stringify({serial,passed:passed&&!process.exitCode,checks},null,2));
}
