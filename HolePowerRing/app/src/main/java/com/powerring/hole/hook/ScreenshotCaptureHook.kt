package com.powerring.hole.hook

import android.graphics.Rect
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingWindowController
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers

/**
 * 截图采集点探针（FLAG_SECURE 被本机特权截图绕过后的备案机制，见报告 §17）。
 *
 * 本 ROM 的全屏/策略/部分截图都汇入
 * `com.android.systemui.screenshot.ImageCaptureImpl.captureDisplay`
 * （xref 实证三条调用路），方法体内同步执行像素采集。before 回调先于
 * 采集执行，此刻提交 SurfaceFlinger 层隐藏事务；after 回调（含采集抛
 * 异常的情形）递减计数，归零后延时恢复。类或方法找不到时降级为不生效。
 */
object ScreenshotCaptureHook {

    private const val CAPTURE_CLASS = "com.android.systemui.screenshot.ImageCaptureImpl"

    fun install(classLoader: ClassLoader) {
        val captureClass = XposedHelpers.findClassIfExists(CAPTURE_CLASS, classLoader)
        if (captureClass == null) {
            ModuleLog.e("未找到 $CAPTURE_CLASS，截图隐藏不生效", null)
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                captureClass,
                "captureDisplay",
                Int::class.javaPrimitiveType,
                Rect::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            RingWindowController.beginScreenshotCapture()
                        } catch (t: Throwable) {
                            ModuleLog.e("截图隐藏 before 回调异常", t)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            RingWindowController.endScreenshotCapture()
                        } catch (t: Throwable) {
                            ModuleLog.e("截图隐藏 after 回调异常", t)
                        }
                    }
                },
            )
            ModuleLog.i("截图采集点 Hook 已安装（captureDisplay）")
        } catch (t: Throwable) {
            ModuleLog.e("Hook captureDisplay 失败，截图隐藏不生效", t)
        }
    }
}
