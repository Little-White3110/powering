package com.powerring.hole.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.powerring.hole.R
import com.powerring.hole.ring.RingConfig
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 模块配置页：全部使用 miuix（HyperOS）Compose 组件。
 * 修改即时写入，Hook 侧通过文件变更检测热加载，一般无需重启系统界面。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                SettingsScreen()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun SettingsScreen() {
        var config by remember { mutableStateOf(PrefsStore.load(this@SettingsActivity)) }

        fun update(newConfig: RingConfig) {
            config = newConfig
        }

        Scaffold(
            topBar = {
                SmallTopAppBar(title = getString(R.string.app_name))
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                SwitchPreference(
                    checked = config.ringEnabled,
                    onCheckedChange = { enabled ->
                        PrefsStore.setBoolean(this@SettingsActivity, RingConfig.KEY_RING_ENABLED, enabled)
                        update(config.copy(ringEnabled = enabled))
                    },
                    title = "挖孔环形电量",
                    summary = "在摄像头挖孔外圈以圆环显示剩余电量",
                )
                SwitchPreference(
                    checked = config.hideBattery,
                    onCheckedChange = { enabled ->
                        PrefsStore.setBoolean(this@SettingsActivity, RingConfig.KEY_HIDE_BATTERY, enabled)
                        update(config.copy(hideBattery = enabled))
                    },
                    title = "隐藏状态栏电池图标",
                    summary = "环形电量可用时隐藏原图标；环不可用时自动恢复，避免电量指示丢失",
                    enabled = config.ringEnabled,
                )
                SwitchPreference(
                    checked = config.levelAnim,
                    onCheckedChange = { enabled ->
                        PrefsStore.setBoolean(this@SettingsActivity, RingConfig.KEY_LEVEL_ANIM, enabled)
                        update(config.copy(levelAnim = enabled))
                    },
                    title = "电量变化动画",
                    summary = "电量增减时弧度平滑过渡",
                )
                SwitchPreference(
                    checked = config.chargingGlow,
                    onCheckedChange = { enabled ->
                        PrefsStore.setBoolean(this@SettingsActivity, RingConfig.KEY_CHARGING_GLOW, enabled)
                        update(config.copy(chargingGlow = enabled))
                    },
                    title = "充电高亮辉光",
                    summary = "充电时圆环显示品牌蓝与外扩光效",
                )

                Text(
                    text = "说明：本模块作用域为「系统界面」，请在 LSPosed 中勾选启用。" +
                        "息屏/AOD 期间自动隐藏圆环以防烧屏；低电量红色、省电模式琥珀色、充电蓝色。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
    }
}
