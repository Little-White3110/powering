package com.powerring.hole.ring

import android.graphics.Rect
import android.graphics.RectF
import android.view.DisplayCutout
import android.view.View
import android.view.WindowInsets
import com.powerring.hole.core.ModuleLog
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap

/**
 * 挖孔几何解析。
 *
 * 首选公开 API：View.getRootWindowInsets().getDisplayCutout().getBoundingRects()
 * （矩形坐标为**屏幕（display）坐标**）。
 * 兜底：反射 DisplayCutoutBaseView 的 displayInfo.logicalDisplayCutout。
 *
 * 通过 getLocationOnScreen 把屏幕坐标换算为 View 自身坐标系（onDraw 的 Canvas 坐标）。
 */
object CutoutGeometry {

    data class Hole(
        val cx: Float,
        val cy: Float,
        /** 挖孔外接圆半径（px） */
        val holeRadius: Float,
        /** 环的外边界是否超出 View 边界（R2 裁切风险，供日志/后续扩窗使用） */
        val clipRisk: Boolean,
    )

    fun resolve(view: View, outerRadius: Float): Hole? {
        val rects = getCutoutRects(view)
        val raw = rects?.let { pickCurrentCutout(it, view) }
        if (rects == null || raw == null) {
            logOnce(view, rects, raw, null)
            return null
        }

        // boundingRects 是屏幕坐标，减去 View 在屏幕中的位置换算到 View 局部坐标
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val local = RectF(
            (raw.left - location[0]).toFloat(),
            (raw.top - location[1]).toFloat(),
            (raw.right - location[0]).toFloat(),
            (raw.bottom - location[1]).toFloat(),
        )
        if (local.width() <= 0f || local.height() <= 0f) {
            logOnce(view, rects, raw, local)
            ModuleLog.e("挖孔局部矩形异常: local=$local viewSize=${view.width}x${view.height}", null)
            return null
        }

        val w = local.width()
        val h = local.height()
        // cutout 矩形是系统安全区，不一定等于物理孔：
        // - 普通打孔（矩形接近正方形）：圆心即矩形中心；
        // - 竖长安全区（如本机 72x144，屏下/长条孔机型）：灵动岛小岛胶囊
        //   在安全区内垂直居中，环与岛对齐，圆心取矩形中心；
        // - 横置安全区：同理取中心。
        val cx = local.centerX()
        val cy = local.centerY()
        val holeRadius = minOf(w, h) / 2f

        val clipRisk = cx - outerRadius < 0f || cy - outerRadius < 0f ||
            cx + outerRadius > view.width.toFloat() || cy + outerRadius > view.height.toFloat()

        logOnce(view, rects, raw, local)
        return Hole(cx, cy, holeRadius, clipRisk)
    }

    /** 取当前方向上真正承载摄像头的那个挖孔矩形（顶部/侧置通用）。 */
    private fun pickCurrentCutout(rects: List<Rect>, view: View): Rect? {
        val candidates = rects.filter { !it.isEmpty }
        if (candidates.isEmpty()) return null
        // 到屏幕四边的最小距离越小越可能是当前挖孔；同为边缘挖孔时取面积更大的
        return candidates.minWithOrNull(compareBy<Rect>(
            { rect ->
                minOf(rect.top, rect.left, view.resources.displayMetrics.widthPixels - rect.right,
                    view.resources.displayMetrics.heightPixels - rect.bottom).coerceAtLeast(0)
            },
            { -it.width().toLong() * it.height() },
        ))
    }

    @Suppress("DEPRECATION")
    private fun getCutoutRects(view: View): List<Rect>? {
        // 1) 公开 API
        try {
            val insets: WindowInsets? = view.rootWindowInsets
            val cutout: DisplayCutout? = insets?.displayCutout
            val list = cutout?.boundingRects
            if (!list.isNullOrEmpty()) return list
        } catch (t: Throwable) {
            ModuleLog.e("rootWindowInsets 取挖孔失败，尝试反射", t)
        }

        // 2) 反射兜底：DisplayCutoutBaseView.displayInfo.logicalDisplayCutout
        return try {
            val displayInfo = XposedHelpers.getObjectField(view, "displayInfo") ?: return null
            val logicalCutout = XposedHelpers.getObjectField(displayInfo, "logicalDisplayCutout")
                ?: return null
            @Suppress("UNCHECKED_CAST")
            (logicalCutout as? DisplayCutout)?.boundingRects
        } catch (t: Throwable) {
            ModuleLog.e("反射 displayInfo 取挖孔失败", t)
            null
        }
    }

    // ---- 一次性诊断日志 ----
    private const val MAX_LOGS = 5
    private val loggedViews: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private var logCount = 0

    private fun logOnce(view: View, rects: List<Rect>?, picked: Rect?, local: RectF?) {
        if (logCount >= MAX_LOGS) return
        synchronized(loggedViews) {
            if (!loggedViews.add(view)) return
            logCount++
        }
        val loc = IntArray(2)
        runCatching { view.getLocationOnScreen(loc) }
        val insetsDesc = runCatching {
            view.rootWindowInsets?.displayCutout?.toString()?.take(300)
        }.getOrNull()
        ModuleLog.i(
            "挖孔诊断: cls=${view.javaClass.name} size=${view.width}x${view.height} " +
                "locOnScreen=(${loc[0]},${loc[1]}) insetsCutout=${insetsDesc ?: "null"} " +
                "rects=$rects picked=$picked local=$local",
        )
    }
}
