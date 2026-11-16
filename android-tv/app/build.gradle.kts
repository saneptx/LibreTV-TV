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
        versionCode = 33
        versionName = "0.8.6"
        buildConfigField("String", "LIBRETV_URL", "\"${libreTvUrl.get()}\"")
    }

    // aapt 默认忽略以 _ 开头的目录；Next 静态运行时放在 _next 中，必须保留。
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:CVS:thumbs.db:picasa.ini:*~"
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/web-assets"))

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
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
    // 保持与本项目的 compileSdk 35 / AGP 8.7 兼容。
    val media3Version = "1.8.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
}

// 界面随 APK 构建，不依赖开发机正在运行的 Next 服务。
val bundleTvWeb by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir.parentFile)
    commandLine("node", "android-tv/scripts/build-web.mjs")
    inputs.dir("../../src")
    inputs.dir("../../public")
    inputs.files("../../source.json", "../../package.json", "../../package-lock.json", "../../next.config.ts",
        "../../tsconfig.json", "../../tailwind.config.ts", "../../postcss.config.mjs", "../scripts/build-web.mjs")
    outputs.dir(layout.buildDirectory.dir("generated/web-assets"))
}
tasks.named("preBuild").configure { dependsOn(bundleTvWeb) }
