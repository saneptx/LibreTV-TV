import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./codec-parse-core', () => ({
  looksLikeMp4: () => false,
  parseMp4VideoResolution: () => undefined,
  // 测试分片前 24 字节写入特征文本，其余补零
  parseTsSignature: (buf: Uint8Array) => new TextDecoder().decode(buf.subarray(0, 24)).replace(/\0+$/, '') || undefined,
  // 第 300 字节起写入首帧时间戳（秒）
  parseTsFirstPts: (buf: Uint8Array) => { const text = new TextDecoder().decode(buf.subarray(300, 340)).replace(/\0+$/, ''); return text ? Number(text) : undefined; },
}));

import { analyzePlaylist, decideAds, heuristicRemovedLines, learnAdAt, loadLearnedAds, playlistGroups, saveLearnedAd, clearLearnedAds, type AdTransport } from './ad-analysis';

const segment = (sig: string, salt = '', pts?: number) => {
  const buf = new Uint8Array(400); buf.set(new TextEncoder().encode(sig)); buf.set(new TextEncoder().encode(salt), 100);
  if (pts !== undefined) buf.set(new TextEncoder().encode(String(pts)), 300);
  return buf;
};
function transport(playlists: Record<string, string>, segments: Record<string, Uint8Array>): AdTransport & { fetched: string[] } {
  const fetched: string[] = [];
  return {
    fetched,
    fetchText: async (url) => playlists[url] === undefined ? undefined : { text: playlists[url], url },
    fetchPrefix: async (url) => { fetched.push(url); return segments[url]; },
    decrypt: async (buf) => buf,
  };
}
const group = (dir: string, seconds: number, count: number, disc = true) =>
  (disc ? '#EXT-X-DISCONTINUITY\n' : '') + Array.from({ length: count }, (_, i) => `#EXTINF:${seconds},\n${dir}/${i}.ts`).join('\n');
const BASE = 'https://cdn.test/v/index.m3u8';
const content = (name: string) => `https://cdn.test/v/${name}`;

describe('playlistGroups', () => {
  it('按 DISCONTINUITY 和分片目录切分，并记录原片起点', () => {
    const text = `#EXTM3U\n${group('main', 10, 3, false)}\n${group('https://ads.test/x', 5, 3, false)}\n${group('main2', 10, 2)}\n#EXT-X-ENDLIST`;
    const { groups, total } = playlistGroups(text, BASE);
    expect(groups.map((g) => [g.start, g.duration])).toEqual([[0, 30], [30, 15], [45, 20]]);
    expect(groups[1].firstUrl).toBe('https://ads.test/x/0.ts');
    expect(total).toBe(65);
  });

  it('目录逐片变化时退回只按 DISCONTINUITY 切分', () => {
    const lines = Array.from({ length: 40 }, (_, i) => `#EXTINF:4,\nhttps://hash.test/${i}/seg.ts`).join('\n');
    expect(playlistGroups(`#EXTM3U\n${lines}\n#EXT-X-ENDLIST`, BASE).groups).toHaveLength(1);
  });
});

describe('heuristicRemovedLines', () => {
  it('返回常规启发式删除的分片行', () => {
    const text = `#EXTM3U\n${group('main', 60, 3, false)}\n${group('/ads/a', 5, 2)}\n${group('main2', 60, 3)}\n#EXT-X-ENDLIST`;
    expect(heuristicRemovedLines(text)).toEqual(['/ads/a/0.ts', '/ads/a/1.ts']);
  });
});

