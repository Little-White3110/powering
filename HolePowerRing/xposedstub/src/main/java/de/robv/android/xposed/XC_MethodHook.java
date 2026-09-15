package de.robv.android.xposed;

import java.lang.reflect.Method;

/**
 * 方法 Hook 回调（编译桩，运行时由 LSPosed 提供真实实现）。
 */
public abstract class XC_MethodHook {

    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public interface Unhook {
        void unhook();
    }

    public static class MethodHookParam {
        public Method method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }
    }
}
