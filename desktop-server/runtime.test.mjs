import {test} from 'node:test';import assert from 'node:assert/strict';import {mkdtemp,writeFile,rm} from 'node:fs/promises';import {tmpdir} from 'node:os';import {join} from 'node:path';
import {WorkQueue,DiskBudget,run,vulkanFilter} from './runtime.mjs';
test('GPU 队列有界串行，快进任务优先于预取，取消排队任务',async()=>{
 const queue=new WorkQueue(),order=[];let unlock;
 const one=queue.enqueue(async()=>{order.push(1);await new Promise(r=>unlock=r);});
 const controller=new AbortController();const cancelled=queue.enqueue(async()=>order.push(2),controller.signal,10);const rejected=assert.rejects(cancelled,/取消/);controller.abort();
 const prefetch=queue.enqueue(async()=>order.push(3),undefined,10);const seek=queue.enqueue(async()=>order.push(4),undefined,0);
 unlock();await Promise.all([one,prefetch,seek,rejected]);assert.deepEqual(order,[1,4,3]);
});
test('缓存不删除使用中的文件，空间不足明确失败',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'libretv-budget-'));try{
  const cache=new DiskBudget(dir,10),a=join(dir,'a'),b=join(dir,'b');await writeFile(a,'123456');await writeFile(b,'1234');const release=cache.pin(a);
  await cache.reserve(4);assert.equal(await (await import('node:fs/promises')).readFile(a,'utf8'),'123456');await assert.rejects(cache.reserve(8),/已满/);release();await cache.reserve(8);
 }finally{await rm(dir,{recursive:true,force:true});}
});
test('进程取消终止处理工具，不留下后台编码任务',async()=>{
 const controller=new AbortController();const task=run(process.execPath,['-e','setInterval(()=>{},1000)'],{signal:controller.signal});const rejected=assert.rejects(task,{name:'AbortError'});controller.abort();await rejected;
});
test('FSRCNNX 在小于1.3倍的放大时仍执行神经网络',()=>{
 const shader='/tmp/FSRCNNX.glsl';const filter=vulkanFilter({shader},{width:1600,height:900},{width:1920,height:1080},'24/1');assert(filter.includes('w=3200:h=1800'));assert(filter.includes('custom_shader_path'));assert(filter.includes('w=1920:h=1080'));assert(filter.includes('fps=24/1'));
});
test('720P到4K分两次执行2倍网络，1080P到4K只执行一次',()=>{
 const tools={shader:'/tmp/FSRCNNX.glsl'};
 const cascade=vulkanFilter(tools,{width:1280,height:720},{width:3840,height:2160},'24/1');
 assert(cascade.includes('w=2560:h=1440'));assert(cascade.includes('w=3840:h=2160'));
 assert.equal(cascade.match(/custom_shader_path/g).length,2);assert.equal(cascade.match(/fps=/g).length,1);
 const double=vulkanFilter(tools,{width:1920,height:1080},{width:3840,height:2160},'24/1');assert.equal(double.match(/custom_shader_path/g).length,1);
});
