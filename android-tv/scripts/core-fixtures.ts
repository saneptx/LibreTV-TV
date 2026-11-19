// 用网页版（TypeScript）实现生成对照数据，供 Kotlin 数据层的 ParityTest 逐项比对。
// 在仓库根目录运行：npx vite-node android-tv/scripts/core-fixtures.ts
import { writeFileSync } from 'node:fs';
import { isRelevant, normalizeTitle, extractEpisodesFromPlayUrl, parseDetail, parseDetailPageHtml, parseSearchList, isAdultContent } from '../../src/lib/cms-parser';
import { formatRemarks, inferGroupRemarks } from '../../src/lib/remarks';
import { qualityLabel, mergeQualityEntries } from '../../src/lib/quality';
import { prefixHash, playlistGroups, heuristicRemovedLines, decideAds, timelineAdGroups, type AdGroup, type MediaAnalysis } from '../../src/lib/ad-analysis';
import { parseTsVideoResolution, parseTsSignature, parseTsFirstPts, parseMp4VideoResolution, looksLikeMp4 } from '../../src/lib/codec-parse-core';
import { parseBestHeight, parseMediaPlaylist, probeEpisodeOrder } from '../../src/lib/quality-probe-core';
import { parseSubscriptionJson, parseSubscriptionPayload } from '../../src/lib/tvbox-parser';
import { subKeyPrefix } from '../../src/lib/store';
import { sourcePosition, sourceDuration, desktopAddress } from '../../src/lib/desktop-playback';
import { aggregateResults } from '../../src/lib/result-groups';
import { parseProcessingInfo, processingPresentation } from '../../src/lib/playback-processing';
/** 位写入器：手写 SPS 的二进制极易出错，用语法元素逐项构造 */
class BitWriter {
  private readonly bits: number[] = [];

  writeBits(value: number, n: number): void {
    for (let i = n - 1; i >= 0; i--) this.bits.push((value >> i) & 1);
  }

  writeUE(value: number): void {
    const v = value + 1;
    const width = v.toString(2).length;
    for (let i = 0; i < width - 1; i++) this.bits.push(0);
    this.writeBits(v, width);
  }

  toBytes(): Uint8Array {
    const bytes: number[] = [];
    for (let i = 0; i < this.bits.length; i += 8) {
      let byte = 0;
      for (let j = 0; j < 8; j++) byte = (byte << 1) | (this.bits[i + j] ?? 0);
      bytes.push(byte);
    }
    return new Uint8Array(bytes);
  }
}

interface SpsOptions {
  profileIdc?: number;
  widthInMbs: number;
  heightInMapUnits: number;
  frameMbsOnly?: boolean;
  cropBottom?: number;
  scalingLists?: boolean;
}

/** 构造一个最小可用的 H.264 SPS（写到 vui_parameters_present_flag 为止） */
function buildSps(opts: SpsOptions): Uint8Array {
  const profileIdc = opts.profileIdc ?? 100;
  const frameMbsOnly = opts.frameMbsOnly ?? true;
  const w = new BitWriter();
  w.writeBits(profileIdc, 8);
  w.writeBits(0, 8); // constraint flags + reserved
  w.writeBits(40, 8); // level_idc
  w.writeUE(0); // seq_parameter_set_id

  if (profileIdc === 100) {
    w.writeUE(1); // chroma_format_idc = 4:2:0
    w.writeUE(0); // bit_depth_luma_minus8
    w.writeUE(0); // bit_depth_chroma_minus8
    w.writeBits(0, 1); // qpprime_y_zero_transform_bypass_flag
    w.writeBits(opts.scalingLists ? 1 : 0, 1);
    if (opts.scalingLists) {
      // 8 组，前 6 组 16 项、后 2 组 64 项，全部 delta_scale = 0（等价于 ue(0)）
      for (let i = 0; i < 8; i++) {
        w.writeBits(1, 1); // seq_scaling_list_present_flag
        const size = i < 6 ? 16 : 64;
        for (let j = 0; j < size; j++) w.writeUE(0);
      }
    }
  }

  w.writeUE(0); // log2_max_frame_num_minus4
  w.writeUE(0); // pic_order_cnt_type
  w.writeUE(2); // log2_max_pic_order_cnt_lsb_minus4
  w.writeUE(2); // max_num_ref_frames
  w.writeBits(0, 1); // gaps_in_frame_num_value_allowed_flag
  w.writeUE(opts.widthInMbs - 1);
  w.writeUE(opts.heightInMapUnits - 1);
  w.writeBits(frameMbsOnly ? 1 : 0, 1);
  if (!frameMbsOnly) w.writeBits(1, 1); // mb_adaptive_frame_field_flag
  w.writeBits(1, 1); // direct_8x8_inference_flag
  const cropping = (opts.cropBottom ?? 0) > 0;
  w.writeBits(cropping ? 1 : 0, 1);
  if (cropping) {
    w.writeUE(0);
    w.writeUE(0);
    w.writeUE(0);
    w.writeUE(opts.cropBottom!);
  }
  w.writeBits(1, 1); // vui_parameters_present_flag（之后的内容我们不再解析）
  return w.toBytes();
}

