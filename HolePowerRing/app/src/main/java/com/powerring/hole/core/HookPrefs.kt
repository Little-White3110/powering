package com.powerring.hole.core

import com.powerring.hole.ring.RingConfig
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.File

/**
 * 在 SystemUI 进程中读取本模块配置页写出的 SharedPreferences XML。
 *
 * 模块配置页会把 prefs 文件设置为全局可读；LSPosed 注入下以 system 身份运行，
 * 可直接读取。读不到时一律回退默认配置，保证功能安全降级。
 *
 * 通过文件 lastModified 做轻量变更检测，避免每次绘制都解析 XML。
 */
object HookPrefs {

    private const val MODULE_PKG = "com.powerring.hole"

    private val VALUE_TAGS = setOf("string", "boolean", "int", "float", "long")

    private val prefsFile: File by lazy {
        File("/data/data/$MODULE_PKG/shared_prefs/${RingConfig.PREFS_NAME}.xml")
    }

    @Volatile
    private var cached: RingConfig = RingConfig.DEFAULT

    @Volatile
    private var cachedMtime: Long = -1L

    /** 返回当前配置；文件变化时重新解析，异常时回退默认值。 */
    fun get(): RingConfig {
        val f = prefsFile
        if (!f.exists()) return RingConfig.DEFAULT
        return try {
            val mtime = f.lastModified()
            if (mtime != cachedMtime) {
                cached = parse(f)
                cachedMtime = mtime
                ModuleLog.i("配置已重新加载: $cached")
            }
            cached
        } catch (t: Throwable) {
            ModuleLog.e("读取模块配置失败，使用默认值", t)
            RingConfig.DEFAULT
        }
    }

    /** 强制下次重新读取（供调试）。 */
    fun invalidate() {
        cachedMtime = -1L
    }

    private fun parse(file: File): RingConfig {
        val values = HashMap<String, String>()
        file.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name
                if (event == XmlPullParser.START_TAG && tag in VALUE_TAGS) {
                    val key = parser.getAttributeValue(null, "name")
                    if (key != null) {
                        // SharedPreferences XML 中除 <string> 外，boolean/int/float/long
                        // 的值全部在 value 属性里；string 的值是文本节点
                        values[key] = if (tag == "string") {
                            parser.nextText()
                        } else {
                            parser.getAttributeValue(null, "value")
                        }
                    }
                }
                event = parser.next()
            }
        }

        fun bool(key: String, default: Boolean) =
            values[key]?.let { it == "true" } ?: default

        fun flt(key: String, default: Float) =
            values[key]?.toFloatOrNull() ?: default

        fun int(key: String, default: Int) =
            values[key]?.toIntOrNull() ?: default

        return RingConfig(
            ringEnabled = bool(RingConfig.KEY_RING_ENABLED, true),
            hideBattery = bool(RingConfig.KEY_HIDE_BATTERY, true),
            levelAnim = bool(RingConfig.KEY_LEVEL_ANIM, true),
            chargingGlow = bool(RingConfig.KEY_CHARGING_GLOW, true),
            strokeWidthDp = flt(RingConfig.KEY_STROKE_WIDTH, 2.5f),
            offsetXDp = flt(RingConfig.KEY_OFFSET_X, 0f),
            offsetYDp = flt(RingConfig.KEY_OFFSET_Y, 0f),
            scale = flt(RingConfig.KEY_SCALE, 1f),
            useCustomColor = bool(RingConfig.KEY_USE_CUSTOM_COLOR, false),
            customColor = int(RingConfig.KEY_CUSTOM_COLOR, 0xFF277AF7.toInt()),
        )
    }
}
