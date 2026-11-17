'use client';

import { useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { Header } from '@/components/header';
import { SiteFooter } from '@/components/site-footer';
import { EmptyState, LoadingState } from '@/components/states';
import { PosterCard } from '@/components/home-showcase';
import { useToast } from '@/components/toast';
import { listFavorites, toggleFavorite, type FavoriteEntry } from '@/lib/db';

const text = (value: unknown) => (typeof value === 'string' || typeof value === 'number' ? String(value) : '');

/** 片库：收藏的影片，与电视、手机同步。点海报按片名查找片源 */
export default function LibraryPage() {
  const router = useRouter();
  const { toast } = useToast();
  const favorites = useLiveQuery(() => listFavorites(), []);

  const remove = async (entry: FavoriteEntry) => {
    const name = text(entry.card.name);
    await toggleFavorite(entry.card);
    toast(`已把「${name}」移出片库`, 'info', { action: { label: '撤销', onClick: () => void toggleFavorite(entry.card) } });
  };

  return (
    <div className="min-h-screen flex flex-col">
      <Header />
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-8 py-8">
        <div className="flex items-baseline gap-3 mb-6">
          <h1 className="text-2xl font-bold tracking-tight text-content">片库</h1>
          {!!favorites?.length && <p className="text-xs text-faint">{favorites.length} 部 · 与电视同步</p>}
        </div>
        {!favorites ? <LoadingState />
          : favorites.length === 0 ? (
            <EmptyState icon="plus" title="片库里还没有影片" description="在电视的影片详情页选择「加入片库」，收藏的影片会同步到这里。" />
          ) : (
            <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-7 gap-x-4 gap-y-7">
              {favorites.map((entry) => {
                const name = text(entry.card.name), year = text(entry.card.year);
                return (
                  <div key={entry.key} className="group/item relative">
                    <PosterCard
                      className="!w-full"
                      title={name}
                      cover={text(entry.card.pic) || text(entry.card.cover) || undefined}
                      meta={[year, text(entry.card.typeName) || text(entry.card.type)].filter(Boolean).slice(0, 1)}
                      onClick={() => router.push(`/?s=${encodeURIComponent(name)}`)}
                    />
                    <button type="button" aria-label={`把「${name}」移出片库`} title="移出片库" onClick={() => void remove(entry)}
                      className="absolute top-2 right-2 w-7 h-7 flex items-center justify-center rounded-full bg-black/65 text-white backdrop-blur-sm opacity-0 group-hover/item:opacity-100 focus-visible:opacity-100 transition-opacity hover:bg-black/85">
                      <svg viewBox="0 0 24 24" className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round"><path d="M6 6l12 12M18 6L6 18" /></svg>
                    </button>
                  </div>
                );
              })}
            </div>
          )}
      </main>
      <SiteFooter />
    </div>
  );
}
