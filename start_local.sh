#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# 家庭局域网默认免登录；AUTH_DISABLED=false 可恢复密码验证。
export AUTH_DISABLED="${AUTH_DISABLED:-true}"
if [[ "$AUTH_DISABLED" != "true" ]]; then
  : "${PASSWORD:?已开启访问验证，请先设置 PASSWORD 环境变量}"
fi

case "${1:-}" in
  --background|-b)
    if [[ -f .start_local.pid ]]; then
      read -r running_pid < .start_local.pid || true
      if [[ "${running_pid:-}" =~ ^[0-9]+$ ]] && kill -0 "$running_pid" 2>/dev/null; then
        echo "已有后台进程运行（PID: $running_pid）"
        exit 1
      fi
    fi
    nohup bash "$PWD/start_local.sh" > "$PWD/start_local.log" 2>&1 < /dev/null &
    background_pid=$!
    echo "$background_pid" > .start_local.pid
    echo "已提交后台启动（PID: $background_pid），安装和构建也将在后台进行。"
    echo "查看日志：tail -f \"$PWD/start_local.log\""
    echo "停止进程：kill $background_pid"
    exit 0
    ;;
  "") ;;
  *)
    echo "用法：$0 [--background|-b]" >&2
    exit 1
    ;;
esac

# 首次启动或依赖安装不完整时，按锁文件安装构建所需的依赖。
if [[ ! -x node_modules/.bin/next || ! -x node_modules/.bin/tsc ]]; then
  echo "正在安装项目依赖……"
  npm ci --include=dev
fi

# 读取 source.json 并验证
DEFAULT_SOURCES="$(
  node -e '
    const fs = require("fs");
    const sources = JSON.parse(
      fs.readFileSync("source.json", "utf8")
    );
    if (!Array.isArray(sources)) {
      throw new Error("source.json 必须是数组");
    }
    if (sources.length > 100) {
      throw new Error("最多支持 100 个点播源");
    }
    for (const s of sources) {
      if (!s.name || !/^https?:\/\//.test(s.url || "")) {
        throw new Error("点播源名称或 URL 无效");
      }
    }
    process.stdout.write(JSON.stringify(sources));
  '
)"

export DEFAULT_SOURCES

echo "已加载 $(node -p 'require("./source.json").length') 个点播源"
if [[ "$AUTH_DISABLED" == "true" ]]; then
  echo "本地服务已关闭密码验证，所有可访问此服务的客户端均可免登录。"
fi

# 构建并启动
npm run build
# 直接替换脚本进程，让后台 PID 对应最终服务进程。
exec node node_modules/next/dist/bin/next start -p 8080
