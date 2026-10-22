# LibreTV Android TV 壳

这是 `/tv` 页面对应的轻量 Android TV WebView 客户端。LibreTV 服务端仍运行在电脑、NAS 或云服务器上。首次打开 APK 会用遥控器输入服务地址；之后按遥控器的菜单或设置键可以随时修改。

## 配置地址

默认不需要在构建时设置地址。若要为测试包预填地址（首次配置页仍可修改），可在项目的 `gradle.properties` 或用户级 Gradle 属性中加入：

```properties
LIBRETV_URL=http://192.168.1.10:8080/tv
```

将 IP 换成运行 LibreTV 的设备地址。电视和服务端需要能互相访问；Android 模拟器可继续使用默认的 `10.0.2.2`。

## 构建

安装 JDK 17 和 Android SDK 35 后，在 Android Studio 中选择 `app` 并运行，或从 Gradle 工具窗口执行 `app > Tasks > build > assembleDebug`。也可直接使用仓库内的 Gradle Wrapper：

```bash
./gradlew assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 当前边界

- 界面、搜索和详情使用 `/tv`；播放仍复用 LibreTV `/watch` 页面，并启用遥控器快捷键。
- 登录 Cookie 和观看历史保存在电视 WebView 本地。
- 同时注册 Android TV 和普通 Android 启动入口，可安装到电视盒子及常见安卓投影仪。
- 局域网 HTTP 已允许；公网部署请使用 HTTPS。
- 下一阶段可以把播放部分替换为 Media3/ExoPlayer，改善低配投影仪上的解码兼容性。

## 电视播放页

从 `/tv` 选片进入播放后，画面默认覆盖整个视口并保留原始比例，控制栏闲置 5 秒自动收起。片源与屏幕比例不同时会留黑边，避免裁掉字幕和画面。

| 遥控器按键 | 播放页行为 |
| --- | --- |
| 确认 | 播放 / 暂停；面板中执行当前选项 |
| 左 / 右 | 画面上预览快退 / 快进，每次 10 秒，确认后跳转；控制栏中移动焦点 |
| 下 | 呼出控制栏，可选上一集、下一集、选集、换源、设置 |
| 上 | 在画面上打开选集；控制栏中上移到进度条 |
| 菜单 / 设置（电脑 F2） | 打开倍速、完整显示 / 裁切铺满、自动连播、广告过滤设置 |
| 返回 | 先关闭面板或取消进度预览，再收起控制栏，最后退出播放 |

这套按键需要新版 APK；换源和切集均保留电视播放模式。首页菜单键仍用于修改服务器地址。

# 本地电视模拟器

已配置的本机调试环境使用 Android 11（API 30）与 1920×1080 电视配置。先在项目根目录启动 LibreTV 服务：

```bash
./start_local.sh
```

另开一个终端，运行：

```bash
./android-tv/run_local_tv_emulator.sh
```

脚本会启动或复用 `LibreTV-TV-API30` 模拟器，把模拟器的 `8080` 转发到本机服务，构建调试 APK 并安装启动。模拟器的 SDK 和 AVD 都保存在 `android-tv/.android-sdk/` 与 `android-tv/.android-avd/`，不会提交到 Git。

`start_local.sh` 默认关闭服务端密码验证，新安装的 App 和模拟器无需登录 Cookie 即可搜索播放。若用其他方式启动服务，可显式设置 `AUTH_DISABLED=true`。

新启动的模拟器开启硬件键盘和按键直通，设置单独保存在 `android-tv/.emulator-config/`。单击模拟器画面后，电脑方向键可用于遥控器导航，Enter 用于确认。首次应用这些设置需要关闭旧模拟器并重新运行脚本。脚本启动新模拟器时需保持终端打开，关闭模拟器窗口后脚本结束。
