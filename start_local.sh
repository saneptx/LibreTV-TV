
#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# 读取 source.json 并验证
export DEFAULT_SOURCES="$(
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

# 建议在启动前由外部设置 PASSWORD
: "${PASSWORD:?请先设置 PASSWORD 环境变量}"

echo "已加载 $(node -p 'require("./source.json").length') 个点播源"

# 构建并启动
npm run build
npm start
