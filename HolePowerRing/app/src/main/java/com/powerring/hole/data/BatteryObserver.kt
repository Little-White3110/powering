package com.powerring.hole.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.powerring.hole.core.HookPrefs
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState

/**
 * 电池/省电/屏幕状态数据源。
 *
 * 不依赖 MIUI 混淆类，直接使用系统广播：
 * - ACTION_BATTERY_CHANGED（sticky，注册即立刻收到当前值）
 * - ACTION_POWER_SAVE_MODE_CHANGED
 * - ACTION_SCREEN_ON / ACTION_SCREEN_OFF（息屏/AOD 不画环，防烧屏且省电）
 */
object BatteryObserver {

    /** 配置页保存后发出的显式广播，通知 SystemUI 侧刷新环 */
    const val ACTION_CONFIG_CHANGED = "com.powerring.hole.CONFIG_CHANGED"

    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext

        // 先读 sticky 立即初始化电量
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { intent ->
            handleBattery(app, intent)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> handleBattery(ctx, intent)
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                        RingState.updateBattery(
                            RingState.level, RingState.charging,
                            ctx.powerManager().isPowerSaveMode,
                        )
                    Intent.ACTION_SCREEN_ON -> {
                        RingState.setScreenOn(true)
                        // 冷启动后若还没读到过配置，屏幕点亮（说明用户在用机）就补读一次
                        if (HookPrefs.needsRetry()) {
                            ModuleLog.i("屏幕点亮，补读一次配置")
                            HookPrefs.invalidate()
                        }
                    }
                    Intent.ACTION_SCREEN_OFF -> RingState.setScreenOn(false)
                    Intent.ACTION_CONFIGURATION_CHANGED -> {
                        // 屏幕旋转后系统不会再调 setIsHideBattery，必须自己重评估
                        RingState.notifyOrientationChanged()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        // 冷启动时 SystemUI 先于解锁起来，此刻配置（凭据加密存储）
                        // 还读不到，模块会退回默认值；解锁后补读一次，
                        // 避免"重启后必须手动开关一次设置才生效"。
                        if (HookPrefs.needsRetry()) {
                            ModuleLog.i("用户已解锁，补读一次配置")
                            HookPrefs.invalidate()
                        }
                    }
                    ACTION_CONFIG_CHANGED -> {
                        HookPrefs.invalidate()
                        RingState.invalidateAll()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_CONFIG_CHANGED)
        }
        // 接收来自本模块配置页（另一应用进程/包）的显式广播，需要 EXPORTED
        app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        RingState.setScreenOn(app.powerManager().isInteractive)
        ModuleLog.i("电池状态监听已注册")
    }

    private fun handleBattery(context: Context, intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

        val percent = when {
            level >= 0 && scale > 0 -> (level * 100 / scale)
            level in 0..100 -> level // 部分设备直接给百分比
            else -> RingState.level
        }
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0
        val powerSave = context.powerManager().isPowerSaveMode

        RingState.updateBattery(percent, charging, powerSave)
    }

    private fun Context.powerManager(): PowerManager =
        getSystemService(Context.POWER_SERVICE) as PowerManager
}