function concat(parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}
// ——— MPEG-TS ———

const TS_SIZE = 188;

function buildTsPacket(pid: number, payload: Uint8Array, payloadStart: boolean): Uint8Array {
  const packet = new Uint8Array(TS_SIZE).fill(0xff);
  packet[0] = 0x47;
  packet[1] = (payloadStart ? 0x40 : 0x00) | ((pid >> 8) & 0x1f);
  packet[2] = pid & 0xff;
  packet[3] = 0x10; // 含载荷、无自适应字段
  packet.set(payload.subarray(0, TS_SIZE - 4), 4);
  return packet;
}

/** 把 PES 拆成多个 TS 包（超过单包容量时续包 payloadStart=0） */
function packetize(pid: number, pes: Uint8Array): Uint8Array[] {
  const packets: Uint8Array[] = [];
  let offset = 0;
  let first = true;
  while (offset < pes.length) {
    packets.push(buildTsPacket(pid, pes.subarray(offset, offset + TS_SIZE - 4), first));
    offset += TS_SIZE - 4;
    first = false;
  }
  return packets;
}

function buildSection(tableId: number, body: number[], sectionLength: number): Uint8Array {
  return concat([new Uint8Array([0]), new Uint8Array([tableId, 0xb0, sectionLength]), new Uint8Array(body)]);
}

function buildPat(pmtPid: number): Uint8Array {
  // program_number=1 + PMT PID + 4 字节 CRC，section_length = 13
  return buildSection(0x00, [0x00, 0x01, 0xc1, 0x00, 0x00, 0x00, 0x01, 0xe0 | (pmtPid >> 8), pmtPid & 0xff, 0, 0, 0, 0], 0x0d);
}

function buildPmt(videoPid: number, streamType = 0x1b): Uint8Array {
  const pidHigh = 0xe0 | ((videoPid >> 8) & 0x1f);
  const pidLow = videoPid & 0xff;
  // 2(program) + 1(version) + 2(section) + 2(PCR) + 2(prog_info_len) + 5(stream) + 4(CRC) = 18
  return buildSection(0x02, [0x00, 0x01, 0xc1, 0x00, 0x00, pidHigh, pidLow, 0xf0, 0x00, streamType, pidHigh, pidLow, 0xf0, 0x00, 0, 0, 0, 0], 0x12);
}

function buildPes(es: Uint8Array): Uint8Array {
  const headerLength = 0;
  const pes = new Uint8Array(9 + headerLength + es.length);
  pes.set([0x00, 0x00, 0x01, 0xe0], 0); // 起始码 + 视频 stream_id
  const length = headerLength + es.length;
  pes[4] = (length >> 8) & 0xff;
  pes[5] = length & 0xff;
  pes[6] = 0x80;
  pes[7] = 0x00;
  pes[8] = headerLength;
  pes.set(es, 9 + headerLength);
  return pes;
}

