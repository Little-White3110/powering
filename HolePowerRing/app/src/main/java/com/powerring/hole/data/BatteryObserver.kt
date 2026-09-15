package com.powerring.hole.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
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
                    Intent.ACTION_SCREEN_ON -> RingState.setScreenOn(true)
                    Intent.ACTION_SCREEN_OFF -> RingState.setScreenOn(false)
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        app.registerReceiver(receiver, filter)
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
