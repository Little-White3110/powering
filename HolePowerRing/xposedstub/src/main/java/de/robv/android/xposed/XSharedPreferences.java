package de.robv.android.xposed;

import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * 编译期桩：运行时由 LSPosed 框架提供真实实现（lspd 守护进程代读模块 prefs）。
 * 仅声明本项目用到的方法，签名与 de.robv.android.xposed.XSharedPreferences 一致。
 */
public class XSharedPreferences {

    public XSharedPreferences(String packageName, String prefName) {
        throw new RuntimeException("STUB");
    }

    @Deprecated
    public XSharedPreferences(File file) {
        throw new RuntimeException("STUB");
    }

    @Deprecated
    public boolean isWorldReadable() {
        throw new RuntimeException("STUB");
    }

    @Deprecated
    public void setWorldReadable() {
        throw new RuntimeException("STUB");
    }

    public void load() {
        throw new RuntimeException("STUB");
    }

    public boolean contains(String key) {
        throw new RuntimeException("STUB");
    }

    public Map<String, ?> getAll() {
        throw new RuntimeException("STUB");
    }

    public Set<String> getStringSet(String key, Set<String> defValue) {
        throw new RuntimeException("STUB");
    }

    public String getString(String key, String defValue) {
        throw new RuntimeException("STUB");
    }

    public boolean getBoolean(String key, boolean defValue) {
        throw new RuntimeException("STUB");
    }

    public float getFloat(String key, float defValue) {
        throw new RuntimeException("STUB");
    }

    public int getInt(String key, int defValue) {
        throw new RuntimeException("STUB");
    }

    public long getLong(String key, long defValue) {
        throw new RuntimeException("STUB");
    }

    public long lastModified() {
        throw new RuntimeException("STUB");
    }
}
