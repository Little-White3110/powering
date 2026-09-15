package com.powerring.hole.ring

import android.graphics.Rect
import android.graphics.RectF
import android.view.DisplayCutout
import android.view.View
import android.view.WindowInsets
import com.powerring.hole.core.ModuleLog
import de.robv.android.xposed.XposedHelpers

/**
 * 挖孔几何解析。
 *
 * 首选公开 API：View.getRootWindowInsets().getDisplayCutout().getBoundingRects()。
 * 兜底：反射 DisplayCutoutBaseView 的 displayInfo.logicalDisplayCutout
 * （隐藏 API，受限时静默失败）。
 *
 * 输出坐标统一换算为 View 自身坐标系（onDraw 的 Canvas 坐标）。
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
        val rects = getCutoutRects(view) ?: return null
        val raw = pickCurrentCutout(rects, view) ?: return null

        // 挖孔矩形坐标属于窗口坐标，减去 View 在窗口中的位置换算到 View 坐标
        val local = RectF(
            (raw.left - view.left).toFloat(),
            (raw.top - view.top).toFloat(),
            (raw.right - view.left).toFloat(),
            (raw.bottom - view.top).toFloat(),
        )
        if (local.width() <= 0f || local.height() <= 0f) return null

        val cx = local.centerX()
        val cy = local.centerY()
        val holeRadius = maxOf(local.width(), local.height()) / 2f

        val clipRisk = cx - outerRadius < 0f || cy - outerRadius < 0f ||
            cx + outerRadius > view.width.toFloat() || cy + outerRadius > view.height.toFloat()

        return Hole(cx, cy, holeRadius, clipRisk)
    }

    /** 取当前方向上真正承载摄像头的那个挖孔矩形（顶部/侧置通用）。 */
    private fun pickCurrentCutout(rects: List<Rect>, view: View): Rect? {
        if (rects.isEmpty()) return null
        val candidates = rects.filter { !it.isEmpty }
        if (candidates.isEmpty()) return null

        // 到 View 四条边的最小距离越小越可能是当前挖孔；
        // 同为边缘挖孔时取面积更大的（摄像头孔 vs 长条）。
        return candidates.minWithOrNull(compareBy<Rect>(
            { rect ->
                minOf(
                    rect.top - view.top,
                    rect.left - view.left,
                    (view.right - rect.right),
                    (view.bottom - rect.bottom),
                ).coerceAtLeast(0)
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
}
