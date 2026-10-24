#!/usr/bin/env bash
# 在本机 Android 11 / 1080p 虚拟电视中构建、安装并启动 LibreTV-TV。
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_TV_ROOT="$PROJECT_ROOT/android-tv"
SDK_ROOT="$ANDROID_TV_ROOT/.android-sdk"
AVD_HOME="$ANDROID_TV_ROOT/.android-avd"
AVD_NAME="LibreTV-TV-API30"
EMULATOR="$SDK_ROOT/emulator/emulator"
ADB="$SDK_ROOT/platform-tools/adb"
APK="$ANDROID_TV_ROOT/app/build/outputs/apk/debug/app-debug.apk"
EMULATOR_CONFIG_HOME="$ANDROID_TV_ROOT/.emulator-config"
SERIAL="emulator-5554"
EMULATOR_PID=""
START_ARGS=()
case "${1:-}" in
  "") ;;
  --native-preview) ;; # 兼容旧命令；原生复刻现已是默认入口。
  --legacy-web) START_ARGS=(--ez legacy_web_ui true) ;;
  *) echo "用法：$0 [--legacy-web]（旧参数 --native-preview 仍兼容）" >&2; exit 2 ;;
esac

if [[ ! -x "$EMULATOR" || ! -x "$ADB" || ! -d "$AVD_HOME/$AVD_NAME.avd" ]]; then
  echo "本地 TV 模拟器尚未配置完成。请先完成 Android SDK、系统镜像和 AVD 安装。" >&2
  exit 1
fi

# 为这个项目单独保存模拟器设置，让实体键盘直接发送给 Android。
mkdir -p "$EMULATOR_CONFIG_HOME/Android Open Source Project"
cat > "$EMULATOR_CONFIG_HOME/Android Open Source Project/Emulator.conf" <<'EOF'
[set]
forwardShortcutsToDevice=true
EOF
sed -i 's/^hw.keyboard=.*/hw.keyboard=yes/; s/^hw.keyboard.lid=.*/hw.keyboard.lid=no/' \
  "$AVD_HOME/$AVD_NAME.avd/config.ini"

"$ADB" start-server >/dev/null
if [[ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)" != "device" ]]; then
  ANDROID_SDK_ROOT="$SDK_ROOT" ANDROID_AVD_HOME="$AVD_HOME" XDG_CONFIG_HOME="$EMULATOR_CONFIG_HOME" \
    "$EMULATOR" -avd "$AVD_NAME" -port 5554 -no-audio -gpu swiftshader_indirect \
      -use-keycode-forwarding -no-snapshot-load \
      >"$ANDROID_TV_ROOT/.emulator.log" 2>&1 &
  EMULATOR_PID=$!
fi

BOOT_READY=false
for ((attempt = 0; attempt < 120; attempt++)); do
  if [[ -n "$EMULATOR_PID" ]] && ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    echo "模拟器启动失败，请查看 $ANDROID_TV_ROOT/.emulator.log" >&2
    exit 1
  fi
  if [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    BOOT_READY=true
    break
  fi
  sleep 2
done
if [[ "$BOOT_READY" != true ]]; then
  echo "等待模拟器启动超时，请查看 $ANDROID_TV_ROOT/.emulator.log" >&2
  exit 1
fi
RUNNING_AVD="$("$ADB" -s "$SERIAL" emu avd name | tr -d '\r' | head -n 1)"
if [[ "$RUNNING_AVD" != "$AVD_NAME" ]]; then
  echo "$SERIAL 被其他虚拟设备 $RUNNING_AVD 占用，请先关闭该设备。" >&2
  exit 1
fi

# 独立 APK 不需要开发机端口转发；清理旧版留下的转发。
"$ADB" -s "$SERIAL" reverse --remove tcp:8080 2>/dev/null || true
(cd "$ANDROID_TV_ROOT" && ./gradlew assembleDebug)
"$ADB" -s "$SERIAL" install -r "$APK"
"$ADB" -s "$SERIAL" shell am start -S -n org.libretv.tv/.MainActivity "${START_ARGS[@]}"

if [[ ${#START_ARGS[@]} -gt 0 ]]; then
  echo "LibreTV-TV 旧网页界面对照已在 $SERIAL 启动；仅供调试。"
else
  echo "LibreTV-TV 原生复刻界面已在 $SERIAL 启动，与投影仪桌面入口一致；独立模式无需启动本机服务。"
fi
if [[ -n "$EMULATOR_PID" ]]; then
  echo "保持此终端打开，关闭模拟器窗口后脚本结束。"
  wait "$EMULATOR_PID"
fi
