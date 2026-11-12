// 电脑片库：把整集按播放时的处理链（去广告、超分、去水印、按设备能力编码）处理完并长期保存。
// 下载在后台逐段进行，优先级低于正在播放的串流；服务重启后续传。已完成的影片播放时直接读取，不再实时处理。
import { mkdir, readdir, readFile, writeFile, rename, rm, stat, copyFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { randomBytes, createHash } from 'node:crypto';
import { exists } from './runtime.mjs';

const uid = () => randomBytes(16).toString('hex');
/** 同一片源与处理设置只保存一份 */
export const libraryKey = (input) => createHash('sha256').update(JSON.stringify([input.url, input.superResolution !== false, input.adFilter !== false, input.overlayRemoval !== false])).digest('hex');

export function createLibrary({ processor, dir = '.desktop-server/library', network, retryDelayMs = 2000 }) {
  const root = resolve(dir), items = new Map(), queue = [];
  let running, closed = false;
  const folder = (item) => join(root, item.id);
  const save = async (item) => {
    const { job, ...meta } = item; const file = join(folder(item), 'meta.json');
    await writeFile(file + '.part', JSON.stringify(meta), { mode: 0o600 }); await rename(file + '.part', file);
  };
  const view = ({ job, input, ...item }) => ({ ...item, url: input.url });

  async function process(item) {
    const session = { id: 'download-' + item.id.slice(0, 12), headers: {}, requests: new Set(), closed: false };
    // 下载可能持续很久，设备列表里的签名地址会过期：一律由电脑自己读取列表，并启用电脑的重复插入规则
    const input = { ...item.input, position: 0, playlists: undefined, adSegments: undefined, repeatFilter: true };
    const job = processor.create(session, input, network(session));
    item.job = job; item.state = 'preparing'; item.error = undefined;
    try {
      await job.preparing;
      // 关闭服务或删除时准备阶段会被中止：直接返回，不能把未下载的条目当成已完成
      if (closed || item.cancelled) return;
      if (job.info.state !== 'ready' || !(job.duration > 0)) throw new Error(job.info.error || '电脑处理失败');
      const count = Math.ceil((job.duration - .000001) / job.chunkDuration);
      Object.assign(item, { state: 'downloading', duration: job.duration, chunkDuration: job.chunkDuration, segments: count, timeMap: job.timeMap,
        label: job.label, timeline: item.timeline ?? (item.done ? 'segmented' : 'continuous'), processingInfo: job.info.processingInfo, output: job.info.output, input: { ...item.input }, sourceInfo: job.info.input });
      await save(item);
      for (let n = 0; n < count; n++) {
        if (closed || item.cancelled) return;
        const target = join(folder(item), `${n}.ts`);
        if (!(await exists(target))) {
          if (n % 4 === 0) job.prefetchSource?.(n + 1, 6);
          // 整集下载耗时长，片源偶尔断开单个连接：每段最多重试 3 次（2、4、8 秒后），仍失败才记为失败
          let file;
          for (let attempt = 0; ; attempt++) {
            try { file = await job.segment(n, false, true); break; }
            catch (error) {
              if (closed || item.cancelled || attempt >= 3) throw error;
              await new Promise((r) => setTimeout(r, retryDelayMs * 2 ** attempt));
            }
          }
          await copyFile(file, target + '.part'); await rename(target + '.part', target);
          item.bytes = (item.bytes || 0) + (await stat(target)).size;
        }
        item.done = n + 1;
        if (n % 5 === 4 || n === count - 1) await save(item);
      }
      if (closed || item.cancelled) return;
      item.state = 'done'; item.finishedAt = Date.now(); await save(item);
    } catch (error) {
      if (closed || item.cancelled) return;
      item.state = 'error'; item.error = error?.status ? error.message : error?.message || '电脑处理失败'; await save(item).catch(() => {});
    } finally { job.close(); item.job = undefined; }
  }
  async function drain() {
    if (running) return;
    while (queue.length && !closed) {
      const item = queue.shift();
      if (!items.has(item.id) || item.cancelled) continue;
      running = process(item); await running; running = undefined;
    }
  }

  return {
    async load() {
      await mkdir(root, { recursive: true, mode: 0o700 });
      for (const name of await readdir(root)) {
        try {
          const item = JSON.parse(await readFile(join(root, name, 'meta.json'), 'utf8'));
          if (!item?.id || item.id !== name) continue;
          // 统计已完成的分段，续传未完成的下载
          item.bytes = 0; item.done = 0;
          for (const file of await readdir(join(root, name))) if (/^\d+\.ts$/.test(file)) { item.bytes += (await stat(join(root, name, file))).size; item.done++; }
          items.set(item.id, item);
          if (item.state !== 'done' || !(item.segments > 0) || item.done < item.segments) { item.state = 'queued'; queue.push(item); }
        } catch { /* 损坏的目录忽略 */ }
      }
      void drain();
    },
    list: () => [...items.values()].sort((a, b) => b.createdAt - a.createdAt).map(view),
    get: (id) => items.get(id),
    /** 与播放请求匹配的已完成影片 */
    find(input) { const key = libraryKey(input); return [...items.values()].find((item) => item.key === key && item.state === 'done'); },
    async add(input, info = {}) {
      const key = libraryKey(input), existing = [...items.values()].find((item) => item.key === key);
      if (existing) { if (existing.state === 'error') { existing.state = 'queued'; queue.push(existing); void drain(); } return view(existing); }
      const item = { id: uid(), key, input, title: String(info.title || '').slice(0, 120), subtitle: String(info.subtitle || '').slice(0, 120), meta: info.meta, state: 'queued', done: 0, bytes: 0, createdAt: Date.now() };
      await mkdir(folder(item), { recursive: true, mode: 0o700 }); await save(item);
      items.set(item.id, item); queue.push(item); void drain();
      return view(item);
    },
    async remove(id) {
      const item = items.get(id); if (!item) return false;
      item.cancelled = true; item.job?.close(); items.delete(id);
      await rm(folder(item), { recursive: true, force: true });
      return true;
    },
    file: (item, n) => join(folder(item), `${n}.ts`),
    close() { closed = true; for (const item of items.values()) item.job?.close(); },
  };
}
