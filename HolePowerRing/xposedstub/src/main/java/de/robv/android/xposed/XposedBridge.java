package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * XposedBridge 日志入口（编译桩，运行时由 LSPosed 提供真实实现）。
 */
public final class XposedBridge {

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    /**
     * 运行时由 LSPosed 提供：把回调挂到任意已解析的 Method/Constructor 上。
     * 仅编译期可见，APK 中只保留调用点。
     */
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub only, provided by Xposed runtime");
    }

    /**
     * 运行时由 LSPosed 提供：绕过本模块已挂的 Hook，直接调用原始方法。
     * 用于「先改状态、稍后再放行原调用」的场景（如截图前先隐藏圆环）。
     */
    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws Throwable {
        throw new UnsupportedOperationException("stub only, provided by Xposed runtime");
    }
}
