import { parseArgs } from 'node:util';
import { mkdir, readFile, writeFile, readdir, rename, chmod } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve, join } from 'node:path';
import { toolConfig } from './runtime.mjs';
process.chdir(resolve(import.meta.dirname, '..'));
const exec = promisify(execFile);
const { values } = parseArgs({ options: { quality: { type: 'boolean', default: false } } });
const root = resolve('.desktop-server/tools'); await mkdir(root,{recursive:true,mode:0o700});
async function download(url, file, digest) {
  try { if (createHash('sha256').update(await readFile(file)).digest('hex')===digest) return; } catch {}
  console.log('下载 '+file.split('/').pop());
  const response=await fetch(url);if(!response.ok)throw Error('下载失败 '+response.status);
  const data=Buffer.from(await response.arrayBuffer());if(createHash('sha256').update(data).digest('hex')!==digest)throw Error('下载文件 SHA256 不匹配');
  await mkdir(resolve(file,'..'),{recursive:true});await writeFile(file+'.part',data,{mode:0o600});await rename(file+'.part',file);
}
let installed=false;try{await exec('ffmpeg',['-version']);installed=true;}catch{}
if(!installed){
  const local=join(root,'ffmpeg/usr/bin/ffmpeg');try{await readFile(local);installed=true;}catch{}
  if(!installed){
    if(process.platform!=='linux'||process.arch!=='x64')throw Error('请先安装 FFmpeg 6+（含 libplacebo），本地自动配置仅支持 Ubuntu 24 x64');
    console.log('下载并解包 FFmpeg；不修改系统软件');
    const packages=join(root,'packages');await mkdir(packages,{recursive:true});
    await exec('apt-get',['download','ffmpeg','libavdevice60'],{cwd:packages,maxBuffer:4*1024**2,timeout:180_000});
    for(const name of await readdir(packages))if(name.endsWith('.deb'))await exec('dpkg-deb',['-x',join(packages,name),join(root,'ffmpeg')]);
  }
}
await download('https://github.com/igv/FSRCNN-TensorFlow/releases/download/1.1/FSRCNNX_x2_8-0-4-1.glsl',join(root,'shaders/FSRCNNX_x2_8-0-4-1.glsl'),'e800dbc5c1c95185cc82216c597724533ff5f2880179f256eef600f03e8dc2ae');
if(values.quality){
  const dir=join(root,'realesrgan');await mkdir(dir,{recursive:true});
  // Release and official general-scene checkpoint are kept outside Git, with their source versions pinned.
  await download('https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-ncnn-vulkan-20220424-ubuntu.zip',join(dir,'release.zip'),'e5aa6eb131234b87c0c51f82b89390f5e3e642b7b70f2b9bbe95b6a285a40c96');
  await exec('python3',['-c',`import zipfile,sys,pathlib
root=pathlib.Path(sys.argv[2]).resolve()
with zipfile.ZipFile(sys.argv[1]) as z:
 for info in z.infolist():
  if not (root/info.filename).resolve().is_relative_to(root):raise ValueError('unsafe archive')
 z.extractall(root)`,join(dir,'release.zip'),dir]);
  await chmod(join(dir,'realesrgan-ncnn-vulkan'),0o755);
  await download('https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth',join(dir,'realesr-general-x4v3.pth'),'8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292');
  await exec('python3',['desktop-server/scripts/convert-srvgg.py',join(dir,'realesr-general-x4v3.pth'),join(dir,'models/realesr-general-x4v3')]);
}
const tools = await toolConfig();
console.log(`环境检测通过：${tools.model} Vulkan 超分 / ${tools.encoder} 编码。`);
console.log('处理依赖准备完成。执行 npm run desktop:server；高质量模式使用 --model realesr-general-x4v3。');
