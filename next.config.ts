import type { NextConfig } from 'next';
import fs from 'node:fs';
import path from 'node:path';

/** 版本号以 package.json 为单一来源，构建时注入 process.env.APP_VERSION（/api/status 使用） */
function readAppVersion(): string {
  try {
    const pkg = JSON.parse(fs.readFileSync(path.join(process.cwd(), 'package.json'), 'utf8')) as {
      version?: string;
    };
    return pkg.version || '0.0.0';
  } catch {
    return '0.0.0';
  }
}

const nextConfig: NextConfig = {
  // 模拟器可使用独立构建目录，验证时不覆盖正在服务投影仪的产物。
  distDir: process.env.NEXT_DIST_DIR || '.next',
  // standalone 输出仅供 Docker 镜像构建使用（DOCKER_BUILD=1）；
  // 本地 next start 在 standalone 模式下不受支持，故按环境切换
  output: process.env.DOCKER_BUILD === '1' ? 'standalone' : undefined,
  reactStrictMode: true,
  // JMGO NS1 的 Android System WebView 为 Chrome 83。TanStack Query 的 modern
  // 入口包含该内核无法解析的私有字段；浏览器端改用其官方 legacy 入口。
  webpack: (config, { isServer }) => {
    if (!isServer) {
      config.resolve.alias['@tanstack/query-core$'] = path.join(
        process.cwd(),
        'node_modules/@tanstack/query-core/build/legacy/index.js',
      );
      config.resolve.alias['@tanstack/react-query$'] = path.join(
        process.cwd(),
        'node_modules/@tanstack/react-query/build/legacy/index.js',
      );
    }
    return config;
  },
  env: { APP_VERSION: readAppVersion() },
  // 采集站/豆瓣等上游地址在运行时由用户配置，构建期无法枚举，关闭图片优化改用 <img>
  images: { unoptimized: true },
};

export default nextConfig;
