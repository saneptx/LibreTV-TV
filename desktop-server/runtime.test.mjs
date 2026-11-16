import {test} from 'node:test';import assert from 'node:assert/strict';import {mkdtemp,writeFile,rm} from 'node:fs/promises';import {tmpdir} from 'node:os';import {join} from 'node:path';
import {WorkQueue,DiskBudget,run,vulkanFilter,encodingProfile,videoEncoderOptions} from './runtime.mjs';
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
test('请求已排队的预取分片时提升优先级，不必等前面的其他预取完成',async()=>{
 const queue=new WorkQueue(),order=[];let unlock;
 const running=queue.enqueue(()=>new Promise(r=>unlock=r));
 const a=new AbortController(),b=new AbortController();
 const one=queue.enqueue(async()=>order.push(1),a.signal,10),two=queue.enqueue(async()=>order.push(2),b.signal,10);
 queue.promote(b.signal);unlock();await Promise.all([running,one,two]);assert.deepEqual(order,[2,1]);
});
test('4K与1080P码率受限，宽屏和高帧率使用对应编码档位',()=>{
 const fourK=encodingProfile({width:3840,height:2160},24),wide=encodingProfile({width:3840,height:1600},24),fullHD=encodingProfile({width:1920,height:1080},24);
 assert.deepEqual(fourK,{average:12000000,peak:20000000,buffer:20000000});assert.deepEqual(wide,fourK);
 assert.equal(encodingProfile({width:854,height:480},24).peak,3000000);
 assert.equal(fullHD.peak,8000000);assert.equal(encodingProfile({width:3840,height:2160},60).peak,30000000);
 for(const encoder of ['h264_nvenc','libx264']){const args=videoEncoderOptions(encoder,fourK);assert.equal(args[args.indexOf('-maxrate')+1],'20000000');assert.equal(args[args.indexOf('-bufsize')+1],'20000000');}
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
test('按设备硬解能力选择 HEVC，未上报或能力不足时回落 H.264，HEVC 码率更低',async()=>{
 const { chooseCodec, encodingProfile } = await import('./runtime.mjs');
 const tools={encoder:'h264_nvenc',codecs:{h264:'h264_nvenc',hevc:'hevc_nvenc'}};
 const k310={hevc:{maxWidth:4096,maxHeight:2304,fps1080:60,fps2160:60}};
 assert.deepEqual(chooseCodec(tools,k310,{width:3840,height:2160},29.97),{codec:'hevc',encoder:'hevc_nvenc'});
 assert.deepEqual(chooseCodec(tools,undefined,{width:1920,height:1080},24),{codec:'h264',encoder:'h264_nvenc'});
 assert.equal(chooseCodec(tools,{hevc:{maxWidth:1920,maxHeight:1088,fps1080:60,fps2160:0}},{width:3840,height:2160},24).codec,'h264');
 assert.equal(chooseCodec(tools,{hevc:{maxWidth:4096,maxHeight:2304,fps1080:60,fps2160:30}},{width:3840,height:2160},60).codec,'h264');
 assert.equal(chooseCodec({encoder:'h264_nvenc',codecs:{h264:'h264_nvenc'}},k310,{width:1920,height:1080},24).codec,'h264');
 const h264=encodingProfile({width:3840,height:2160},24),hevc=encodingProfile({width:3840,height:2160},24,'hevc');
 assert.equal(hevc.average,Math.round(h264.average*.6));assert(hevc.peak<h264.peak);
});

test('滤镜路径：Linux 路径原样，Windows 盘符路径转正斜杠并转义冒号', async () => {
 const { filterPath } = await import('./runtime.mjs');
 assert.equal(filterPath('/tmp/m.pgm'), '/tmp/m.pgm');
 assert.equal(filterPath('/tmp/a.glsl', true), "'/tmp/a.glsl'");
 assert.equal(filterPath('C:\\Users\\a b\\m.pgm'), "'C\\:/Users/a b/m.pgm'");
 assert.throws(() => filterPath("/tmp/a'b"));
});
