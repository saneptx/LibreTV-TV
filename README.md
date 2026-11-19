# SuperTV

SuperTV 是一套家用影视聚合播放器：聚合公开的 Apple CMS 采集站，搜索、选集、去插播广告、记住观看进度，并在家里的设备之间同步。三端都是原生应用，界面统一为黑白灰简约风格、同一个标志：

| 端 | 位置 | 说明 |
| --- | --- | --- |
| 电视 / 投影仪 | [`android-tv/`](android-tv/README.md) | Android 原生遥控器界面，Media3 播放。可连接电脑，由电脑显卡去广告、AI 超分后串流给电视 |
| 手机 | [`android-tv/`](android-tv/README.md)（同一个 APK） | 原生触屏界面，自己直连片源看片；也是电视的伴侣：发现电视、同步、投屏接力、遥控、代替遥控器打字 |
| 电脑 | [`desktop/`](desktop/README.md) | Compose Desktop 程序（Windows / macOS），libVLC 播放；同时运行给电视用的串流服务 |
| 串流服务 | [`desktop-server/`](desktop-server/README.md) | Node.js 服务，电脑端自动启动：去广告、FSRCNNX 超分、NVENC 编码、局域网同步中枢、电脑片库 |

三端共用同一套 Kotlin 数据层 [`android-tv/core`](android-tv/core)：片源解析、去广告判定、资料库、同步、订阅都只有这一份实现。

## 设备之间怎么配合

- **电视 / 投影仪**屏幕大，需要时连接电脑：在「设置 → 电脑服务」里选中自动发现的电脑，输入电脑端显示的 6 位配对码。
- **手机**不连电脑、不做超分：自己直连片源看片，并自动发现局域网里的电视（第一次连接要在电视上确认），与电视同步、把正在看的内容接力到电视上播放。
- **电脑**运行串流服务，作为局域网里的同步中枢：观看历史、各集进度、搜索记录、片库收藏按修改时间合并，删除也同步。只在家里的局域网内同步，不经过任何云服务。

## 构建

### 安卓（手机 + 电视）

需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0（`ANDROID_HOME` 或 `android-tv/local.properties` 的 `sdk.dir`）。

```bash
./android-tv/gradlew -p android-tv :app:assembleDebug
```

APK 输出到 `android-tv/app/build/outputs/apk/debug/app-debug.apk`，手机、电视、投影仪装同一个包。投影仪开启 ADB 调试后可以覆盖安装（IP 换成设备的实际地址）：

```bash
adb connect 192.168.1.20:5555
adb -s 192.168.1.20:5555 install -r android-tv/app/build/outputs/apk/debug/app-debug.apk
```

升级请覆盖安装；卸载或清除应用数据会删除本地设置和记录。本地电视模拟器：`./android-tv/run_local_tv_emulator.sh`。

### 电脑端

需要 JDK 17。Windows 构建前把 VLC 3.0.x（win64）解压到 `.desktop-tools/vlc`、把 node.exe 放到 `.desktop-tools/node`，会一起打进程序：

```bash
cd desktop
./gradlew run                   # 开发时运行
./gradlew createDistributable   # 程序目录（Windows：build/compose/binaries/main/app/SuperTV/SuperTV.exe）
./gradlew packageMsi -PappVersion=1.0.0   # Windows 安装程序（build/compose/binaries/main/msi/）
```

超分需要 NVIDIA 显卡；第一次使用在「设置 → 串流服务」里下载超分工具（FFmpeg 与模型）。电视要从局域网连接，Windows 防火墙需放行 TCP 8090。详见 [电脑端说明](desktop/README.md) 和 [串流服务说明](desktop-server/README.md)。

### 测试

```bash
./android-tv/gradlew -p android-tv :core:test :app:testDebugUnitTest   # 数据层与安卓单元测试
npm run desktop:test                                                    # 串流服务
```

## 自动构建与发布

GitHub Actions（[`.github/workflows/build.yml`](.github/workflows/build.yml)）：

