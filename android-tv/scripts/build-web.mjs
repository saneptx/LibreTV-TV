import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const stage = join(root, 'android-tv/.web-build');
const target = join(root, 'android-tv/app/build/generated/web-assets/web');
if (!existsSync(join(root, 'node_modules/next/dist/bin/next'))) {
  throw new Error('请先在项目根目录执行 npm ci，安装界面构建依赖');
}
rmSync(stage, { recursive: true, force: true });
mkdirSync(join(stage, 'src/app'), { recursive: true });
for (const dir of ['components', 'lib']) cpSync(join(root, 'src', dir), join(stage, 'src', dir), { recursive: true });
// 电视用 /tv、/watch、/native-tv；手机用首页、片库和同一个播放页
for (const name of ['tv', 'watch', 'native-tv', 'page.tsx', 'library', 'error.tsx', 'global-error.tsx', 'layout.tsx', 'globals.css']) {
  cpSync(join(root, 'src/app', name), join(stage, 'src/app', name), { recursive: true });
}
for (const name of ['public', 'package.json', 'tsconfig.json', 'next-env.d.ts', 'postcss.config.mjs', 'tailwind.config.ts', 'source.json']) {
  if (existsSync(join(root, name))) cpSync(join(root, name), join(stage, name), { recursive: true });
}
symlinkSync(join(root, 'node_modules'), join(stage, 'node_modules'), 'dir');
// 只导出电视页面和原生界面的本地数据入口；不把 Node API 或服务端密码打进 APK。
const config = readFileSync(join(root, 'next.config.ts'), 'utf8')
  .replace(/output: process\.env\.DOCKER_BUILD === '1' \? 'standalone' : undefined,/, "output: 'export',");
writeFileSync(join(stage, 'next.config.ts'), config);
const result = spawnSync(process.execPath, [join(root, 'node_modules/next/dist/bin/next'), 'build'], {
  cwd: stage, stdio: 'inherit', env: { ...process.env, NEXT_DIST_DIR: '.next', NEXT_TELEMETRY_DISABLED: '1' },
});
if (result.status !== 0) process.exit(result.status ?? 1);
rmSync(target, { recursive: true, force: true });
mkdirSync(target, { recursive: true });
cpSync(join(stage, 'out'), target, { recursive: true });
console.log(`电视离线界面已输出：${target}`);
