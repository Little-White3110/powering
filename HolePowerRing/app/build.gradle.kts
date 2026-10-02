// AGP 9 已内置 Kotlin 支持，无需 org.jetbrains.kotlin.android；
// 组合使用 JetBrains Compose Compiler 插件（与 miuix 参考工程一致）。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.powerring.hole"
    // AGP 9.x 新 DSL（compileSdk 37，与参考工程一致）
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.powerring.hole"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 外观配置 JSON 导入导出（ui/ConfigJson）。只用手动 JsonObject 树 API，
    // 不依赖 @Serializable 代码生成，因此无需 kotlin 序列化编译器插件。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // 纯数据层（ring/CustomColors、ui/HexColor）的 JVM 单测；UI 与 Hook 侧仍靠真机验证
    testImplementation("junit:junit:4.13.2")

    // Xposed API 仅在编译期可见，运行时由 LSPosed 提供
    compileOnly(project(":xposedstub"))

    // 模块配置页：miuix（HyperOS）Compose 组件
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4-rc01")
    implementation("androidx.activity:activity-compose:1.13.0")
    // Comppose 运行时版本与 miuix 0.9.4-rc01 编译时的 CMP 1.11.1 对齐
    implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
    implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
    implementation("org.jetbrains.compose.ui:ui:1.11.1")
}
