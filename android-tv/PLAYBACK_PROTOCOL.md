# 原生播放协议 v1

电视页面继续负责焦点、控制栏、选集、换源和本地历史。Android Media3 负责网络加载、解码及视频输出。

`NativePlayerHost` 使用 WebView 的 origin 限制消息桥：只有配置服务的主框架可向 `window.LibreTvNativePlayer.postMessage()` 发送 JSON。普通浏览器、旧 APK 或不支持消息桥的 WebView 继续使用网页播放器。

## 命令

每条命令包含 `version: 1`、`sessionId` 和 `type`。每次换源/换集创建新 session；迟到的旧 session 命令和状态都被忽略。时间统一使用**秒**，Android 内部再换算成毫秒。

`load` 的 `request`：

```json
{
  "url": "https://example.com/video.m3u8",
  "proxyUrl": "http://server:8080/api/proxy?url=...",
  "headers": {},
  "position": 123.5,
  "adFilter": true
}
```

`url` 只接受 HTTP(S)。`proxyUrl` 可省略；当前由网页适配层构造，Android 限制为配置服务同 origin 的 `/api/proxy` 地址。0.3.0 独立版不再构造电脑代理地址，设备直接连接片源。

其余命令：`play`、`pause`、`seek`（position）、`rate`（rate）、`fit`（contain/cover）、`filter`（enabled）、`stop`。

## 状态与进度

Android 通过 `libretv-native-player-state` CustomEvent 回传：position、duration、bufferedPosition、bufferedDuration、paused、buffering、ready、ended、rate、width、height、bytesPerSecond、error、rendered、route、fit。状态每 500 ms 更新，下载速度统计最近两秒真实收到的网络字节，空闲后归零。

网页按原有 source/vod/episode 键保存进度，每五秒、暂停及退出时写入；换集时使用上一 session 自己的回调保存，避免将旧进度写到下一集。自动连播继续由网页负责。

## 网络与生命周期

- 原生 HTTP 直接加载清单、分片及密钥；沿用保守的广告分组过滤，保留 KEY/MAP 和正片 DISCONTINUITY。
- Media3 自行重试瞬时网络错误，最终网络错误可回退到可选服务器代理一次。格式/解码错误直接通知网页换源。
- WebView 登录 Cookie 只附加到配置服务的代理请求，不作为片源通用请求头发送。
- App 进入后台暂停，返回后恢复之前正在播放的会话；手动暂停保持暂停。
- 离开播放页及 Activity 销毁会释放播放器。WebView 背景只在原生播放页面透明。

## 当前范围

0.3.0 已将界面、搜索与详情迁入 APK。以下验证记录对应最初 0.2.0 原生播放阶段。原生播放器目前使用 Media3 自身的内存缓冲，尚未接入网页 CacheStorage 的分片预取/缓存，也未实现原生外挂字幕选择。4K/HDR、特定编码和 DRM 是否可播放仍取决于设备解码器与片源，需要分别验证。

## 验证记录

- 网页类型检查、ESLint、488 项 Vitest 测试通过；Android 构建、4 项过滤单元测试和 lint 检查通过。
- Android 11 模拟器验证了原生视频实际输出、暂停/继续、10 秒跳转及取消、倍速、切集进度隔离、换源、后台恢复、手动暂停保持、退出释放。
- 模拟器真实限速验证了缓冲接收速率、恢复带宽后收起提示，以及首次缓冲中手动暂停时不再显示缓冲提示。
- 可控 HTTP 失败验证了片源请求头传递、可选代理回退、错误提示及失败后的遥控器操作。
- JMGO NS1 实机确认原生 1280×720 视频画面、暂停、快进、倍速、菜单和返回首页。当前测试片源直连较慢，通过电脑代理可以持续播放；这一现象与设备已经成功解码输出画面分别验证。

## 0.4.0 可选电脑增强链路

设备仍直接搜索和获取影片详情。只有开始播放时将原视频 URL、原片进度和广告开关提交给配对电脑，等待电脑首段完成，然后将电脑的 HLS 地址及本地 Authorization 请求头交给 Media3。`processing=enhance` 时关闭设备端二次广告过滤；`label` 表示电脑实际处理能力。Media3 解码和最终显示仍发生在电视或投影仪。

电脑返回 `position` 为去广告后的播放位置，`timeMap` 为按原片时间排序的删除区间。设备保存历史及切回直连时将位置映射回原片时间；质量探测记录 `sourceHeight`，不会将增强后的尺寸误写为片源原始清晰度。处理阶段通过既有缓冲提示显示，不改变遥控器逻辑和页面布局。具体服务协议、模型和限制见 [电脑增强串流说明](../desktop-server/README.md)。

