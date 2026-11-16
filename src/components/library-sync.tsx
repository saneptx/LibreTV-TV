'use client';

import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, STATUS_QUERY_KEY } from '@/lib/client-api';
import { startLibrarySync, type SyncReply } from '@/lib/library-sync';

const ENDPOINT = '/api/sync';

/**
 * 浏览器端的资料同步：观看历史、进度、搜索记录、片库收藏经网页服务转发到电脑串流服务，
 * 与电视、手机互通。全站常驻、没有界面；电脑没有配置串流服务或尚未登录时不启动。
 * 同步失败（串流服务没开等）只记录在状态里，下一轮自动重试，不打断使用。
 */
export function LibrarySync() {
  const { data } = useQuery({ queryKey: STATUS_QUERY_KEY, queryFn: () => api.status() });
  const enabled = !!data?.librarySync && !!data.verified;
  useEffect(() => {
    if (!enabled) return;
    const sync = startLibrarySync(() => ENDPOINT, async (body, signal) => {
      // 不走 client-api 的 request：后台同步失败不应弹出登录框
      const response = await fetch(ENDPOINT, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal });
      const reply: unknown = await response.json().catch(() => null);
      if (!response.ok) throw new Error((reply as { error?: string } | null)?.error || `同步失败 (${response.status})`);
      return reply as SyncReply;
    });
    return () => sync.stop();
  }, [enabled]);
  return null;
}
