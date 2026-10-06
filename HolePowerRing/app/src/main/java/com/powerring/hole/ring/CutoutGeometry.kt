package com.powerring.hole.ring

import android.content.Context
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import com.powerring.hole.core.ModuleLog
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap

/**
 * 挖孔几何解析（多源链 + 缓存）。
 *
 * 取数源（按优先级，先成功先用）：
 * 1. 配置手动孔径（[RingConfig.holeDiameterDp] > 0）：自动定位拿不到 insets 的机型兜底；
 * 2. 公开 API：View.getRootWindowInsets().getDisplayCutout().getBoundingRects()
 *    （矩形坐标为**屏幕（display）坐标**）；
 * 3. WindowManager 度量：currentWindowMetrics.windowInsets（minSdk 34 恒可用），
 *    个别窗口的 rootWindowInsets 不带 cutout，度量接口反而有；
 * 4. 反射兜底：DisplayCutoutBaseView 的 displayInfo.logicalDisplayCutout。
 *
 * 屏幕坐标 → View 局部坐标的换算与启发式判定全部在 [CutoutMath]（纯函数，JVM 可测）。
 *
 * 性能：resolve 每帧被绘制与点击命中调用，因此**只用廉价输入**（视图/屏幕尺寸、
 * 屏上位置、手动孔径）组缓存键，命中直接复用上次解析成功的 HoleCore。缓存键
 * **不含 rects**：若键含 rects，反射源每帧都得先反射拿 rects 才能算键，缓存形同
 * 虚设——而 rects 只会随视图/屏幕尺寸、位置变化（旋转、分屏），这些都已在键里。
 * **失败不缓存**：insets 晚到是常态，失败每帧重走多源链，等系统把 cutout 派发进来。
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

    /** 解析成功的缓存条目：键 + 核心几何 + 来源名（来源名仅诊断用）。 */
    private data class CacheEntry(val key: String, val core: HoleCore, val source: String)

    @Volatile
    private var cache: CacheEntry? = null

    fun resolve(view: View, outerRadius: Float, config: RingConfig): Hole? {
        val viewW = view.width
        val viewH = view.height
        if (viewW <= 0 || viewH <= 0) return null

        // ---- 缓存键（廉价输入，全部本地字段读取，无反射无 binder）----
        val dm = view.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val loc = IntArray(2)
        try {
            view.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            ModuleLog.e("getLocationOnScreen 失败，按 (0,0) 处理", t)
        }
        val manualDp = config.holeDiameterDp
        val key = "$viewW|$viewH|$screenW|$screenH|${loc[0]}|${loc[1]}|$manualDp"

        cache?.let { hit ->
            if (hit.key == key) {
                return withClipRisk(hit.core, outerRadius, viewW, viewH)
            }
        }

        // ---- 缓存未命中：跑多源链 ----
        val scale = run {
            // cutout 矩形是物理像素，视图坐标空间可能被缩放；1:1 机型恒为 1。
            // 防御非有限值/<=0：displayMetrics 异常时宁可按原样用，也不除出 NaN。
            val s = if (screenW > 0) viewW / screenW.toFloat() else 1f
            if (!s.isFinite() || s <= 0f) 1f else s
        }
        val viewWf = viewW.toFloat()
        val viewHf = viewH.toFloat()

        // 源 1：手动孔径（优先级最高，命中即不走自动链）
        if (manualDp > 0f) {
            val core = CutoutMath.manualHole(viewWf, manualDp * dm.density)
            return success(
                key, core, "manual", null, null, scale, fellBack = false,
                outerRadius = outerRadius, viewW = viewW, viewH = viewH,
            )
        }

        // 源 2–4：自动链。每个源拿到 rects 后走同一条纯函数管线，
        // 任一步拿不到就落到下一源，全部失败才判「本轮解析不了」。
        val chain = listOf(
            "rootWindowInsets" to { getFromRootWindowInsets(view) },
            "windowMetrics" to { getFromWindowMetrics(view) },
            "displayInfo" to { getFromDisplayInfoReflection(view) },
        )
        val statuses = ArrayList<String>(chain.size)
        for ((name, source) in chain) {
            val rects = try {
                source()
            } catch (t: Throwable) {
                ModuleLog.e("挖孔源 $name 抛异常，尝试下一源", t)
                statuses.add("$name=error")
                continue
            }
            if (rects.isNullOrEmpty()) {
                statuses.add("$name=empty")
                continue
            }
            val rectI = rects.map { RectI(it.left, it.top, it.right, it.bottom) }
            val picked = CutoutMath.pickCutoutRect(rectI, screenW, screenH)
            if (picked == null) {
                statuses.add("$name=noCandidate")
                continue
            }
            val local =
                CutoutMath.toViewLocal(picked, loc[0], loc[1], scale, viewWf, viewHf)
            if (local == null) {
                statuses.add("$name=noLocal")
                continue
            }
            return success(
                key, CutoutMath.holeFromLocal(local), name,
                picked, local, scale, local.fellBack,
                outerRadius = outerRadius, viewW = viewW, viewH = viewH,
            )
        }

        logOnce(view, statuses)
        return null
    }

    /** 记缓存并产出 Hole。clipRisk 依赖调用方的 outerRadius（含 stroke/gap，会变），必须每次现算。 */
    private fun success(
        key: String,
        core: HoleCore,
        source: String,
        picked: RectI?,
        local: LocalRect?,
        scale: Float,
        fellBack: Boolean,
        outerRadius: Float,
        viewW: Int,
        viewH: Int,
    ): Hole {
        cache = CacheEntry(key, core, source)
        ModuleLog.i(
            "挖孔几何: source=$source rect=$picked local=$local scale=$scale " +
                "cx=${core.cx} cy=${core.cy} r=${core.holeRadius} fellBack=$fellBack",
        )
        return withClipRisk(core, outerRadius, viewW, viewH)
    }

    /** 用当前 outerRadius 重算裁切风险（渲染端每帧的 stroke/gap 与 expand 都可能变）。 */
    private fun withClipRisk(core: HoleCore, outerRadius: Float, viewW: Int, viewH: Int): Hole {
        val clipRisk = core.cx - outerRadius < 0f || core.cy - outerRadius < 0f ||
            core.cx + outerRadius > viewW.toFloat() || core.cy + outerRadius > viewH.toFloat()
        return Hole(core.cx, core.cy, core.holeRadius, clipRisk)
    }

    // ---- 取数源 ----

    @Suppress("DEPRECATION")
    private fun getFromRootWindowInsets(view: View): List<Rect>? {
        return try {
            val insets: WindowInsets? = view.rootWindowInsets
            insets?.displayCutout?.boundingRects
        } catch (t: Throwable) {
            ModuleLog.e("rootWindowInsets 取挖孔失败", t)
            null
        }
    }

    /** minSdk 34 起恒可用；某些窗口的 rootWindowInsets 不带 cutout，度量接口反而有。 */
    @Suppress("DEPRECATION")
    private fun getFromWindowMetrics(view: View): List<Rect>? {
        return try {
            val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val cutout = wm?.currentWindowMetrics?.windowInsets?.displayCutout
            cutout?.boundingRects
        } catch (t: Throwable) {
            ModuleLog.e("WindowManager 度量取挖孔失败", t)
            null
        }
    }

    private fun getFromDisplayInfoReflection(view: View): List<Rect>? {
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

    // ---- 一次性诊断日志（失败路径：失败不缓存、每帧重试，日志必须按 View 限流） ----
    private const val MAX_LOGS = 5
    private val loggedViews: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private var logCount = 0

    private fun logOnce(view: View, sourceStatuses: List<String>) {
        if (logCount >= MAX_LOGS) return
        synchronized(loggedViews) {
            if (!loggedViews.add(view)) return
            logCount++
        }
        val loc = IntArray(2)
        runCatching { view.getLocationOnScreen(loc) }
        @Suppress("DEPRECATION")
        val insetsDesc = runCatching {
            view.rootWindowInsets?.displayCutout?.toString()?.take(300)
        }.getOrNull()
        ModuleLog.i(
            "挖孔解析失败: cls=${view.javaClass.name} size=${view.width}x${view.height} " +
                "locOnScreen=(${loc[0]},${loc[1]}) insetsCutout=${insetsDesc ?: "null"} " +
                "sources=${sourceStatuses.joinToString(",")}",
        )
    }
}
