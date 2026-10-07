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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.grimdroid.R
import com.grimdroid.data.MonitorSnapshot
import com.grimdroid.data.MonitorStateRepository

/** 触发提醒的系统变化类型。 */
enum class AlertType { ACCESSIBILITY, DEVICE_ADMIN, NOTIFICATION_LISTENER, PACKAGE, PACKAGE_UPDATE }

/** 一条待展示的安全提醒。 */
data class SecurityAlert(
    val type: AlertType,
    val title: String,
    val description: String,
)

private const val ALERT_CHANNEL_ID = "grimdroid_alerts"

/**
 * 监控系统变化：无障碍服务、设备管理员、通知监听服务。
 *
 * 由 [GuardService] 每 30 秒调用一次 [check]，与上次快照对比，对新增项触发提醒。
 * 新应用安装/更新由 [com.grimdroid.receiver.PackageChangeReceiver] 处理。
 */
object SystemMonitor {

    suspend fun check(context: Context) {
        val appContext = context.applicationContext

        val current = MonitorSnapshot(
            accessibility = queryAccessibilityServices(appContext),
            deviceAdmins = queryDeviceAdmins(appContext),
            notificationListeners = queryNotificationListeners(appContext),
        )

        if (!MonitorStateRepository.isInitialized(appContext)) {
            // 首次运行只建立基线，不告警
            MonitorStateRepository.saveSnapshot(appContext, current)
            MonitorStateRepository.setInitialized(appContext, true)
            return
        }

        val previous = MonitorStateRepository.currentSnapshot(appContext)

        (current.accessibility - previous.accessibility).forEach { pkg ->
            dispatchAlert(appContext, accessibilityAlert(appContext, pkg))
        }
        (current.deviceAdmins - previous.deviceAdmins).forEach { pkg ->
            dispatchAlert(appContext, deviceAdminAlert(appContext, pkg))
        }
        (current.notificationListeners - previous.notificationListeners).forEach { pkg ->
            dispatchAlert(appContext, notificationListenerAlert(appContext, pkg))
        }

        MonitorStateRepository.saveSnapshot(appContext, current)
    }

    private fun accessibilityAlert(context: Context, packageName: String) = SecurityAlert(
        type = AlertType.ACCESSIBILITY,
        title = "检测到新的无障碍服务",
        description = "「${appLabel(context, packageName)}」获得了无障碍权限，可能读取屏幕内容或自动点击",
    )

    private fun deviceAdminAlert(context: Context, packageName: String) = SecurityAlert(
        type = AlertType.DEVICE_ADMIN,
        title = "检测到新的设备管理员",
        description = "「${appLabel(context, packageName)}」成为设备管理员，卸载前需要先取消",
    )

    private fun notificationListenerAlert(context: Context, packageName: String) = SecurityAlert(
        type = AlertType.NOTIFICATION_LISTENER,
        title = "检测到新的通知监听服务",
        description = "「${appLabel(context, packageName)}」可以读取所有通知内容，包括验证码",
    )

    private fun queryAccessibilityServices(context: Context): Set<String> =
        Settings.Secure
            .getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .parseComponentPackages()

    private fun queryNotificationListeners(context: Context): Set<String> =
        Settings.Secure
            .getString(context.contentResolver, "enabled_notification_listeners")
            .parseComponentPackages()

    private fun queryDeviceAdmins(context: Context): Set<String> {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return emptySet()
        return manager.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
    }
}

/** 形如 "pkg/.Service:pkg2/.S2" 的设置值，取其中的包名集合。 */
private fun String?.parseComponentPackages(): Set<String> =
    this?.split(':')
        ?.mapNotNull { ComponentName.unflattenFromString(it)?.packageName }
        ?.toSet()
        ?: emptySet()

/** 取应用显示名，取不到则退回包名。 */
internal fun appLabel(context: Context, packageName: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
} catch (_: Throwable) {
    packageName
}

/**
 * 展示一条提醒：有悬浮窗权限则弹悬浮窗，否则降级为高优先级通知。
 */
fun dispatchAlert(context: Context, alert: SecurityAlert) {
    if (Settings.canDrawOverlays(context)) {
        try {
            AlertOverlayService.show(context, alert)
            return
        } catch (t: Throwable) {
            // 启动悬浮窗服务失败（如后台限制），降级到通知
        }
    }
    showAlertNotification(context, alert)
}

private fun showAlertNotification(context: Context, alert: SecurityAlert) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(ALERT_CHANNEL_ID, "安全提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "系统变化的安全提醒"
        },
    )

    val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
        .setContentTitle(alert.title)
        .setContentText(alert.description)
        .setStyle(NotificationCompat.BigTextStyle().bigText(alert.description))
        .setSmallIcon(R.drawable.ic_notification)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setAutoCancel(true)
        .build()

    try {
        manager.notify(alert.description.hashCode(), notification)
    } catch (_: Throwable) {
        // 通知权限被拒等情况下静默忽略
    }
}
