import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation("org.json:json:20240303")
    // 播放内核：libVLC（随程序分发在 vlc 目录）
    implementation("uk.co.caprica:vlcj:4.8.3")
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "org.libretv.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx1g")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "SuperTV"
            // 版本号来自发布用的 tag（v1.2.3 → -PappVersion=1.2.3）；MSI 要求三段数字，主版本号在 1 到 255 之间
            packageVersion = providers.gradleProperty("appVersion").getOrElse("1.0.0")
            // jpackage 在中文 Windows 上按 GBK 读参数文件，这里只能写英文
            description = "SuperTV Desktop"
            vendor = "SuperTV"
            // 随程序分发的文件：串流服务脚本、内置片源表、播放内核（构建前由 prepareAppResources 准备）
            appResourcesRootDir.set(layout.buildDirectory.dir("app-resources"))
            modules("java.net.http", "jdk.httpserver", "jdk.unsupported")
            windows {
                // 装到 Program Files（安装时需要管理员确认，可自选目录）。不用按用户安装：
                // 那样会装进 %LOCALAPPDATA%\SuperTV，和程序的数据目录撞在一起
                menuGroup = "SuperTV"
                menu = true
                shortcut = true
                dirChooser = true
                iconFile.set(project.file("icons/supertv.ico"))
                upgradeUuid = "6f5b9a5e-2d3c-4b8e-9a51-3c7d2e4f8a10"
            }
            macOS { iconFile.set(project.file("icons/supertv.icns")) }
        }
    }
}

// 把仓库里的串流服务脚本、内置片源表，以及（Windows 上）VLC 播放内核放进程序资源目录
val prepareSuperTvResources by tasks.registering(Sync::class) {
    val repo = rootProject.projectDir.parentFile
    into(layout.buildDirectory.dir("app-resources/common"))
    from(repo.resolve("desktop-server")) { into("service/desktop-server"); include("*.mjs"); exclude("*.test.mjs", "check-*.mjs", "generate-fixture.mjs", "fixture.mjs") }
    from(repo.resolve("source.json")) { rename { "sources.json" } }
    // 串流服务的运行环境：随程序带一份 node.exe，用户不需要另外安装 Node.js
    val node = repo.resolve(".desktop-tools/node")
    if (node.isDirectory) from(node) { into("service/node") }
    val vlc = repo.resolve(".desktop-tools/vlc")
    if (vlc.isDirectory) from(vlc) { into("vlc"); exclude("vlc.exe", "uninstall.exe", "lua/**", "skins/**", "locale/**", "hrtfs/**", "*.txt", "*.html") }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareSuperTvResources) }

// 随包的 VLC 没有插件缓存（plugins.dat），没有它 VLC 每次都要逐个加载几百个插件 DLL（约 20 多秒）。
// 安装到 Program Files 后程序目录不能写，所以在构建时生成好，一起打进安装包
val vlcPluginCache by tasks.registering(Exec::class) {
    val plugins = layout.buildDirectory.dir("app-resources/common/vlc/plugins").get().asFile
    val gen = rootProject.projectDir.parentFile.resolve(".desktop-tools/vlc/vlc-cache-gen.exe")
    dependsOn(prepareSuperTvResources)
    onlyIf { System.getProperty("os.name").startsWith("Windows") && gen.isFile && plugins.isDirectory }
    commandLine(gen.path, plugins.path)
}
prepareSuperTvResources.configure { finalizedBy(vlcPluginCache) }
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(vlcPluginCache) }

// 用标志的矢量画法生成各尺寸 PNG，再合成 Windows 的 .ico 和 macOS 的 .icns（结果提交在 icons/ 下）
val generateIcons by tasks.registering(JavaExec::class) {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.libretv.desktop.IconGen")
    args(layout.buildDirectory.dir("icons").get().asFile.path)
}

// 开发用：把页面渲染成 PNG 检查界面。gradle snapshot -Pout=目录 -Psteps="home settings:sources"
val snapshot by tasks.registering(JavaExec::class) {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.libretv.desktop.Snapshot")
    dependsOn("prepareAppResources")
    systemProperty("compose.application.resources.dir", layout.buildDirectory.dir("compose/tmp/prepareAppResources").get().asFile.path)
    providers.environmentVariable("SUPERTV_HOME").orNull?.let { environment("SUPERTV_HOME", it) }
    args(listOf(providers.gradleProperty("out").getOrElse("build/snapshots")) + providers.gradleProperty("steps").getOrElse("home").split(" "))
}
