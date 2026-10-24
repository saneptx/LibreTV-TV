#!/usr/bin/env node
// Compare actual Android layouts without modifying settings, sources or history.
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
const serials=process.argv.slice(2);assert(serials.length>0,'请提供 ADB 设备序号');
const adb=resolve('android-tv/.android-sdk/platform-tools/adb');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const result=[];
for(const serial of serials){
 const shell=(...args)=>execFileSync(adb,['-s',serial,...args],{encoding:'utf8',timeout:15000,maxBuffer:8*1024**2});
 const activity=()=>shell('shell','dumpsys','activity','activities');
 async function launch(category,stop=false){
  shell('shell','am','start',...(stop?['-S']:[]),'-a','android.intent.action.MAIN','-c','android.intent.category.'+category,'-n','org.libretv.tv/.MainActivity');
  for(let n=0;n<30;n++){const state=activity();if(state.split('\n').some(line=>line.includes('mResumedActivity')&&line.includes('NativeTvActivity'))){
   const instances=[...state.matchAll(/^\s*\* Hist #\d+: ActivityRecord\{[^\n]*org\.libretv\.tv\/\.NativeTvActivity/gm)].length;
   assert.equal(instances,1,`${serial}: 应只保留一个原生界面实例`);return;
  }await sleep(100);}
  throw Error(serial+': 正常启动未进入原生电视界面');
 }
 function nodes(){
  const dump=shell('shell','uiautomator','dump','/sdcard/libretv-ui-compare.xml');assert(!dump.includes('ERROR'),dump);
  return [...shell('exec-out','cat','/sdcard/libretv-ui-compare.xml').matchAll(/<node\b[^>]*>/g)].map(([tag])=>Object.fromEntries([...tag.matchAll(/([\w-]+)="([^"]*)"/g)].map(([,k,v])=>[k,v.replace(/&#(\d+);/g,(_,n)=>String.fromCharCode(Number(n))).replace(/&amp;/g,'&').replace(/&quot;/g,'"')])));
 }
 function anchors(list,selectors){return Object.fromEntries(Object.entries(selectors).map(([key,select])=>{const node=list.find(select);assert(node,`${serial}: 缺少 ${key}`);return[key,node.bounds.match(/-?\d+/g).map(Number)];}));}
 await launch('LEANBACK_LAUNCHER',true);await sleep(800);
 const home=anchors(nodes(),{search:n=>n.class==='android.widget.EditText',submit:n=>n.class==='android.widget.Button'&&n.text==='搜索',manager:n=>n.class==='android.widget.Button'&&n.text==='片源管理'});
 shell('shell','input','keyevent','3');await launch('LAUNCHER');
 shell('shell','input','keyevent','3');await launch('LEANBACK_LAUNCHER');
 shell('shell','input','keyevent','82');await sleep(200);
 const settingsNodes=nodes();
 const settings=anchors(settingsNodes,{close:n=>n.class==='android.widget.Button'&&n.text==='完成',direct:n=>n.text==='独立直连',desktop:n=>n.text==='电脑串流',superResolution:n=>n.class==='android.widget.Button'&&n.text.startsWith('AI 超分：'),connect:n=>n.text==='保存并测试'});
 const navigation=[];
 for(const expected of ['mode','superResolution','server','source']){
  shell('shell','input','keyevent','20');
  const focused=nodes().find(n=>n.focused==='true');assert(focused,serial+': 焦点丢失');
  if(expected==='mode')assert(['独立直连','电脑串流'].includes(focused.text));
  if(expected==='superResolution')assert(focused.text.startsWith('AI 超分：'));
  if(expected==='server')assert.equal(focused.class,'android.widget.EditText');
  if(expected==='source')assert(/^[✓○] /.test(focused.text));
  navigation.push(expected);
 }
 const sourceCells=settingsNodes.filter(n=>n.class==='android.widget.Button'&&/^[✓○] /.test(n.text)).slice(0,4).map(n=>n.bounds.match(/-?\d+/g).map(Number));
 assert.equal(sourceCells.length,4,serial+': 片源列表应为四列');
 shell('shell','input','keyevent','4');
 const record={serial,home,settings,sourceCells,navigation,checks:['普通电视桌面启动','普通应用桌面重开','电视桌面再次重开','单一界面实例','片源设置遥控器入口']};
 result.push(record);console.log('PASS '+JSON.stringify(record));
}
if(result.length>1){for(const item of result.slice(1)){assert.deepEqual(item.home,result[0].home,'同分辨率首页布局应相同');assert.deepEqual(item.settings,result[0].settings,'同分辨率设置布局应相同');assert.deepEqual(item.sourceCells,result[0].sourceCells,'四列片源按钮位置应相同');}console.log('PASS 各设备首页和设置控件位置一致');}
writeFileSync(resolve('.desktop-server/tv-ui-consistency.json'),JSON.stringify({date:new Date().toISOString(),passed:true,result},null,2));
