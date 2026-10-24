import { afterEach, describe, expect, it, vi } from 'vitest';
import { createSourceCache } from './source-cache';
afterEach(() => vi.useRealTimers());
const memory = () => {
  const data = new Map<string, string>();
  return { getItem: (key: string) => data.get(key) ?? null, setItem: (key: string, value: string) => { data.set(key, value); }, data };
};
describe('持久化来源缓存', () => {
  it('跨实例/应用重启复用，区分新鲜与过期并最终淘汰，读取不共享可变对象', () => {
    const storage = memory(); let now = 1000;
    const cache = createSourceCache(() => storage, () => now);
    cache.set('a', { count: 1 }, 100, 500); cache.flush();
    const reopened = createSourceCache(() => storage, () => now);
    const hit = reopened.get<{ count: number }>('a')!;
    expect(hit).toMatchObject({ value: { count: 1 }, stale: false }); hit.value.count = 2;
    now += 101;
    expect(reopened.get('a')).toMatchObject({ value: { count: 1 }, stale: true });
    now += 400; expect(reopened.get('a')).toBeUndefined();
  });
  it('限制缓存体积与数量，存储损坏或配额异常不阻断操作', () => {
    vi.useFakeTimers(); const storage = memory(), cache = createSourceCache(() => storage);
    for (let n = 0; n < 300; n++) cache.set(String(n), 'x'.repeat(3000), 1000);
    cache.flush();
    const raw = [...storage.data.values()][0];
    expect(raw.length * 2).toBeLessThanOrEqual(1024 * 1024); expect(JSON.parse(raw).length).toBeLessThanOrEqual(250);
    expect(cache.get('0')).toBeUndefined(); expect(cache.get('299')).toBeDefined();
    const broken = createSourceCache(() => ({ getItem: () => '{', setItem: () => { throw new Error('Quota'); } }));
    expect(() => { broken.set('ok', [1], 1000); broken.flush(); }).not.toThrow();
    expect(broken.get('ok')?.value).toEqual([1]);
  });
});
