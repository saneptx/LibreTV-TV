import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

/**
 * 网页服务所在电脑上的串流服务（desktop-server，默认 8090）。
 * 浏览器不直接连接它，也不需要填写配对码：网页服务用本机保存的配对码代为转发，
 * 能访问网页（通过了网页的访问验证）的设备即视为同一个家庭。
 *
 * - DESKTOP_SERVER_URL：串流服务地址，默认 http://127.0.0.1:8090
 * - DESKTOP_PAIRING_TOKEN：配对码；未设置时读取当前目录下串流服务生成的 .desktop-server/config.json
 */
export interface DesktopHub { url: string; token: string }

const validToken = (token: unknown): token is string => typeof token === 'string' && token.length >= 24 && token.length <= 256 && !/[\r\n]/.test(token);

export async function desktopHub(): Promise<DesktopHub | null> {
  let url: string;
  try {
    const parsed = new URL(process.env.DESKTOP_SERVER_URL?.trim() || 'http://127.0.0.1:8090');
    if (!['http:', 'https:'].includes(parsed.protocol)) return null;
    url = parsed.origin;
  } catch { return null; }
  const fromEnv = process.env.DESKTOP_PAIRING_TOKEN?.trim();
  if (fromEnv) return validToken(fromEnv) ? { url, token: fromEnv } : null;
  try {
    const { token } = JSON.parse(await readFile(resolve('.desktop-server/config.json'), 'utf8')) as { token?: unknown };
    return validToken(token) ? { url, token } : null;
  } catch { return null; }
}
