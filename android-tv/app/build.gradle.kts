plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val libreTvUrl = providers.gradleProperty("LIBRETV_URL")
    .orElse("http://10.0.2.2:8080/tv")

android {
    namespace = "org.libretv.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.libretv.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "LIBRETV_URL", "\"${libreTvUrl.get()}\"")
    }

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
