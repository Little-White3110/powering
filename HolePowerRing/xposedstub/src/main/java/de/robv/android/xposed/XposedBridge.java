package de.robv.android.xposed;

/**
 * XposedBridge 日志入口（编译桩，运行时由 LSPosed 提供真实实现）。
 */
public final class XposedBridge {

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }
}
