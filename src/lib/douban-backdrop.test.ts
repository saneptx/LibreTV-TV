import { describe, expect, it } from 'vitest';
import { pickBackdrop } from './douban-backdrop';

const photo = (url: string, width: number, height: number) => ({ image: { large: { url, width, height } } });

describe('pickBackdrop', () => {
  it('跳过竖图和小图，取第一张够大的横图', () => {
    expect(pickBackdrop([
      photo('https://img1.doubanio.com/view/photo/l/public/p1.jpg', 1057, 1600),
      photo('https://img1.doubanio.com/view/photo/l/public/p2.jpg', 800, 533),
      photo('https://img3.doubanio.com/view/photo/l/public/p3.jpg', 1600, 1066),
      photo('https://img3.doubanio.com/view/photo/l/public/p4.jpg', 1600, 900),
    ])).toBe('https://img3.doubanio.com/view/photo/l/public/p3.jpg');
  });
  it('只接受豆瓣图床的 https 地址', () => {
    expect(pickBackdrop([photo('https://example.com/a.jpg', 1600, 900), photo('http://img3.doubanio.com/a.jpg', 1600, 900)])).toBeNull();
  });
  it('数据不是数组或缺字段时返回 null', () => {
    expect(pickBackdrop(undefined)).toBeNull();
    expect(pickBackdrop([{}, { image: {} }, photo('https://img3.doubanio.com/a.jpg', 3000, 1000)])).toBeNull();
  });
});
