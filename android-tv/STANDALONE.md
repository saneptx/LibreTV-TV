# Android 0.3.0 独立运行

此版本把电视界面、片源配置和采集接口解析打进 APK。投影仪只需连接互联网，电脑关机后仍可搜索、选集、换源及播放；不再要求运行 `start_local.sh`、填写电脑地址或登录密码。普通浏览器仍使用原来的 Next.js 服务。

## 数据流

- 页面、脚本、样式：APK assets；`MainActivity` 的 WebView 拦截器读取，不启动 HTTP 服务。
- 搜索 / 详情 / 订阅 / 封面：设备通过原生 HTTP 直接访问外网。Apple CMS 与订阅解析沿用项目的 TypeScript 规则。
- 画质：设备读取清单和最多 96 KiB 的媒体前缀，共用 TS/MP4 解析；AES-128 前缀用 Android 解密。解码后仍以 Media3 的真实尺寸为准。
- 视频：设备上的 Media3 直接连接 CDN、解码并显示，保留电视遥控器操作、缓冲网速、广告过滤和进度恢复。
- 配置和记录：继续保存在设备 WebView 的 localStorage / IndexedDB。

搜索最多并发 6 个来源，每源最多 5 页、10 秒预算；逐源返回结果，单个失效源不阻塞已到达的结果。画质最多并发 3 条。关闭页面/取消搜索时通知原生层中断请求。HTTP 桥有并发、队列、响应大小和超时限制，不向外部页面或子框架开放。

## 升级和本地地址

直接覆盖安装 `org.libretv.tv`，不要卸载或清除应用数据。升级时沿用旧版保存的网页 origin，以保留搜索历史、收藏、观看进度与片源勾选。新安装默认采用 `http://127.0.0.1:8080` 这个虚拟 origin。

调试器里仍可能看到旧电脑地址或 `127.0.0.1:8080`。这是 WebView 的存储身份：所有该 origin 的资源都从 APK 返回，缺失资源直接返回本地 404，绝不访问原电脑；没有监听或转发 8080 端口。主页面只允许本地电视页面导航。原生 HTTP 桥也拒绝请求该 origin。

首页的「片源管理」按钮、遥控器菜单/设置键可启停来源、添加 Apple CMS 接口、导入或更新 LibreTV SourceList / TVBOX 直连订阅。Spider、jar、JS/Python 爬虫仍不支持。

## 构建

项目根目录先执行 `npm ci`。需要 Node.js、JDK 17、Android SDK 35。

```bash
cd android-tv
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Gradle 自动执行 `scripts/build-web.mjs`：在 `.web-build` 中只导出 `/tv` 和 `/watch` 的静态页面，然后把页面和 `_next` 资源打包进 APK。不会覆盖开发机的 `.next` 构建，不打包服务端 API、环境密码或 Node 运行时。Gradle 的资源忽略规则显式保留 `_next` 目录。

产物：`app/build/outputs/apk/debug/app-debug.apk`。本地模拟器可直接运行 `./android-tv/run_local_tv_emulator.sh`，无需先启动电脑服务；脚本会清理旧版 8080 转发。

## 边界

本阶段覆盖电视点播流程，保留 React 电视界面；还不是全原生 UI。直播、下载、外挂字幕和语音输入没有在此阶段迁入独立电视界面。设备对外网的速度、DNS、TLS 和片源可用性仍会影响体验；关闭电脑代理后不能再借用电脑的网络线路加速。4K/HDR 能力取决于设备解码器，未承诺所有编码均支持。

桥使用 AndroidX 的指定 origin 和主框架检查，消息回复遵循 [WebViewCompat 官方文档](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addWebMessageListener(android.webkit.WebView,java.lang.String,java.util.Set,androidx.webkit.WebViewCompat.WebMessageListener))。页面或桥协议更改后应重新覆盖安装；仅重启网页服务不会更新独立 APK。

## 本次验证记录

- 497 项 Vitest 测试、TypeScript、ESLint，以及完整网页生产构建通过；Android 构建、6 项单元测试和 lint 通过（保留项目原有平台/版本提示）。
- 模拟器移除全部 ADB reverse 后验证了搜索、封面、720P / 1080P 原生输出、暂停、切集、换源、画质探测、片源开关和遥控器菜单。断开 Wi-Fi 和移动网络后冷启动仍可显示本地首页、默认片源和历史，恢复网络后继续使用。
- JMGO NS1 覆盖升级 0.3.0，移除 reverse；升级时 15 条观看记录、12 条进度、3 条搜索历史与升级前快照逐项一致。实机直连搜索、1280×720 原生视频画面和遥控器暂停通过，测试时接收速率约 250–300 KB/s，未使用电脑代理。
- 电脑上的网页服务构建和 Android 静态导出互不覆盖。APK 约 5 MB，不包含 Node 运行时。
