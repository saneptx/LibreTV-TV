import { parseArgs } from 'node:util';
import { mkdir, readFile, writeFile, readdir, rename, chmod } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve, join, basename } from 'node:path';
import { toolConfig, localFfmpeg } from './runtime.mjs';
// 数据目录：电脑端程序通过 SUPERTV_HOME 指定（配对码、同步资料、片库、超分工具都在其下的 .desktop-server）；直接在仓库里运行时为仓库根目录
process.chdir(process.env.SUPERTV_HOME ? resolve(process.env.SUPERTV_HOME) : resolve(import.meta.dirname, '..'));
const exec = promisify(execFile);
const { values } = parseArgs({ options: { quality: { type: 'boolean', default: false } } });
const root = resolve('.desktop-server/tools'); await mkdir(root,{recursive:true,mode:0o700});
async function download(url, file, digest) {
  try { if (createHash('sha256').update(await readFile(file)).digest('hex')===digest) return; } catch {}
  console.log('下载 '+basename(file));
  const response=await fetch(url);if(!response.ok)throw Error('下载失败 '+response.status);
  const data=Buffer.from(await response.arrayBuffer());if(createHash('sha256').update(data).digest('hex')!==digest)throw Error('下载文件 SHA256 不匹配');
  await mkdir(resolve(file,'..'),{recursive:true});await writeFile(file+'.part',data,{mode:0o600});await rename(file+'.part',file);
}
const windows=process.platform==='win32';
// 超分依赖 libplacebo 滤镜：系统 FFmpeg 没有它时（例如 Windows 的 essentials 精简版）改用项目内的完整版
const usable=async(file)=>{try{return (await exec(file,['-hide_banner','-filters'],{maxBuffer:4*1024**2})).stdout.includes('libplacebo');}catch{return false;}};
const local=localFfmpeg(root);
if(!(await usable(local))&&!(await usable('ffmpeg'))){
  if(windows&&process.arch==='x64'){
    console.log('下载并解包 FFmpeg 完整版（含 libplacebo / NVENC）到项目目录；不修改系统软件');
    const archive=join(root,'packages/ffmpeg-7.1.1-full_build-shared.zip');
    await download('https://github.com/GyanD/codexffmpeg/releases/download/7.1.1/ffmpeg-7.1.1-full_build-shared.zip',archive,'9f28727e8b472a04c1d2e520aaa425dca82721b995139b35710091130ea6e699');
    await mkdir(join(root,'ffmpeg'),{recursive:true});
    // Windows 10+ 自带的 tar（bsdtar）可直接解 zip
    await exec(join(process.env.SystemRoot||'C:\\Windows','System32/tar.exe'),['-xf',archive,'-C',join(root,'ffmpeg'),'--strip-components=1'],{timeout:180_000});
  }else if(process.platform==='linux'&&process.arch==='x64'){
    console.log('下载并解包 FFmpeg；不修改系统软件');
    const packages=join(root,'packages');await mkdir(packages,{recursive:true});
    await exec('apt-get',['download','ffmpeg','libavdevice60'],{cwd:packages,maxBuffer:4*1024**2,timeout:180_000});
    for(const name of await readdir(packages))if(name.endsWith('.deb'))await exec('dpkg-deb',['-x',join(packages,name),join(root,'ffmpeg')]);
  }else throw Error('请先安装 FFmpeg 6+（含 libplacebo），本地自动配置仅支持 Ubuntu 24 x64 和 Windows x64');
  if(!(await usable(local)))throw Error('FFmpeg 解包后仍无法使用 libplacebo，请检查 '+local);
}
await download('https://github.com/igv/FSRCNN-TensorFlow/releases/download/1.1/FSRCNNX_x2_8-0-4-1.glsl',join(root,'shaders/FSRCNNX_x2_8-0-4-1.glsl'),'e800dbc5c1c95185cc82216c597724533ff5f2880179f256eef600f03e8dc2ae');
if(values.quality&&windows)throw Error('高质量模式（Real-ESRGAN）的自动配置暂不支持 Windows；默认的 FSRCNNX 超分不受影响');
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
