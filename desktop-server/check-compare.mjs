#!/usr/bin/env node
// 多来源比对端到端检查：两个本地 HLS 来源，其中一个在连续编码中夹了一段广告（无分段边界、同目录）。
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, rm, readFile, mkdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { run, toolConfig, DiskBudget } from './runtime.mjs';
import { CompareJob } from './compare.mjs';
process.chdir(resolve(import.meta.dirname, '..'));
const dir = await mkdtemp(join(tmpdir(), 'libretv-compare-')), tools = await toolConfig();
const ffmpeg = (...args) => run(tools.ffmpeg, ['-hide_banner', '-loglevel', 'error', ...args], { env: tools.env, timeout: 300_000 });
let server;
try {
  // 正片音频：随机种子噪声经多段带通，听起来像连续变化的环境声；两个来源取自同一份
  const base = join(dir, 'base.mkv'), ad = join(dir, 'ad.mkv');
  await ffmpeg('-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=24:d=600', '-f', 'lavfi', '-i', 'anoisesrc=d=600:c=pink:seed=42:a=0.3,lowpass=f=3000,volume=enable=\'lt(mod(t,7),3)\':volume=0.3',
    '-c:v', 'libx264', '-preset', 'ultrafast', '-c:a', 'aac', '-shortest', '-y', base);
  await ffmpeg('-f', 'lavfi', '-i', 'smptebars=size=320x180:rate=24:d=20', '-f', 'lavfi', '-i', 'sine=f=880:d=20,volume=0.5', '-c:v', 'libx264', '-preset', 'ultrafast', '-c:a', 'aac', '-shortest', '-y', ad);
  const hls = async (name, inputs, filter) => {
    await mkdir(join(dir, name));
    await ffmpeg(...inputs.flatMap((i) => ['-i', i]), ...(filter ? ['-filter_complex', filter, '-map', '[v]', '-map', '[a]'] : []), '-c:v', 'libx264', '-preset', 'ultrafast', '-g', '48', '-c:a', 'aac',
      '-f', 'hls', '-hls_time', '6', '-hls_list_size', '0', '-hls_segment_filename', join(dir, name, '%03d.ts'), '-y', join(dir, name, 'index.m3u8'));
  };
  await hls('clean', [base]);
  // 在 312 秒处插入 20 秒广告，整体一次编码：没有 DISCONTINUITY，分片目录也相同
  await hls('dirty', [base, ad], '[0:v]trim=0:312,setpts=PTS-STARTPTS[v0];[0:a]atrim=0:312,asetpts=PTS-STARTPTS[a0];[0:v]trim=312,setpts=PTS-STARTPTS[v1];[0:a]atrim=312,asetpts=PTS-STARTPTS[a1];[v0][a0][1:v][1:a][v1][a1]concat=n=3:v=1:a=1[v][a]');
  server = http.createServer(async (req, res) => {
    try { const body = await readFile(join(dir, ...new URL(req.url, 'http://x').pathname.split('/').filter(Boolean))); res.end(body); }
    catch { res.writeHead(404); res.end(); }
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const open = (url, range, signal) => new Promise((resolve, reject) => {
    const req = http.get(url, { signal, headers: range ? { Range: `bytes=${range.start}-${range.end}` } : {} }, (response) => resolve({ response, url }));
    req.on('error', reject);
  });
  const root = join(dir, 'cache'), started = performance.now();
  const job = new CompareJob({ tools, budget: new DiskBudget(root, 2 * 1024 ** 3), root, runner: run }, { id: 'compare-check', headers: {} },
    { primary: { url: `${origin}/dirty/index.m3u8` }, references: [`${origin}/clean/index.m3u8`] }, { open });
  await job.running;
  const status = job.status(), seconds = (performance.now() - started) / 1000;
  console.log(JSON.stringify({ ...status, seconds: Math.round(seconds * 10) / 10 }));
  assert.equal(status.state, 'done', status.error);
  assert.equal(status.intervals.length, 1, JSON.stringify(status.intervals));
  assert(Math.abs(status.intervals[0].start - 312) < 1.5 && Math.abs(status.intervals[0].duration - 20) < .5, JSON.stringify(status.intervals));
  console.log(`PASS 夹在连续编码中的 20 秒广告定位到 ${status.intervals[0].start}s，长度 ${status.intervals[0].duration}s，测量 ${status.measurements} 次，用时 ${seconds.toFixed(1)}s`);
  const reverse = new CompareJob({ tools, budget: new DiskBudget(root, 2 * 1024 ** 3), root, runner: run }, { id: 'compare-check-2', headers: {} },
    { primary: { url: `${origin}/clean/index.m3u8` }, references: [`${origin}/dirty/index.m3u8`] }, { open });
  await reverse.running;
  assert.equal(reverse.status().state, 'done'); assert.deepEqual(reverse.status().intervals, []);
  console.log('PASS 参考来源多出的片段不会被当成当前来源的广告');
} finally { server?.close(); await rm(dir, { recursive: true, force: true }); }
