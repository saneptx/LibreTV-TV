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

"$ADB" -s "$SERIAL" reverse tcp:8080 tcp:8080
(cd "$ANDROID_TV_ROOT" && ./gradlew assembleDebug)
"$ADB" -s "$SERIAL" install -r "$APK"
"$ADB" -s "$SERIAL" shell am start -n org.libretv.tv/.MainActivity

echo "LibreTV-TV 已在 $SERIAL 启动；应用服务请保持运行在宿主机 8080 端口。"
if [[ -n "$EMULATOR_PID" ]]; then
  echo "保持此终端打开，关闭模拟器窗口后脚本结束。"
  wait "$EMULATOR_PID"
fi
