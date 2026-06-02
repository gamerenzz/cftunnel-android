plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

dependencies {
    // 引入 JetBrains 官方 Desktop UI 核心库
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

android {
    // 规避 Gradle 底层不必要的多平台校验
}

compose.desktop {
    application {
        mainClass = "com.qingchen.cftunnel.MainKt"
        nativeDistributions {
            // 指示打包格式为 .zip 纯绿色版压缩包
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Zip)
            packageName = "cftunnel"
            packageVersion = "1.0.0"
            
            // 基础配置：设定图标、关于信息等
            description = "cftunnel Windows 控制台"
            copyright = "Copyright © 2026 qingchen. All rights reserved."
        }
    }
}