function buildTsWithSps(sps: Uint8Array): Uint8Array {
  const es = concat([
    new Uint8Array([0x00, 0x00, 0x00, 0x01]),
    concat([new Uint8Array([0x67]), sps]),
    new Uint8Array([0x00, 0x00, 0x00, 0x01]),
    new Uint8Array([0x68, 0xeb, 0xe3, 0xcb]),
  ]);
  return concat([
    buildTsPacket(0x0000, buildPat(0x1000), true),
    buildTsPacket(0x1000, buildPmt(0x0100), true),
    ...packetize(0x0100, buildPes(es)),
  ]);
}

  const pesWithPts = (streamId: number, seconds: number) => {
    const pts = Math.round(seconds * 90000);
    const pes = new Uint8Array(14 + 4);
    pes.set([0x00, 0x00, 0x01, streamId, 0x00, 0x00, 0x80, 0x80, 0x05], 0);
    pes.set([0x21 | (Math.floor(pts / 2 ** 29) & 0x0e), Math.floor(pts / 2 ** 22) & 0xff, (Math.floor(pts / 2 ** 14) & 0xfe) | 1, Math.floor(pts / 2 ** 7) & 0xff, ((pts * 2) & 0xfe) | 1], 9);
    pes.set([0x00, 0x00, 0x00, 0x01], 14);
    return pes;
  };
function box(type: string, payload: Uint8Array): Uint8Array {
  const buf = new Uint8Array(8 + payload.length);
  const size = buf.length;
  buf[0] = (size >>> 24) & 0xff;
  buf[1] = (size >>> 16) & 0xff;
  buf[2] = (size >>> 8) & 0xff;
  buf[3] = size & 0xff;
  buf.set([type.charCodeAt(0), type.charCodeAt(1), type.charCodeAt(2), type.charCodeAt(3)], 4);
  buf.set(payload, 8);
  return buf;
}

function buildVisualEntry(type: string, width: number, height: number): Uint8Array {
  const payload = new Uint8Array(40);
  payload[24] = (width >> 8) & 0xff;
  payload[25] = width & 0xff;
  payload[26] = (height >> 8) & 0xff;
  payload[27] = height & 0xff;
  return box(type, payload);
}

function buildMoov(width: number, height: number, entryType = 'avc1'): Uint8Array {
  // stsd 是 full box：version/flags(4) + entry_count(4) 之后再放 sample entry
  const stsdPayload = concat([new Uint8Array([0, 0, 0, 0, 0, 0, 0, 1]), buildVisualEntry(entryType, width, height)]);
  return box('moov', box('trak', box('mdia', box('minf', box('stbl', box('stsd', stsdPayload))))));
}
const hex = (b: Uint8Array) => Buffer.from(b).toString('hex');
const tryOr = <T>(f: () => T) => { try { return { value: f() }; } catch (e) { return { error: (e as Error).message }; } };
const out: Record<string, unknown> = {};