- 推送到 `main`、提 PR 时：跑测试，构建安卓 APK 和 Windows 安装程序（MSI），在这次运行的页面里可以下载。
- 推送版本 tag 时（例如 `git tag v1.0.0 && git push origin v1.0.0`）：额外创建 GitHub Release，附上 `SuperTV-1.0.0.msi` 和 `SuperTV-1.0.0.apk`。版本号同时用作 APK 的版本和 MSI 的版本，要写成三段数字，主版本号在 1 到 255 之间。

Windows 安装程序自带播放内核（VLC 3.0.x）和串流服务用的 Node.js（最新 LTS），构建时从官网下载并核对 SHA-256；VLC 插件缓存在构建时生成。安装到 `C:\Program Files\SuperTV`（可以自选目录），数据仍在 `%LOCALAPPDATA%\SuperTV`，新版本覆盖安装即可升级。

安卓发布版的签名密钥放在仓库的 Secrets 里（Settings → Secrets and variables → Actions）：`ANDROID_KEYSTORE_BASE64`（密钥库文件的 base64）、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。必须一直用同一把密钥，已安装的设备才能覆盖升级；没有配置时，构建出的 APK 用临时密钥签名，不能覆盖安装已有的版本。本地构建发布版时用同名的环境变量（`SUPERTV_KEYSTORE` 指向密钥库文件），不提供则用本机的调试密钥。

## 片源与订阅

片源是 Apple CMS 采集接口（形如 `https://example.com/api.php/provide/vod`）。内置片源表是仓库根目录的 [`source.json`](source.json)，构建时打进 APK 和电脑端。也可以在设置里添加单个片源、订阅片源列表，或从剪贴板批量导入：每行写一个采集接口地址（前面可以带名称）或订阅地址，也可以直接粘贴下面格式的 JSON。

订阅地址指向一份公开的 JSON，可用 Gist、对象存储或任意静态托管：

```json
{
  "name": "我的源列表",
  "sources": [
    { "name": "示例点播源", "url": "https://example.com/api.php/provide/vod", "detail": "https://example.com", "isAdult": false }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 否 | 列表名称，显示在订阅条目上 |
| `sources[].url` | **是** | 采集接口地址（http/https）；按地址去重，最多 100 个 |
| `sources[].name` | 否 | 显示名，缺省用地址的主机名 |
| `sources[].detail` | 否 | 详情页根地址，给列表接口拿不到播放地址、需要读详情页的源用 |
| `sources[].isAdult` | 否 | 成人内容标记 |

- 裸数组 `[{ "name": "...", "url": "..." }]` 视为片源列表；`liveSources`（直播源）会被忽略，SuperTV 暂不支持直播。
- 也可以直接填 **TVBox 配置**（`{"sites": [...]}`）：只导入能直接搜索的采集接口（`type: 1`，或地址是 `api.php/provide/vod` 的站点）；Spider、XML 接口、标记为不可搜索的站点会跳过，并提示跳过了多少、各是什么原因。TVBox 配置常有上百个站点但多数是 Spider，只导入十几个属正常。
- 订阅的片源以远端列表为准，重新同步会恢复。在设置里删掉的订阅片源，之后同步也不会再加回来；可以在片源列表里一键恢复。

## 安全说明

- 电脑配对：电视第一次连接时输入电脑端显示的 6 位配对码，换取长期令牌；5 分钟内输错 5 次会暂时锁定。配对码每次启动串流服务都会更换。
- 手机第一次连接电视时，要在电视上确认。
- 令牌只保存在已配对的设备上，不要公开或写进媒体地址。
- 串流服务只读取公网片源（拒绝内网与保留地址，含 DNS 解析后校验）；电脑端的播放代理只监听 127.0.0.1。

## 来源与许可

本项目基于 [LibreSpark/LibreTV](https://github.com/LibreSpark/LibreTV)（AGPL-3.0）改写，许可证同为 AGPL-3.0，见 [LICENSE](LICENSE)。

## 免责声明

本项目不存储、不制作任何视频内容，仅提供第三方公开接口的聚合与播放能力，内容的合法性由对应数据源负责。
