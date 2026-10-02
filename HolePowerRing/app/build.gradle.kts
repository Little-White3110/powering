// AGP 9 已内置 Kotlin 支持，无需 org.jetbrains.kotlin.android；
// 组合使用 JetBrains Compose Compiler 插件（与 miuix 参考工程一致）。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// 版本号：默认值写在这里；
// CI 推送 tag（如 v1.2.3）时由 .github/workflows/release.yml 通过
// -PversionName=1.2.3 -PversionCode=1002003 覆盖，无需改代码。
// ---------------------------------------------------------------------------
fun gradleProp(name: String): String? =
    (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }

val DEFAULT_VERSION_CODE = 1
val DEFAULT_VERSION_NAME = "1.0.0"

val appVersionCode: Int = gradleProp("versionCode")?.let {
    val code = it.toIntOrNull()
    require(code != null && code > 0) { "versionCode 必须是正整数，实际收到：$it" }
    code
} ?: DEFAULT_VERSION_CODE

val appVersionName: String = gradleProp("versionName") ?: DEFAULT_VERSION_NAME

// 发布签名：命令行属性或环境变量任一提供即可（CI 用环境变量传密钥库内容）。
// 四要素齐全且密钥库文件存在时才启用真正的 release 签名；
// 否则回落到 debug 签名，保证 `assembleRelease` 永远产出「可直接安装」的 APK。
val releaseStoreFile = gradleProp("releaseStoreFile") ?: System.getenv("RELEASE_STORE_FILE")
val releaseStorePassword = gradleProp("releaseStorePassword") ?: System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = gradleProp("releaseKeyAlias") ?: System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword = gradleProp("releaseKeyPassword") ?: System.getenv("RELEASE_KEY_PASSWORD")
val releaseStoreType = gradleProp("releaseStoreType") ?: System.getenv("RELEASE_STORE_TYPE")

val hasReleaseKeystore = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrEmpty() } && releaseStoreFile?.let { file(it).exists() } == true

// 只在真的要打 release 包且缺密钥时提示，避免 IDE 同步等每次配置都刷屏
val isReleaseTaskRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (!hasReleaseKeystore && isReleaseTaskRequested) {
    logger.lifecycle(
        "[HolePowerRing] 未提供发布密钥（releaseStoreFile / releaseStorePassword / releaseKeyAlias / releaseKeyPassword），" +
            "本次 release 构建回落为 debug 签名，仅供本地安装测试，不可作为正式发布包。"
    )
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
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                if (!releaseStoreType.isNullOrEmpty()) storeType = releaseStoreType
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