const names = ['摔跤吧！爸爸', '摔跤吧!爸爸【影视解说】', '摔跤吧 爸爸', '爸爸去哪儿第三季', '爸爸当家 第五季', '钢铁侠', '钢铁侠 Iron Man 2008', '庆余年第二季', '庆余年 第二季', '庆余年', '庆余年2', '庆余年 2', '电影第二季', '电影2', '任意标题', '', ' ！ ', '2012', '21克', 'Dune: Part Two', '沙丘2', '沙丘 第2季', '权力的游戏 第十季', '权力的游戏第十二季（附花絮）', '一拳超人 第三季', '1', 'Iron Man 3', '速度与激情10', '速度与激情1', '第二季'];
const queries = ['摔跤吧！爸爸', '爸爸去哪儿', '钢铁侠 2008', '庆余年2', '庆余年 第二季', '庆余年', '电影2', '', '任意', '1', '2012', 'dune part two', '沙丘2', '权力的游戏 第十季', '权力的游戏12', '一拳超人3', 'iron man', '速度与激情1', '第二季', '庆余年 第2季', '  '];
out.relevance = names.flatMap((name) => queries.map((query) => ({ name, query, expected: isRelevant(name, query) })));
out.normalize = [...names, 'ＡＢＣ　１２３', 'Hello, World! — 你好·世界', 'a_b-c+d=e|f~g'].map((s) => ({ input: s, expected: normalizeTitle(s) }));
const playUrls = ['第1集$http://a.com/1.m3u8#第2集$http://a.com/2.m3u8', '正片$https://x/1.mp4$$$正片$https://y/1.m3u8#2$https://y/2.m3u8', '', 'a$ftp://x#b$', '1$http://a/1.mp4#2$http://a/2.mp4$$$1$http://b/1.mp4', 'noname', '1$http://a/x.m3u8$extra'];
out.episodes = playUrls.map((input) => ({ input, expected: extractEpisodesFromPlayUrl(input) }));
const src = { key: 'k1', name: '源一', url: 'https://api.example.com/api.php/provide/vod', isAdult: false };
const detailData = { list: [{ vod_id: 12, vod_name: ' 片名 ', vod_pic: 'http://p/1.jpg', vod_content: '<p>简介</p>', type_name: '剧情', vod_year: '2024', vod_area: '', vod_director: '甲', vod_actor: '乙,丙', vod_remarks: '更新至10集', vod_play_url: playUrls[1] }] };
out.detail = { data: detailData, source: src, expected: parseDetail(detailData, src), contentOnly: parseDetail({ list: [{ vod_name: 'x', vod_content: '看 $https://c/v/index.m3u8 和 $http://d/2.m3u8' }] }, src) };
const page = '<h1 class="t"> 标题 </h1><div class="sketch">一段<b>简介</b></div><a>1$https://cdn/20240101/123_abcdef/index.m3u8(备用)</a><a>2$https://cdn/a/2.m3u8</a><a>2$https://cdn/a/2.m3u8</a>';
out.detailPage = { html: page, source: src, expected: parseDetailPageHtml(page, src) };
const searchData = { pagecount: 3, list: [{ vod_id: 7, vod_name: '甲', vod_pic: 'p', type_name: '电影', vod_year: 2020, vod_remarks: 'HD' }, { vod_id: 'x9', vod_name: '乙', vod_year: '2021', vod_area: '中国' }] };
out.searchList = { data: searchData, source: src, expected: parseSearchList(searchData, src) };
out.adult = ['伦理片', '剧情', '日本无码', undefined, 'SWAG精选', '国产剧'].map((t) => ({ input: t ?? null, expected: isAdultContent(t) }));
const remarks = ['全34集', '完结', '更新至34集', '共 12 集', '12集全', 'HD', '', undefined, '大结局', '第5集', '更新至 8 集', '已完结', '40集全 国语', '正片', '全集'];
out.remarks = remarks.map((r) => ({ input: r ?? null, expected: formatRemarks(r) ?? null }));
out.remarkGroups = [['全34集', '完结', '更新至34集'], ['更新至3集', '更新至10集'], ['HD', '正片'], ['完结', '更新至5集'], [undefined, ''], ['第5集', 'HD']].map((g) => ({ input: g.map((x) => x ?? null), expected: inferGroupRemarks(g) ?? null }));
out.qualityLabels = [0, -5, 240, 300, 360, 400, 420, 480, 500, 520, 540, 600, 640, 700, 720, 800, 804, 900, 1000, 1080, 1200, 1300, 1440, 1600, 1800, 1900, 2160, 2400, 3000, 4320].map((h) => ({ input: h, expected: qualityLabel(h) ?? null }));
const now = 1_800_000_000_000;
const qCurrent = { a: { from: 'play', height: 720, timestamp: now - 1000 }, b: { from: 'probe', height: 1080, timestamp: now - 2000 }, c: { from: 'probe', error: 'x', timestamp: now - 10 * 60_000 }, d: { from: 'probe', timestamp: now - 40 * 24 * 3600_000, height: 480 }, e: { from: 'probe', timestamp: now - 5 } } as const;
const qIncoming = { a: { from: 'probe', height: 1080, timestamp: now }, b: { from: 'probe', timestamp: now }, e: { from: 'play', height: 800, timestamp: now }, f: { from: 'play', height: 2160, timestamp: now }, a2: { from: 'play', height: 1080, timestamp: now } } as const;
out.qualityMerge = { now, current: qCurrent, incoming: qIncoming, expected: mergeQualityEntries(qCurrent as never, qIncoming as never, now), again: mergeQualityEntries(mergeQualityEntries(qCurrent as never, qIncoming as never, now), { a: { from: 'play', height: 480, timestamp: now } } as never, now) };
const bytes = [new Uint8Array(0), new Uint8Array([1, 2, 3]), Uint8Array.from({ length: 256 }, (_, i) => i), Uint8Array.from({ length: 5000 }, (_, i) => (i * 131 + 7) & 0xff), new Uint8Array(1000).fill(0xff)];
out.prefixHash = bytes.map((b) => ({ hex: hex(b), expected: prefixHash(b) }));

