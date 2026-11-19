# SuperTV 电脑端

Windows / macOS 桌面程序，界面和手机端、电视端同一套设计（黑白灰、同一个标志），全部是原生控件（Compose Desktop），不是网页。

- **看片**：首页（继续观看、热门剧集 / 电影）、搜索、详情、片库、播放。数据层与手机、电视共用 `android-tv/core`（Kotlin），搜索、去广告、观看记录、同步的行为三端一致。
- **窗口**：Windows 上不用系统标题栏，顶栏兼作标题栏（空白处拖动、双击最大化），最小化 / 最大化 / 关闭按钮和可拖动缩放的边框都是自己画的（`Chrome.kt`）；最大化和全屏也由程序自己设窗口范围（盖住任务栏），不走系统全屏。
- **播放**：libVLC 解码（Windows 版随程序带一份 VLC 播放内核），画面逐帧画进界面，控制栏叠在画面上。片源的播放列表经本机代理（`PlayProxy.kt`，只监听 127.0.0.1）按数据层的判定删掉插播广告，进度按去掉的时长换算；去掉广告后时间轴保持连续，片源把整个文件按 206 返回时改成 200 交给 VLC。只有播到片尾附近才自动下一集，中途断流先重连，几次不行就提示换源。缓冲时显示下载速度。随包的 VLC 没有插件缓存，安装或更新后第一次运行时在后台用 `vlc-cache-gen` 生成。快捷键：空格暂停、← → 快退快进 10 秒、F 全屏、N / P 下一集 / 上一集、Esc 退出全屏或返回；双击画面全屏。
- **串流服务**：程序随启动拉起 `desktop-server`（Node 脚本，用显卡和 FFmpeg 给电视去广告、超分），在「设置 → 串流服务」里显示电视配对码、状态和日志，可下载超分工具、选择下载影片的位置（可把已下载的一起搬过去）。关闭窗口后服务在后台继续运行（任务栏右下角的图标可重新打开或退出）。Windows 版随程序带一份 node.exe（构建时取自 `.desktop-tools/node`），不需要另外安装 Node.js；开发时用系统里的 Node。
- **电脑片库**：电视交给电脑下载、处理好的影片，可在电脑上直接播放。
- **启动诊断**：每次启动把各阶段耗时写进数据目录下的 `app/startup.log`；启动后一分钟内界面卡住超过 1 秒时记下当时在做什么。
- **数据目录**：Windows 在 `%LOCALAPPDATA%\SuperTV`，macOS 在 `~/Library/Application Support/SuperTV`；串流服务的配对码、同步资料、片库、超分工具在其下的 `.desktop-server`。

## 构建

需要 JDK 17。Windows 上还需要把 VLC 3.0.x（win64 压缩包）解压到仓库根目录的 `.desktop-tools/vlc`、把 node.exe 放到 `.desktop-tools/node`，构建时会打进程序，并用 `vlc-cache-gen` 生成 VLC 插件缓存（安装到 Program Files 后程序目录不能写，缓存必须随包带上）。在 PowerShell 里传版本号要加引号（`"-PappVersion=1.0.0"`），否则会在小数点处被拆开。

```bash
cd desktop
./gradlew run                    # 开发时直接运行（Mac 上把 VLC.app 放在 desktop/.vlc 下可以测试播放）
./gradlew createDistributable    # 生成程序目录（Windows：build/compose/binaries/main/app/SuperTV/SuperTV.exe）
./gradlew packageMsi -PappVersion=1.0.0   # Windows 安装程序，装到 Program Files；正式发布由 GitHub Actions 构建
```

`./gradlew snapshot -Pout=目录 -Psteps="home search:繁花 wait:8 shot:x play:小城良方 wait:15 move:600,400"` 不开窗口把页面渲染成图片，用来检查界面；`playsrc:片名|片源名|第几集` 按指定片源播放，`seek:秒`、`before-ad:first` 跳转。设置环境变量 `SUPERTV_DEBUG=1` 会输出数据层请求、广告段和播放事件。
