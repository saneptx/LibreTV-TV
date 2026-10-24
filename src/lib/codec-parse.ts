import { createDecipheriv } from 'node:crypto';
export * from './codec-parse-core';

// ——— 解密 ———

/**
 * AES-128-CBC 解密分片前缀。
 * 只处理完整的 16 字节块：我们只读了分片开头一小段，末尾必然是不完整块，
 * 交给 update 而不调用 final，否则 Node 会按 PKCS7 去 padding 并报错。
 */
export function decryptAes128CbcPrefix(buf: Uint8Array, key: Uint8Array, iv: Uint8Array): Uint8Array {
  const usable = Math.floor(buf.length / 16) * 16;
  if (usable === 0 || key.length < 16 || iv.length < 16) return new Uint8Array(0);
  const decipher = createDecipheriv('aes-128-cbc', key.subarray(0, 16), iv.subarray(0, 16));
  decipher.setAutoPadding(false);
  const out = decipher.update(buf.subarray(0, usable));
  return new Uint8Array(out.buffer, out.byteOffset, out.byteLength);
}