const head = [buildTsPacket(0x0000, buildPat(0x1000), true), buildTsPacket(0x1000, buildPmt(0x0100), true)];
const samples: Record<string, Uint8Array> = {
  ts1080: buildTsWithSps(buildSps({ widthInMbs: 120, heightInMapUnits: 68, cropBottom: 4 })),
  ts720: buildTsWithSps(buildSps({ widthInMbs: 80, heightInMapUnits: 45 })),
  tsBaseline: buildTsWithSps(buildSps({ profileIdc: 66, widthInMbs: 40, heightInMapUnits: 30 })),
  tsInterlaced: buildTsWithSps(buildSps({ widthInMbs: 120, heightInMapUnits: 34, frameMbsOnly: false, cropBottom: 2 })),
  tsScaling: buildTsWithSps(buildSps({ widthInMbs: 64, heightInMapUnits: 36, scalingLists: true })),
  tsOffset: concat([new Uint8Array([1, 2, 3, 4, 5]), buildTsWithSps(buildSps({ widthInMbs: 80, heightInMapUnits: 45 }))]),
  ptsVideo: concat([...head, buildTsPacket(0x0101, pesWithPts(0xc0, 11.2), true), buildTsPacket(0x0100, pesWithPts(0xe0, 363.41), true)]),
  ptsAudioOnly: concat([...head, buildTsPacket(0x0101, pesWithPts(0xc0, 11.2), true)]),
  ptsLarge: concat([...head, buildTsPacket(0x0100, pesWithPts(0xe0, 60000), true)]),
  junk: new Uint8Array(600).fill(0x5a),
  moov1080: buildMoov(1920, 1080),
  moovHevc: buildMoov(3840, 2160, 'hvc1'),
  ftyp: concat([box('ftyp', new Uint8Array(8)), buildMoov(1280, 720)]),
  truncated: buildMoov(1920, 1080).subarray(0, 60),
  short: new Uint8Array([0x47, 0, 0]),
};
out.codec = Object.entries(samples).map(([name, b]) => ({ name, hex: hex(b), ts: parseTsVideoResolution(b) ?? null, sig: parseTsSignature(b) ?? null, pts: parseTsFirstPts(b) ?? null, mp4: parseMp4VideoResolution(b) ?? null, looksMp4: looksLikeMp4(b) }));

const seg = (dir: string, from: number, n: number, d = 6) => Array.from({ length: n }, (_, i) => `#EXTINF:${d},\n${dir}${from + i}.ts`).join('\n');
const D = '#EXT-X-DISCONTINUITY';
const lists: { name: string; base: string; text: string }[] = [
  { name: 'plain', base: 'https://cdn.a.com/v/1/index.m3u8', text: `#EXTM3U\n#EXT-X-TARGETDURATION:6\n${seg('', 0, 5)}\n#EXT-X-ENDLIST` },
  { name: 'leadAd', base: 'https://cdn.a.com/v/1/index.m3u8', text: `#EXTM3U\n${D}\n${seg('https://ad.x.com/p/', 0, 3, 5)}\n${D}\n${seg('', 0, 60)}\n#EXT-X-ENDLIST` },
  { name: 'interstitial', base: 'https://cdn.a.com/v/1/index.m3u8', text: `#EXTM3U\n${seg('', 0, 30)}\n${D}\n${seg('/x/ad', 0, 3, 5)}\n${D}\n${seg('', 30, 30)}\n${D}\n${seg('/x/ad', 0, 3, 5)}\n${D}\n${seg('', 60, 40)}\n#EXT-X-ENDLIST` },
  { name: 'adUrl', base: 'http://h/a/b.m3u8?t=1', text: `#EXTM3U\n${seg('m', 0, 10, 10)}\n${D}\n#EXTINF:8,\nhttp://h/adjump/1.ts\n#EXTINF:8,\nhttp://h/adjump/2.ts\n${D}\n${seg('m', 10, 10, 10)}` },
  { name: 'keys', base: 'https://k.com/p/list.m3u8', text: `#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n${seg('s', 0, 3)}\n${D}\n#EXT-X-KEY:METHOD=AES-128,URI="/k2.bin",IV=0x00000000000000000000000000ABCDEF\n${seg('t', 0, 2)}\n${D}\n#EXT-X-KEY:METHOD=NONE\n#EXT-X-MAP:URI="init.mp4"\n${seg('u', 0, 2)}` },
  { name: 'dirSplit', base: 'https://cdn.a.com/v/1/index.m3u8', text: `#EXTM3U\n${seg('a/', 0, 40)}\n${seg('https://other.com/z/', 0, 2, 7.5)}\n${seg('a/', 40, 40)}\n${seg('https://other.com/z/', 0, 2, 7.5)}\n${seg('a/', 80, 10)}` },
  { name: 'manyShort', base: 'https://cdn.a.com/v/1/index.m3u8', text: `#EXTM3U\n${Array.from({ length: 30 }, (_, i) => seg(`d${i}/`, 0, 2)).join('\n')}` },
  { name: 'typicalGroups', base: 'https://cdn.a.com/v/1/index.m3u8', text: `#EXTM3U\n${Array.from({ length: 6 }, (_, i) => `${D}\n${seg(`p${i}_`, 0, 10)}`).join('\n')}` },
  { name: 'weird', base: 'https://cdn.a.com/v/1/index.m3u8', text: `#EXTM3U\r\n#EXTINF:abc,\r\n  x1.ts  \r\n\r\n#EXTINF:4.004,title\r\nx2.ts\r\n#EXT-X-ENDLIST\r\n` },
];
const view = (g: AdGroup) => ({ start: g.start, duration: g.duration, lines: g.lines, firstUrl: g.firstUrl, dir: g.dir, keyUri: g.key?.uri ?? null, iv: g.key ? hex(g.key.iv) : null, initUrl: g.initUrl ?? null });
out.playlists = lists.map((l) => { const r = playlistGroups(l.text, l.base); return { ...l, total: r.total, groups: r.groups.map(view), heuristic: heuristicRemovedLines(l.text) }; });

