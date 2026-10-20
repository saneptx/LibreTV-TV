# LibreTV Android TV 壳

这是 `/tv` 页面对应的轻量 Android TV WebView 客户端。LibreTV 服务端仍运行在电脑、NAS 或云服务器上。

## 配置地址

用 Android Studio 打开本目录，在项目的 `gradle.properties` 或用户级 Gradle 属性中加入：

```properties
LIBRETV_URL=http://192.168.1.10:8080/tv
```

将 IP 换成运行 LibreTV 的设备地址。电视和服务端需要能互相访问；Android 模拟器可继续使用默认的 `10.0.2.2`。

## 构建

安装 JDK 17 和 Android SDK 35 后，在 Android Studio 中选择 `app` 并运行，或从 Gradle 工具窗口执行 `app > Tasks > build > assembleDebug`。

本目录暂未提交 Gradle Wrapper；如果电脑已安装 Gradle，也可以先生成 Wrapper 再构建：

```bash
gradle wrapper
./gradlew assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 当前边界

- 界面、搜索和详情使用 `/tv`；播放仍复用 LibreTV `/watch` 页面。
- 登录 Cookie 和观看历史保存在电视 WebView 本地。
- 同时注册 Android TV 和普通 Android 启动入口，可安装到电视盒子及常见安卓投影仪。
- 局域网 HTTP 已允许；公网部署请使用 HTTPS。
- 下一阶段可以把播放部分替换为 Media3/ExoPlayer，改善低配投影仪上的解码兼容性。
