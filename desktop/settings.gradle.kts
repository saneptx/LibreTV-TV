pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "SuperTV-Desktop"
// 与手机、电视共用的数据层
include(":core")
project(":core").projectDir = file("../android-tv/core")
