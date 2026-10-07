/*
 * This file is part of GrimDroid (https://github.com/WIheee/GrimDroid).
 *
 * Copyright (c) 2026 WIhee
 *
 * GrimDroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GrimDroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GrimDroid. If not, see <https://www.gnu.org/licenses/>.
 */

package com.grimdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.grimdroid.R
import com.grimdroid.data.SettingsRepository
import com.grimdroid.receiver.EmergencyReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常驻前台防护服务。
 *
 * 以 [START_STICKY] 运行，被系统回收后会尝试自动重建；同时通过
 * [nativeStartGuard] 启动一个 Native 守护进程，在 App 进程被杀死时
 * 负责重新拉起本服务。
 */
class GuardService : Service() {

    /** 启动 Native 双哨兵守护进程；参数为要拉起的组件名与 files 目录（写 pid 用）。 */
    private external fun nativeStartGuard(componentName: String, filesDir: String)

    /** 确保同一进程生命周期内只 fork 一次守护进程，避免重复堆积。 */
    private var guardStarted = false

    /** 系统变化轮询所用的协程作用域。 */
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 充电触发清场：插上电源时按开关决定是否清场。 */
    private val chargeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_POWER_CONNECTED) {
                return
            }
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val enabled = SettingsRepository.chargeTriggerEnabled(applicationContext).first()
                    if (enabled) {
                        EmergencyCleanup.trigger(applicationContext)
                    }
                } catch (t: Throwable) {
                    Log.w("GrimDroid", "充电触发处理失败", t)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true
        startSystemMonitor()
        observeStrongMode()
        registerChargeReceiver()
    }

    private fun registerChargeReceiver() {
        ContextCompat.registerReceiver(
            this,
            chargeReceiver,
            IntentFilter(Intent.ACTION_POWER_CONNECTED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** 跟随强力模式开关启停各检测器。 */
    private fun observeStrongMode() {
        monitorScope.launch {
            SettingsRepository.strongModeEnabled(applicationContext).collect { enabled ->
                if (enabled) {
                    startStrongMode()
                } else {
                    stopStrongMode()
                }
            }
        }
    }

    private fun startStrongMode() {
        OverlayDetector.start(applicationContext, monitorScope)
    }

    private fun stopStrongMode() {
        OverlayDetector.stop()
    }

    /** 每 [MONITOR_INTERVAL_MS] 轮询一次系统变化（无障碍/设备管理员/通知监听）。 */
    private fun startSystemMonitor() {
        monitorScope.launch {
            while (isActive) {
                try {
                    SystemMonitor.check(applicationContext)
                } catch (t: Throwable) {
                    Log.w("GrimDroid", "系统变化轮询失败", t)
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!guardStarted) {
            guardStarted = true
            nativeStartGuard("$packageName/${GuardService::class.java.name}", filesDir.absolutePath)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(chargeReceiver)
        } catch (_: Throwable) {
            // 未注册或已注销时忽略
        }
        monitorScope.cancel()
        isRunning = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "防护服务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示 GrimDroid 后台防护运行状态"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // 通知上的“紧急清场”按钮 → EmergencyReceiver
        val emergencyIntent = Intent(this, EmergencyReceiver::class.java).setAction(ACTION_EMERGENCY)
        val emergencyPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            emergencyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GrimDroid 防护中")
            .setContentText("已拦截 $interceptCount 项威胁")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "🚨 紧急清场", emergencyPendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "grimdroid_guard"
        private const val NOTIFICATION_ID = 1001

        /** 系统变化轮询间隔：30 秒。 */
        private const val MONITOR_INTERVAL_MS = 30_000L

        /** 通知“紧急清场”按钮的 action。 */
        private const val ACTION_EMERGENCY = "com.grimdroid.action.EMERGENCY_CLEANUP"

        /** 服务是否处于运行状态；供 [com.grimdroid.worker.GuardWorker] 判定存活。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 通知中展示的拦截计数，由业务逻辑更新。 */
        @Volatile
        var interceptCount: Int = 0

        init {
            System.loadLibrary("grimdroid")
        }

        /** 以兼容各版本的方式启动前台服务。 */
        fun start(context: Context) {
            val intent = Intent(context, GuardService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
