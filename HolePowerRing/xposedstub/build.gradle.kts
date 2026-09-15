// Xposed Framework API 的最小编译桩（Java）。
// 运行时由 LSPosed 提供真正的实现，因此仅以 compileOnly 形式接入，绝不能打进 APK。
plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
