plugins {
    id("org.jetbrains.kotlin.jvm")
}

// 手机、电视、电脑三端共用的数据层：片源、搜索、去广告、资料库与同步、电脑串流客户端。纯 Kotlin，不依赖界面。
kotlin { jvmToolchain(17) }

dependencies {
    // 安卓系统自带 org.json；电脑端和单元测试用同一份实现
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
