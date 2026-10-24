'use client';
import { useEffect } from 'react';
import { installNativeTvBackend } from '@/lib/native-tv-backend';

/** 仅供 APK 原生视图使用的数据入口，不渲染产品界面。 */
export default function NativeTvBackendPage() {
  useEffect(() => installNativeTvBackend(), []);
  return null;
}