## 0.4.1 处理结果与 4K

增强服务返回 `processingInfo`：输入/输出宽高、`superResolution`、实际模型、广告分片过滤是否支持/开启、删除分片数及秒数。客户端验证后生成 `presentation.video` / `presentation.ads`，供默认和原生界面统一显示。缺少信息时显示未知，不能依据开关推断已去广告或依据输出尺寸推断已超分。直连/原画转发从实际解码尺寸显示未超分。

电脑服务可显式选择 `--target-height 2160`，最大输出 3840×2160，保留原比例；默认 1080P 不变。模拟器已验证实际 4K 解码和标注，但真实 720P 片源处理约 0.83 倍速，投影仪端性能与连续播放仍需单独验证。

## 0.4.2 升档与换源排序

电脑端默认 targetHeight=auto，按原始视频提高一个档位，720P → 1080P、1080P → 4K、4K 保持。宽屏裁边按16:9包围尺寸归档，保持实际比例。`/v1/status` 以 upscalePolicy=next-tier 标识策略；显式 target-height 数值仍为固定上限。

换源业务结果携带数字 height，使用原始片源探测或播放实测高度；过滤过期缓存，不使用超分输出高度。默认和原生面板均按可用性、原始分辨率降序、响应速度依次排序，当前来源不再置顶。画质更新可重排按钮，焦点与选择始终绑定来源标识。

## 0.4.3 来源缓存与超分开关

设备按关键词、过滤设置和完整片源身份缓存每个来源的搜索结果 10 分钟，失败仅缓存 30 秒。换源面板的可用性、集数和响应时间结论保存 5 分钟；超过后先显示最近列表并后台更新，旧结果最多保留 24 小时。缓存有 1 MiB / 250 项上限并持久化到设备本地，重启应用可复用；关闭面板时取消的结果不写入。实际播放地址仍只在内存缓存 60 秒，避免将易过期的 URL 当成长效缓存。来源地址/启用集合/过滤设置变化会换缓存键，手动“刷新来源”绕过各层缓存。画质失败与未知结果也尊重已有有效期。

片源管理和电脑串流播放设置提供独立 `superResolution` 开关，默认开启并持久化。播放创建请求携带布尔值；关闭时电脑仅转码到原始尺寸，广告过滤由 `adFilter` 独立决定；开启时沿用自动升一档策略。播放中切换会从原片进度重建处理任务，保留时间轴换算。电脑状态增加 capabilities.superResolutionToggle，processingInfo 增加 superResolutionEnabled；关闭时标注“超分已关闭（原始分辨率）”。关闭请求遇到不支持的新字段的旧服务会提示更新，避免静默超分。电脑处理缓存隔离开关与广告设置。

## 0.4.4 统一桌面入口

MainActivity 默认转交 NativeTvActivity，电视桌面、普通桌面和模拟器脚本共用原生复刻界面；无需 native_preview 参数。内部 Activity 在调试和发布清单中均启用且不导出，调试版保留 legacy_web_ui=true 用于旧网页对照。重复从桌面打开时 CLEAR_TOP / SINGLE_TOP 复用现有界面，避免堆叠播放器及业务实例。片源配置、配对、缓存与历史沿用同一 local origin。

## 0.4.5 播放换源面板的滚动边界

照片对应的是播放时的换源面板，而非首页片源管理。修复该面板来源增多时的绘制溢出：正文直接占用标题与页脚之间的剩余高度，左右栏独立滚动；左侧 ScrollView 启用裁切，根 overlay 上的焦点光晕也遵守启用裁切的滚动祖先边界。卡片内边距放在列表内容中，滚动请求额外保留焦点框空间，最后一项的底边不会被裁掉。刷新与返回按钮顶部对齐，右列及最后单个来源可向右进入对应切换/重试操作，左键返回原来源。

当时用模拟器脚本（已随网页调试接口一起删除）在模拟器使用 37 个隔离来源、长名称与长错误文本，检查开头/末尾截图页脚间隔的像素、最后项完整可见、标题页脚固定、右侧重试可达以及浏览不触发播放。报告 `.desktop-server/source-layout-emulator.json`，修复前复现截图 `/tmp/libretv-many-sources-before.png`。模拟器替身结束自动清理，不修改来源或历史。