describe('编码特征比对', () => {
  const text = `#EXTM3U\n${group('p1', 10, 30, false)}\n${group('https://x.test/spot', 5, 6)}\n${group('p2', 10, 30)}\n#EXT-X-ENDLIST`;
  const segs = { [content('p1/0.ts')]: segment('ts|1b,f|1920x1080'), [content('p2/0.ts')]: segment('ts|1b,f|1920x1080'), 'https://x.test/spot/0.ts': segment('ts|1b,f|1280x720', 'ad') };

  it('短段组的编码与正片主体不同 → 判为广告', async () => {
    const media = await analyzePlaylist(BASE, transport({ [BASE]: text }, segs));
    const decision = decideAds(media, []);
    expect(decision.extraLines).toEqual(Array.from({ length: 6 }, (_, i) => `https://x.test/spot/${i}.ts`));
    expect(decision.codecGroups).toBe(1);
  });

  it('特征相同或缺失时不删除', async () => {
    const same = { ...segs, 'https://x.test/spot/0.ts': segment('ts|1b,f|1920x1080') };
    expect(decideAds(await analyzePlaylist(BASE, transport({ [BASE]: text }, same)), []).extraLines).toEqual([]);
    const missing = { ...segs, 'https://x.test/spot/0.ts': segment('') };
    expect(decideAds(await analyzePlaylist(BASE, transport({ [BASE]: text }, missing)), []).extraLines).toEqual([]);
  });

  it('超过 90 秒的不同编码段不按编码判定', async () => {
    const long = `#EXTM3U\n${group('p1', 10, 60, false)}\n${group('https://x.test/spot', 10, 12)}\n${group('p2', 10, 60)}\n#EXT-X-ENDLIST`;
    expect(decideAds(await analyzePlaylist(BASE, transport({ [BASE]: long }, segs)), []).extraLines).toEqual([]);
  });

  it('长段很多时以已探测的长段为准，最长段特征一致才判定', async () => {
    const parts = Array.from({ length: 7 }, (_, i) => `${group(`c${i}`, 10, 150, i > 0)}\n${group(`https://x.test/s${i}`, 5, 3)}`).join('\n');
    const many = `#EXTM3U\n${parts}\n#EXT-X-ENDLIST`;
    const all: Record<string, Uint8Array> = {};
    for (let i = 0; i < 7; i++) { all[content(`c${i}/0.ts`)] = segment('ts|1b,f|1280x538'); all[`https://x.test/s${i}/0.ts`] = segment('ts|1b,f|1920x1080', String(i)); }
    expect(decideAds(await analyzePlaylist(BASE, transport({ [BASE]: many }, all)), []).codecGroups).toBe(7);
  });

  it('同一非正片目录、时长相同的短段组重复出现 → 按重复插入删除（编码相同也能识别）', async () => {
    const repeated = `#EXTM3U\n${group('p1', 10, 30, false)}\n${group('https://x.test/spot', 5.86, 3)}\n${group('p2', 10, 30)}\n${group('https://x.test/spot', 5.86, 3)}\n${group('p3', 10, 30)}\n#EXT-X-ENDLIST`;
    const same = { [content('p1/0.ts')]: segment('ts|1b|1920x1080'), [content('p2/0.ts')]: segment('ts|1b|1920x1080'), [content('p3/0.ts')]: segment('ts|1b|1920x1080'), 'https://x.test/spot/0.ts': segment('ts|1b|1920x1080') };
    const decision = decideAds(await analyzePlaylist(BASE, transport({ [BASE]: repeated }, same)), []);
    expect(decision).toMatchObject({ repeatGroups: 2, codecGroups: 0 });
    const once = `#EXTM3U\n${group('p1', 10, 30, false)}\n${group('https://x.test/spot', 5.86, 3)}\n${group('p2', 10, 30)}\n${group('https://x.test/other', 5.86, 3)}\n${group('p3', 10, 30)}\n#EXT-X-ENDLIST`;
    expect(decideAds(await analyzePlaylist(BASE, transport({ [BASE]: once }, same)), []).repeatGroups).toBe(0);
  });

  it('主列表分析各档位并合并', async () => {
    const master = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhd/index.m3u8';
    const hd = 'https://cdn.test/v/hd/index.m3u8';
    const hdSegs = { 'https://cdn.test/v/hd/p1/0.ts': segs[content('p1/0.ts')], 'https://cdn.test/v/hd/p2/0.ts': segs[content('p2/0.ts')], 'https://x.test/spot/0.ts': segs['https://x.test/spot/0.ts'] };
    const media = await analyzePlaylist(BASE, transport({ [BASE]: master, [hd]: text }, hdSegs));
    expect(media.map((m) => m.url)).toEqual([hd]);
    expect(decideAds(media, []).codecGroups).toBe(1);
  });

  it('非 m3u8 或读取失败时不分析', async () => {
    expect(await analyzePlaylist('https://cdn.test/a.mp4', transport({}, {}))).toEqual([]);
    expect(await analyzePlaylist(BASE, transport({}, {}))).toEqual([]);
  });
});

describe('时间轴接续', () => {
  // 30 组、每组 5 个 2 秒分片（段组很多，不建完整指纹）；编码全部相同
  const build = (insertAt: number, adSeconds: number, resume = true) => {
    const parts: string[] = [], segs: Record<string, Uint8Array> = {};
    let start = 0;
    for (let k = 0; k < 30; k++) {
      const ad = k === insertAt, seconds = ad ? adSeconds : 10, count = seconds / 2;
      parts.push(group(`c${k}`, 2, count, k > 0));
      // 正片时间戳 = 原片位置 + 1.4（插入之后整体少掉广告的长度）；广告从 1.4 秒重新开始
      const shift = k > insertAt && resume ? -adSeconds : k > insertAt ? 500 : 0;
      segs[content(`c${k}/0.ts`)] = segment('ts|1b,f|1920x1080', String(k), ad ? 1.4 : start + 1.4 + shift);
      start += seconds;
    }
    return { text: `#EXTM3U\n${parts.join('\n')}\n#EXT-X-ENDLIST`, segs };
  };

  it('插入的短段自带时间戳、之后正片接回插入前的位置 → 判为广告（编码相同、段组很多也能识别）', async () => {
    const { text, segs } = build(12, 24);
    const decision = decideAds(await analyzePlaylist(BASE, transport({ [BASE]: text }, segs)), []);
    expect(decision).toMatchObject({ timelineGroups: 1, codecGroups: 0 });
    expect(decision.extraLines).toEqual(Array.from({ length: 12 }, (_, i) => `c12/${i}.ts`));
  });

  it('插入点前面正片不足 90 秒（含开头插播）→ 只看时间戳无法区分，不删除', async () => {
    for (const at of [0, 5]) {
      const { text, segs } = build(at, 24);
      expect(decideAds(await analyzePlaylist(BASE, transport({ [BASE]: text }, segs)), []).extraLines).toEqual([]);
    }
  });

  it('时间轴换了但没有接回（多个文件拼接） → 不删除', async () => {
    const { text, segs } = build(12, 24, false);
    expect(decideAds(await analyzePlaylist(BASE, transport({ [BASE]: text }, segs)), []).extraLines).toEqual([]);
  });

  it('段组很多时分层抽查，只读插入点附近的组', async () => {
    const parts: string[] = [], segs: Record<string, Uint8Array> = {};
    let start = 0;
    for (let k = 0; k < 400; k++) {
      const ad = k === 233, seconds = ad ? 30 : 4;
      parts.push(group(`c${k}`, 2, seconds / 2, k > 0));
      segs[content(`c${k}/0.ts`)] = segment('ts|1b,f|1920x1080', String(k), ad ? 0.7 : start + 0.7 - (k > 233 ? 30 : 0));
      start += seconds;
    }
    const t = transport({ [BASE]: `#EXTM3U\n${parts.join('\n')}\n#EXT-X-ENDLIST` }, segs);
    const decision = decideAds(await analyzePlaylist(BASE, t), []);
    expect(decision.timelineGroups).toBe(1);
    expect(decision.extraLines).toEqual(Array.from({ length: 15 }, (_, i) => `c233/${i}.ts`));
    expect(t.fetched.length).toBeLessThan(60);
  });

  it('插入段超过 90 秒不按时间轴判定；读不到时间戳的组不参与', async () => {
    const { text, segs } = build(12, 100);
    expect(decideAds(await analyzePlaylist(BASE, transport({ [BASE]: text }, segs)), []).timelineGroups).toBe(0);
    const short = build(12, 24);
    short.segs[content('c13/0.ts')] = segment('ts|1b,f|1920x1080');
    expect(decideAds(await analyzePlaylist(BASE, transport({ [BASE]: short.text }, short.segs)), []).timelineGroups).toBe(0);
  });
});

describe('广告学习', () => {
  beforeEach(() => {
    const store = new Map<string, string>();
    vi.stubGlobal('localStorage', { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => store.set(k, v), removeItem: (k: string) => store.delete(k) });
  });
  const text = (adDir: string) => `#EXTM3U\n${group('p1', 10, 30, false)}\n${group(adDir, 5, 4)}\n${group('p2', 10, 30)}\n#EXT-X-ENDLIST`;
  const sameCodec = (adDir: string, salt: string) => ({ [content('p1/0.ts')]: segment('ts|1b|1920x1080'), [content('p2/0.ts')]: segment('ts|1b|1920x1080'), [`${adDir}/0.ts`]: segment('ts|1b|1920x1080', salt) });

  it('标记后同一广告文件在其他影片中也会被删除', async () => {
    const first = await analyzePlaylist(BASE, transport({ [BASE]: text('https://a.test/ad') }, sameCodec('https://a.test/ad', 'same-file')));
    expect(decideAds(first, []).extraLines).toEqual([]);
    const { entry, group: marked } = await learnAdAt(first, 302, transport({}, {}));
    expect([marked.start, marked.duration]).toEqual([300, 20]);
    const learned = saveLearnedAd(entry);
    expect(loadLearnedAds()).toHaveLength(1);
    const other = await analyzePlaylist(BASE, transport({ [BASE]: text('https://b.test/ad') }, sameCodec('https://b.test/ad', 'same-file')));
    expect(decideAds(other, learned)).toMatchObject({ learnedGroups: 1, extraLines: ['https://b.test/ad/0.ts', 'https://b.test/ad/1.ts', 'https://b.test/ad/2.ts', 'https://b.test/ad/3.ts'] });
    clearLearnedAds();
    expect(loadLearnedAds()).toEqual([]);
  });

  it('同一广告目录、同特征、相近时长也算命中；正片目录不参与', async () => {
    const first = await analyzePlaylist(BASE, transport({ [BASE]: text('https://a.test/ad') }, sameCodec('https://a.test/ad', 'one')));
    const { entry } = await learnAdAt(first, 305, transport({}, {}));
    expect(entry.dir).toBe('https://a.test/ad/');
    const again = await analyzePlaylist(BASE, transport({ [BASE]: text('https://a.test/ad') }, sameCodec('https://a.test/ad', 'two')));
    expect(decideAds(again, [entry]).learnedGroups).toBe(1);
  });

  it('拒绝标记正片长段或没有分段的列表', async () => {
    const media = await analyzePlaylist(BASE, transport({ [BASE]: text('https://a.test/ad') }, sameCodec('https://a.test/ad', 'x')));
    await expect(learnAdAt(media, 100, transport({}, {}))).rejects.toThrow('更像正片');
    const single = await analyzePlaylist(BASE, transport({ [BASE]: `#EXTM3U\n${group('p1', 10, 3, false)}\n#EXT-X-ENDLIST` }, {}));
    await expect(learnAdAt(single, 5, transport({}, {}))).rejects.toThrow('同一个编码段');
  });
});