// 判定：给段组指定指纹 / 时间戳后比较结论
const mk = (start: number, duration: number, dir: string, extra: Partial<AdGroup> = {}): AdGroup => ({ start, duration, lines: [`${dir}${start}.ts`], firstUrl: `${dir}${start}.ts`, dir, ...extra });
const cases: { name: string; groups: AdGroup[]; contentDir?: string; heuristic: string[]; learned: { hash: string; sig?: string; dir?: string; duration: number; at: number }[] }[] = [
  { name: 'codec', contentDir: 'm/', heuristic: ['h1.ts'], learned: [], groups: [mk(0, 600, 'm/', { sig: 'ts|1b,f|1920x1080' }), mk(600, 30, 'm/', { sig: 'ts|1b,f|1280x720' }), mk(630, 900, 'm/', { sig: 'ts|1b,f|1920x1080' }), mk(1530, 100, 'm/', { sig: 'ts|1b,f|1280x720' })] },
  { name: 'learnedHash', contentDir: 'm/', heuristic: [], learned: [{ hash: 'aa', duration: 15, at: 1 }], groups: [mk(0, 600, 'm/'), mk(600, 15, 'm/', { hash: 'aa' }), mk(615, 600, 'm/', { hash: 'bb' })] },
  { name: 'learnedDir', contentDir: 'm/', heuristic: [], learned: [{ hash: 'zz', sig: 's1', dir: 'ad/', duration: 20, at: 1 }], groups: [mk(0, 600, 'm/', { sig: 's0' }), mk(600, 21, 'ad/', { sig: 's1' }), mk(621, 600, 'm/', { sig: 's0' }), mk(1221, 25, 'ad/', { sig: 's1' })] },
  { name: 'repeat', contentDir: 'm/', heuristic: ['x.ts'], learned: [], groups: [mk(0, 600, 'm/'), mk(600, 15, 'o/'), mk(615, 600, 'm/'), mk(1215, 15, 'o/'), mk(1230, 300, 'm/'), mk(1530, 16, 'o/')] },
  { name: 'timeline', contentDir: 'm/', heuristic: [], learned: [], groups: [mk(0, 300, 'm/', { pts: 10 }), mk(300, 300, 'm/', { pts: 310 }), mk(600, 20, 'm/', { pts: 5 }), mk(620, 10, 'm/', { pts: 25 }), mk(630, 400, 'm/', { pts: 610 }), mk(1030, 400, 'm/', { pts: 1010 }), mk(1430, 20, 'm/', { pts: 9000 }), mk(1450, 20, 'm/')] },
  { name: 'timelineWrap', contentDir: 'm/', heuristic: [], learned: [], groups: [mk(0, 200, 'm/', { pts: 95400 }), mk(200, 30, 'm/', { pts: 3 }), mk(230, 500, 'm/', { pts: 95630 - 95443.717688888 })] },
  { name: 'unreliable', contentDir: 'm/', heuristic: [], learned: [], groups: [mk(0, 100, 'm/', { sig: 'a' }), mk(100, 100, 'm/', { sig: 'b' }), mk(200, 50, 'm/', { sig: 'c' })] },
];
out.decisions = cases.map((c) => {
  const total = c.groups.reduce((n, g) => n + g.duration, 0);
  const media: MediaAnalysis = { url: 'u', text: '', total, groups: c.groups, contentDir: c.contentDir, heuristicLines: c.heuristic };
  return { name: c.name, total, contentDir: c.contentDir, heuristic: c.heuristic, learned: c.learned, groups: c.groups.map((g) => ({ start: g.start, duration: g.duration, dir: g.dir, line: g.lines[0], sig: g.sig ?? null, hash: g.hash ?? null, pts: g.pts ?? null })),
    spans: timelineAdGroups(c.groups, total).map((s) => s.map((g) => c.groups.indexOf(g))), expected: decideAds([media], c.learned) };
});

