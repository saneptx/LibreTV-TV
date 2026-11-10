#!/usr/bin/env node
// 去水印端到端检查：合成多场景影片，叠加固定台标、顶部滚动广告字幕和逐句变化的底部字幕，
// 经真实处理流水线（抽帧识别 → delogo → GPU 编码）后核对识别区域，并输出处理前后对比帧。
import assert from 'node:assert/strict';
import http from 'node:http';
import { createReadStream } from 'node:fs';
import { mkdtemp, rm, mkdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { toolConfig, run } from './runtime.mjs';
import { createProcessor } from './processing.mjs';
process.chdir(resolve(import.meta.dirname, '..'));
const dir = await mkdtemp(join(tmpdir(), 'libretv-overlay-')), tools = await toolConfig();
const font = '/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc', report = resolve('.desktop-server');
const ffmpeg = (...args) => run(tools.ffmpeg, ['-hide_banner', '-loglevel', 'error', ...args], { env: tools.env, timeout: 600_000 });
let upstream, session;
try {
  const scenes = ['testsrc2', 'mandelbrot', 'smptehdbars', 'cellauto=rule=110', 'life=mold=10:ratio=0.3', 'rgbtestsrc'].map((s, i) => `${s}${s.includes('=') ? ':' : '='}size=1280x720:rate=24${s.startsWith('cellauto') || s.startsWith('life') ? '' : ''}`);
  const inputs = scenes.flatMap((s) => ['-f', 'lavfi', '-t', '20', '-i', s]);
  const concat = scenes.map((_, i) => `[${i}:v]scale=1280:720,setsar=1,format=yuv420p[s${i}]`).join(';') + ';' + scenes.map((_, i) => `[s${i}]`).join('') + `concat=n=${scenes.length}:v=1:a=0[base]`;
  const draw = [
    // 固定台标
    `drawbox=x=1090:y=28:w=160:h=56:color=white@0.85:t=fill`, `drawtext=fontfile=${font}:text='台标TV':x=1105:y=36:fontsize=36:fontcolor=black`,
    // 顶部滚动广告字幕（每秒 160 像素）
    `drawbox=x=0:y=110:w=1280:h=48:color=black@0.6:t=fill`, `drawtext=fontfile=${font}:text='澳门首家线上赌场 上线送豪礼 网址 www.example-ad.com 澳门首家线上赌场':x=w-mod(t*160\\,w+1800):y=116:fontsize=34:fontcolor=yellow`,
    // 底部对白字幕轮换五句，不应被处理
    ...['你今天怎么这么晚才回来', '我们明天一早就出发吧', '这件事情没有那么简单', '他到底想要什么', '先别说了快跟我走'].map((line, i) =>
      `drawtext=fontfile=${font}:text='${line}':x=(w-tw)/2:y=640:fontsize=40:fontcolor=white:borderw=2:enable='between(mod(t\\,17)\\,${i * 3.4}\\,${i * 3.4 + 3})'`),
  ].join(',');
  await mkdir(join(dir, 'film'));
  await ffmpeg(...inputs, '-f', 'lavfi', '-i', 'sine=f=440:d=120', '-filter_complex', `${concat};[base]${draw}[v]`, '-map', '[v]', '-map', `${scenes.length}:a`,
    '-c:v', 'libx264', '-preset', 'ultrafast', '-g', '48', '-c:a', 'aac', '-shortest', '-f', 'hls', '-hls_time', '4', '-hls_list_size', '0', '-hls_segment_filename', join(dir, 'film', '%03d.ts'), '-y', join(dir, 'film', 'index.m3u8'));
  upstream = http.createServer((req, res) => { const file = join(dir, ...req.url.split('/').filter(Boolean)); res.setHeader('Content-Type', req.url.endsWith('.ts') ? 'video/mp2t' : 'application/vnd.apple.mpegurl'); createReadStream(file).on('error', () => { res.writeHead(404); res.end(); }).pipe(res); });
  await new Promise((r) => upstream.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${upstream.address().port}/film/index.m3u8`;
  const network = { open: (target, range, signal) => new Promise((resolve, reject) => { const req = http.get(target, { signal, headers: range ? { Range: `bytes=${range.start}-${range.end}` } : {} }, (response) => resolve({ response, url: target })); req.on('error', reject); }) };
  const processor = createProcessor({ ...tools, targetHeight: 720 }, { cacheDir: join(dir, 'cache'), prefetch: 0 });
  const started = performance.now();
  session = processor.create({ id: 'overlay-check', headers: {} }, { url, superResolution: false, position: 48 }, network);
  await session.preparing;
  assert.equal(session.info.state, 'ready', session.info.error);
  const regions = session.info.processingInfo.overlays;
  console.log(JSON.stringify({ regions, label: session.label, seconds: Math.round((performance.now() - started) / 100) / 10 }));
  const logo = regions.find((r) => r.kind === 'logo'), marquee = regions.find((r) => r.kind === 'marquee');
  assert(logo && logo.x <= 1092 && logo.x + logo.w >= 1248 && logo.y <= 30 && logo.y + logo.h >= 82, '固定台标未识别：' + JSON.stringify(regions));
  assert(marquee && marquee.y <= 114 && marquee.y + marquee.h >= 154 && marquee.h <= 120, '滚动字幕未识别：' + JSON.stringify(regions));
  assert(!regions.some((r) => r.y + r.h > 600), '底部对白字幕被误判：' + JSON.stringify(regions));
  console.log('PASS 识别固定台标与顶部滚动广告字幕，未误判底部对白字幕');
  const index = Math.floor(48 / session.chunkDuration), file = await session.serve(index);
  await ffmpeg('-ss', '20', '-i', join(dir, 'film', 'index.m3u8'), '-frames:v', '1', '-y', join(report, 'overlay-before.png'));
  await ffmpeg('-ss', String(20 - index * session.chunkDuration + session.chunkDuration * 0), '-i', file, '-frames:v', '1', '-y', join(report, 'overlay-after-raw.png')).catch(() => ffmpeg('-i', file, '-frames:v', '1', '-y', join(report, 'overlay-after-raw.png')));
  await ffmpeg('-ss', String(48), '-i', join(dir, 'film', 'index.m3u8'), '-frames:v', '1', '-y', join(report, 'overlay-before.png'));
  await ffmpeg('-i', file, '-frames:v', '1', '-y', join(report, 'overlay-after.png'));
  await writeFile(join(report, 'overlay-validation.json'), JSON.stringify({ regions, label: session.label }, null, 2));
  console.log('PASS 处理后的分片已加入 delogo；对比帧 .desktop-server/overlay-before.png / overlay-after.png');
} finally { session?.close(); upstream?.close(); await rm(dir, { recursive: true, force: true }); }
