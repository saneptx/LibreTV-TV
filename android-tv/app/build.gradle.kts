plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val libreTvUrl = providers.gradleProperty("LIBRETV_URL")
    .orElse("")

android {
    namespace = "org.libretv.tv"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "org.libretv.tv"
        minSdk = 23
        targetSdk = 35
        // 发布时由 tag 决定（v1.0.0 → -PappVersion=1.0.0，versionCode = 主×1000000 + 次×1000 + 修订）
        val release = providers.gradleProperty("appVersion").orNull
        versionCode = release?.split('.')?.map { it.toInt() }?.let { (a, b, c) -> a * 1_000_000 + b * 1_000 + c } ?: 33
        versionName = release ?: "0.8.6"
        buildConfigField("String", "LIBRETV_URL", "\"${libreTvUrl.get()}\"")
    }

    // 随包分发的内置片源表（仓库根目录的 source.json）
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/core-assets"))

    buildFeatures { buildConfig = true }

    // 发布版签名：密钥由环境变量提供（GitHub Actions 里来自仓库的 Secrets）。必须一直用同一把密钥，
    // 否则已安装的设备无法覆盖升级。没有提供时用本机的调试密钥签名。
    val keystore = providers.environmentVariable("SUPERTV_KEYSTORE").orNull?.let(::file)?.takeIf { it.isFile }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = providers.environmentVariable("SUPERTV_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("SUPERTV_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("SUPERTV_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    // 保持与本项目的 compileSdk 35 / AGP 8.7 兼容。
    val media3Version = "1.8.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    testImplementation("junit:junit:4.13.2")
}

// 内置片源表随 APK 分发；界面、播放器、数据层都是原生代码，不打包任何网页。
val bundleSources by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile.resolve("source.json")) { rename { "sources.json" } }
    into(layout.buildDirectory.dir("generated/core-assets"))
}
tasks.named("preBuild").configure { dependsOn(bundleSources) }
