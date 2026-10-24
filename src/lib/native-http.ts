/** Android 原生网络桥；仅 APK 中存在，普通网页仍走原来的服务端 API。 */
interface NativeHttpBridge {
  postMessage(message: string): void;
  onmessage?: (event: { data: string }) => void;
}

export function getNativeHttpBridge(): NativeHttpBridge | undefined {
  return typeof window === 'undefined' ? undefined : (window as Window & { LibreTvNativeHttp?: NativeHttpBridge }).LibreTvNativeHttp;
}

let sequence = 0;
let attached: NativeHttpBridge | undefined;
const pending = new Map<string, { resolve: (value: NativeReply) => void; reject: (error: Error) => void }>();
interface NativeReply { status?: number; url?: string; headers?: Record<string, string>; body: string; error?: string }

export function nativeRpc(type: string, payload: Record<string, unknown>, signal?: AbortSignal): Promise<NativeReply> {
  const bridge = getNativeHttpBridge();
  if (!bridge) return Promise.reject(new Error('设备原生网络不可用'));
  if (signal?.aborted) return Promise.reject(new DOMException('已取消', 'AbortError'));
  if (attached !== bridge) {
    attached = bridge;
    bridge.onmessage = ({ data }) => {
      let reply: NativeReply & { id: string };
      try { reply = JSON.parse(data); } catch { return; }
      const entry = pending.get(reply.id);
      if (!entry) return;
      if (reply.error) entry.reject(new Error(reply.error)); else entry.resolve(reply);
    };
  }
  const id = `http-${Date.now()}-${++sequence}`;
  return new Promise((resolve, reject) => {
    const finish = () => { pending.delete(id); clearTimeout(timer); signal?.removeEventListener('abort', abort); };
    const abort = () => {
      bridge.postMessage(JSON.stringify({ id, type: 'cancel' }));
      finish(); reject(new DOMException('已取消', 'AbortError'));
    };
    const timer = setTimeout(() => {
      bridge.postMessage(JSON.stringify({ id, type: 'cancel' }));
      finish(); reject(new Error('网络请求超时'));
    }, Number(payload.timeoutMs ?? 10000) + 1000);
    pending.set(id, {
      resolve: (value) => { finish(); resolve(value); },
      reject: (error) => { finish(); reject(error); },
    });
    signal?.addEventListener('abort', abort, { once: true });
    try { bridge.postMessage(JSON.stringify({ id, type, ...payload })); } catch (error) { finish(); reject(error); }
  });
}

export function decodeNativeBytes(base64: string): Uint8Array<ArrayBuffer> {
  const text = atob(base64);
  return Uint8Array.from(text, (c) => c.charCodeAt(0));
}
export function encodeNativeBytes(bytes: Uint8Array): string {
  let text = '';
  for (const byte of bytes) text += String.fromCharCode(byte);
  return btoa(text);
}

export async function nativeFetch(url: string, options: { method?: 'GET' | 'POST' | 'DELETE'; body?: string; headers?: Record<string, string>; signal?: AbortSignal; timeoutMs?: number; maxBytes?: number; prefix?: boolean } = {}): Promise<Response> {
  const { signal, ...request } = options;
  const reply = await nativeRpc('fetch', { url, ...request }, signal);
  const res = new Response(decodeNativeBytes(reply.body), { status: reply.status, headers: reply.headers });
  Object.defineProperty(res, 'url', { value: reply.url || url });
  return res;
}