const masters = ['#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nhi.m3u8', '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3500000\na.m3u8', '#EXTM3U\n#EXTINF:5,\na.ts', '#EXTM3U\n#EXT-X-STREAM-INF:PROGRAM-ID=1\n\n#comment\nx.m3u8', '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1080x1920\nv.m3u8'];
out.bestHeight = masters.map((text) => ({ text, expected: parseBestHeight(text) ?? null }));
out.mediaInfo = lists.map((l) => { const i = parseMediaPlaylist(l.text); return { text: l.text, expected: { initUri: i.initUri ?? null, firstSegment: i.firstSegment ?? null, keyMethod: i.keyMethod ?? null, keyUri: i.keyUri ?? null, keyIv: i.keyIv ? hex(i.keyIv) : null, sequence: i.mediaSequence } }; });
out.episodeOrder = [[], ['a'], ['a', 'b'], ['a', 'b', 'c'], ['a', 'b', 'c', 'd', 'e'], ['a', 'a']].map((e) => ({ input: e, expected: probeEpisodeOrder(e) }));

const subs = [
  '{"name":"我的列表","sources":[{"name":"甲","url":"https://a.com/api/"},{"url":"http://b.com/x"},{"url":"ftp://c"},{"name":" ","url":"https://a.com/api"},{"name":"成人","url":"https://d.com","isAdult":true,"detail":" https://d.com/d "}]}',
  '[{"name":"数组","url":"https://arr.com/api.php/provide/vod/"}]',
  '// 注释\n{"sites":[{"key":"a","name":"站A","type":1,"api":"https://sa.com/api.php/provide/vod/",},{"name":"蜘蛛","type":3,"api":"csp_XYZ"},{"name":"XML","type":0,"api":"https://x.com/inc/api.php"},{"name":"CMS0","type":0,"api":"https://c0.com/api.php/provide/vod"},{"name":"XMLAT","api":"https://c1.com/api.php/provide/vod/at/xml"},{"name":"不可搜","type":1,"api":"https://n.com/a","searchable":0},{"name":"本地","type":1,"api":"./x.json"},{"name":"T4","type":4,"api":"https://t4.com/vod"},{"name":"JS","type":1,"api":"https://j.com/a.js"},{"name":"重复","type":"1","api":"https://sa.com/api.php/provide/vod"},{"type":1,"api":"https://noname.com/v"}, /* 块注释 */ {"name":"无api"}],"lives":[{"name":"L","url":"http://l/x.m3u"}],}',
  '{"sites":[{"name":"蜘蛛","type":3,"api":"csp_XYZ"}]}',
  '{"sites":[]}',
  '{"foo":1}',
  'not json',
  '{"name":"多行\n名字","sources":[{"url":"https://m.com"}]}',
];
out.subscriptions = subs.map((text) => { const r = tryOr(() => parseSubscriptionPayload(parseSubscriptionJson(text))); return { text, error: 'error' in r ? r.error : null, name: r.value ? r.value.name ?? null : null, sources: r.value ? r.value.sources.map((s) => ({ name: s.name, url: s.url, detail: s.detail ?? null, isAdult: s.isAdult === true })) : null, live: r.value ? r.value.liveSources.length : 0 }; });
out.subKeys = ['https://a.com/list.json', 'http://b', '', '中文地址/订阅'].map((u) => ({ input: u, expected: subKeyPrefix(u) }));
const removals = [{ start: 10, duration: 5 }, { start: 100, duration: 20 }, { start: 100.5, duration: 1 }];
out.sourcePosition = [0, 9.99, 10, 10.00001, 50, 94.9, 95, 96, 500].map((p) => ({ position: p, removals, expected: sourcePosition(p, removals), duration: sourceDuration(p, removals) }));
out.desktopAddress = ['http://192.168.1.10:8090', ' http://192.168.1.10:8090/ ', 'https://pc.local', 'http://a:80', 'https://a:443', 'http://a/path', 'http://u:p@a', 'ftp://a', 'abc', 'http://a?x=1', 'http://A.B:8090'].map((v) => { const r = tryOr(() => desktopAddress(v)); return { input: v, value: 'value' in r ? r.value : null, error: 'error' in r ? r.error : null }; });
const item = (sourceKey: string, vodId: string, name: string, year?: string, extra: Record<string, unknown> = {}) => ({ sourceKey, sourceName: sourceKey, vodId, name, year, ...extra });
const list = [item('a', '1', '庆余年', '2019', { remarks: '全46集', pic: 'p1' }), item('b', '2', '庆余年', '2019', { remarks: '完结', typeName: '国产剧' }), item('c', '3', '庆余年 ', undefined, { remarks: '更新至46集' }), item('a', '4', '倚天屠龙记', '2003'), item('b', '5', '倚天屠龙记', '2019', { pic: 'p5' }), item('c', '6', '倚天屠龙记'), item('a', '7', ' '), item('a', '8', '独苗', '', { typeName: '' })];
out.groups = { list, expected: aggregateResults(list as never) };
const infos = [
  { sourceWidth: 1280, sourceHeight: 720, outputWidth: 1920, outputHeight: 1080, superResolution: true, model: 'fsrcnnx', adRemovalEnabled: true, adRemovalSupported: true, removedSegments: 3, removedSeconds: 15.04, overlayRemovalEnabled: true, overlays: [{ kind: 'logo', x: 1, y: 2, w: 3, h: 4 }, { kind: 'marquee', x: 0, y: 0, w: 9, h: 9 }], codec: 'hevc' },
  { sourceWidth: 1920, sourceHeight: 1080, outputWidth: 3840, outputHeight: 1600, superResolution: true, model: 'x', adRemovalEnabled: false, adRemovalSupported: true, removedSegments: 0, removedSeconds: 0 },
  { sourceWidth: 1920, sourceHeight: 1080, outputWidth: 1920, outputHeight: 1080, superResolution: false, superResolutionEnabled: false, model: null, adRemovalEnabled: true, adRemovalSupported: false, removedSegments: 0, removedSeconds: 0, overlayRemovalEnabled: false },
  { sourceWidth: 1920, sourceHeight: 1080, outputWidth: 3840, outputHeight: 2160, superResolution: false, model: null, adRemovalEnabled: true, adRemovalSupported: true, removedSegments: 0, removedSeconds: 0, overlayRemovalEnabled: true, overlays: [] },
  { sourceWidth: 0, sourceHeight: 1080, outputWidth: 1920, outputHeight: 1080, superResolution: false, model: null, adRemovalEnabled: true, adRemovalSupported: true, removedSegments: 0, removedSeconds: 0 },
  { sourceWidth: 1280, sourceHeight: 720, outputWidth: 1280, outputHeight: 720, superResolution: true, model: 'm', adRemovalEnabled: true, adRemovalSupported: true, removedSegments: 0, removedSeconds: 0 },
  { sourceWidth: 1280, sourceHeight: 720, outputWidth: 1920, outputHeight: 1080, superResolution: false, model: null, adRemovalEnabled: false, adRemovalSupported: true, removedSegments: 2, removedSeconds: 3 },
];
out.processing = infos.map((info) => { const parsed = parseProcessingInfo(info); return { info, valid: !!parsed, presentation: parsed ? processingPresentation(parsed) : null }; });
writeFileSync('android-tv/core/src/test/resources/core-fixtures.json', JSON.stringify(out));
console.log(Object.entries(out).map(([k, v]) => `${k}:${Array.isArray(v) ? v.length : 1}`).join(' '));
