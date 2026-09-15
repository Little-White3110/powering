package de.robv.android.xposed.callbacks;

/**
 * Xposed 框架回调基类（编译桩，运行时由 LSPosed 提供真实实现）。
 * 仅声明本模块实际使用到的字段，不依赖 Android 类，便于以纯 Java 库形式提供。
 */
public abstract class XC_LoadPackage {

    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
