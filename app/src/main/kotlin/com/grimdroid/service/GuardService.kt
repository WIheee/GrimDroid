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
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.grimdroid.R

/**
 * 常驻前台防护服务。
 *
 * 以 [START_STICKY] 运行，被系统回收后会尝试自动重建；同时通过
 * [nativeStartGuard] 启动一个 Native 守护进程，在 App 进程被杀死时
 * 负责重新拉起本服务。
 */
class GuardService : Service() {

    /** 启动 Native 守护进程；参数为要拉起的组件名。 */
    private external fun nativeStartGuard(componentName: String)

    /** 确保同一进程生命周期内只 fork 一次守护进程，避免重复堆积。 */
    private var guardStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!guardStarted) {
            guardStarted = true
            nativeStartGuard("$packageName/${GuardService::class.java.name}")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GrimDroid 防护中")
            .setContentText("已拦截 $interceptCount 项威胁")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    companion object {
        private const val CHANNEL_ID = "grimdroid_guard"
        private const val NOTIFICATION_ID = 1001

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
