package de.robv.android.xposed;

import java.lang.reflect.Method;

/**
 * Xposed 反射/Hook 辅助方法（编译桩，运行时由 LSPosed 提供真实实现）。
 * 方法签名与 Xposed API 82 保持一致。
 */
public final class XposedHelpers {

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        return null;
    }

    public static Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        throw new ClassNotFoundException(className);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader classLoader, String methodName,
            Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static Method findMethodIfExists(
            Class<?> clazz, String methodName, Object... parameterTypes) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    public static int getIntField(Object obj, String fieldName) {
        return 0;
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        return false;
    }

    public static float getFloatField(Object obj, String fieldName) {
        return 0f;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return null;
    }
}
