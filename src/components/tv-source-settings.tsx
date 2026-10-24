'use client';

import { useEffect, useRef, useState } from 'react';
import { useAppStore } from '@/lib/store';
import { syncSourceSubscription } from '@/lib/subscription-sync';
import { testDesktopConnection } from '@/lib/desktop-playback';
import styles from './tv-source-settings.module.css';

export function TvSourceSettings({ onClose }: { onClose: () => void }) {
  const store = useAppStore();
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [subscription, setSubscription] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const close = useRef<HTMLButtonElement>(null);
  const available = [...store.envSources, ...store.customAPIs].filter((s) => !s.isAdult || !store.yellowFilter);
  useEffect(() => {
    close.current?.focus();
    const key = (event: KeyboardEvent) => { if (event.key === 'Escape') { event.preventDefault(); onClose(); } };
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  }, [onClose]);

  return <div className={styles.backdrop}>
    <section className={styles.panel} role="dialog" aria-modal="true" aria-label="片源管理" data-tv-dialog>
      <header><div><h2>片源管理</h2><p>{store.playbackMode === 'desktop' ? '电脑负责画面处理和串流' : '独立运行 · 设备直接访问片源，无需电脑服务器'}</p></div><button ref={close} data-tv-focus onClick={onClose}>完成</button></header>
      <div className={styles.body}>
        <h3>播放模式</h3>
        <form onSubmit={(event) => event.preventDefault()}>
          <button data-tv-focus aria-pressed={store.playbackMode !== 'desktop'} onClick={() => store.updateSettings({ playbackMode: 'direct' })}>独立直连</button>
          <button data-tv-focus aria-pressed={store.playbackMode === 'desktop'} onClick={() => store.updateSettings({ playbackMode: 'desktop' })}>电脑串流</button>
        </form>
        <form onSubmit={event => event.preventDefault()}>
          <span>AI 超分（电脑串流）</span>
          <button data-tv-focus aria-pressed={store.superResolution} onClick={() => store.updateSettings({ superResolution: !store.superResolution })}>{store.superResolution ? '已开启 · 自动升高一个档位' : '已关闭 · 保持原始分辨率'}</button>
        </form>
        <p>串流模式需要电脑服务持续运行；处理能力以连接检测结果为准。配对码用于连接电脑服务，独立模式无需密码。</p>
        <form onSubmit={async (event) => {
          event.preventDefault(); if (busy) return;
          setBusy(true); setMessage('正在连接电脑服务…');
          try { setMessage(await testDesktopConnection(store)); }
          catch (error) { setMessage(error instanceof Error ? error.message : '连接失败'); }
          finally { setBusy(false); }
        }}>
          <input data-tv-focus aria-label="电脑服务地址" placeholder="http://192.168.1.10:8090" value={store.desktopServerUrl} onChange={(event) => store.updateSettings({ desktopServerUrl: event.target.value })} />
          <input data-tv-focus aria-label="电脑服务配对码" type="password" placeholder="电脑服务配对码" value={store.desktopPairingToken} onChange={(event) => store.updateSettings({ desktopPairingToken: event.target.value })} />
          <button data-tv-focus disabled={busy} type="submit">测试连接</button>
        </form>
        <p role="status">{message}</p>
        <p>已启用 {available.filter((s) => store.selectedKeys.includes(s.key)).length} 个片源。按确认键切换，设置自动保存。</p>
        <div className={styles.sources}>{available.map((source) => <button data-tv-focus key={source.key} aria-pressed={store.selectedKeys.includes(source.key)} onClick={() => store.toggleSourceSelected(source.key)}>
          <span>{store.selectedKeys.includes(source.key) ? '✓ ' : '○ '}{source.name}</span>
        </button>)}</div>
        <h3>添加 Apple CMS 片源</h3>
        <form onSubmit={(event) => {
          event.preventDefault();
          try {
            const parsed = new URL(url.trim());
            if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password) throw new Error();
            store.addCustomApi({ name: name.trim() || parsed.hostname, url: parsed.href.replace(/\/+$/, '') });
            setName(''); setUrl(''); setMessage('片源已添加并启用');
          } catch { setMessage('请输入有效的 HTTP/HTTPS 片源地址'); }
        }}>
          <input data-tv-focus aria-label="片源名称" placeholder="名称（可选）" value={name} onChange={(event) => setName(event.target.value)} />
          <input data-tv-focus aria-label="片源接口地址" placeholder="https://example.com/api.php/provide/vod" value={url} onChange={(event) => setUrl(event.target.value)} />
          <button data-tv-focus type="submit">添加</button>
        </form>
        <h3>导入或更新订阅</h3>
        <p>支持 LibreTV SourceList 和 TVBOX 的直连接口；Spider 类订阅暂不支持。</p>
        <form onSubmit={async (event) => {
          event.preventDefault(); if (busy) return;
          setBusy(true); setMessage('正在从设备同步订阅…');
          try { await syncSourceSubscription(subscription.trim()); setMessage('订阅已同步'); setSubscription(''); }
          catch (error) { setMessage(error instanceof Error ? error.message : '订阅同步失败'); }
          finally { setBusy(false); }
        }}>
          <input data-tv-focus aria-label="订阅地址" placeholder="https://example.com/sources.json" value={subscription} onChange={(event) => setSubscription(event.target.value)} />
          <button data-tv-focus disabled={busy || !subscription.trim()} type="submit">同步订阅</button>
        </form>
        {store.subscriptions.map((s) => <button data-tv-focus key={s.url} className={styles.subscription} disabled={busy} onClick={async () => {
          setBusy(true); setMessage('正在更新订阅…');
          try { await syncSourceSubscription(s.url); setMessage('订阅已更新'); }
          catch (error) { setMessage(error instanceof Error ? error.message : '订阅更新失败'); }
          finally { setBusy(false); }
        }}>更新 {s.name || s.url}</button>)}
      </div>
    </section>
  </div>;
}
