package com.powerring.hole.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 模块配置页入口：全部使用 miuix（HyperOS）Compose 组件。
 * 修改即时写入并通过广播通知 SystemUI 侧热加载，无需重启系统界面。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // pagerState 提升到主题容器之外：整棵 UI 树重建组合时 Tab 位置不丢
            val pagerState = rememberPagerState(initialPage = 0) { 3 }
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            // miuix 不碰系统栏：浅色主题下手动把状态栏图标压暗，否则恒为白色
            val darkTheme = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = !darkTheme
            }
            MiuixTheme(controller = controller) {
                SettingsScreen(pagerState)
            }
        }
    }
}
