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
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.grimdroid.MainActivity
import com.grimdroid.R
import com.grimdroid.ui.AlertDialogView
import com.grimdroid.ui.theme.ComposeEmptyActivityTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 悬浮窗提醒服务（普通 Service，非前台服务）。
 *
 * 支持两种类型：
 *  - 普通提醒（title/description，20 秒自动移除）；
 *  - 强力模式提醒（可疑应用，冻结/卸载/忽略，30 秒无响应自动冻结）。
 *
 * 因 [ComposeView] 脱离 Activity 使用，需手动提供
 * LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner。
 */
class AlertOverlayService : Service() {

    /** ComposeView 需要的三合一 owner（Lifecycle / ViewModelStore / SavedStateRegistry）。 */
    private val owner = OverlayOwner()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null

    private var strongMode = false
    private var pendingPackage: String? = null

    private val autoDismiss = Runnable { onTimeout() }

    override fun onCreate() {
        super.onCreate()
        owner.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        strongMode = intent?.getStringExtra(EXTRA_MODE) == MODE_STRONG
        pendingPackage = null

        if (strongMode) {
            val packageName = intent?.getStringExtra(EXTRA_PACKAGE)
            val appName = intent?.getStringExtra(EXTRA_APP_NAME).orEmpty()
            if (packageName.isNullOrEmpty()) {
                stopSelf()
                return START_NOT_STICKY
            }
            pendingPackage = packageName
            showStrongOverlay(appName, packageName)
        } else {
            val title = intent?.getStringExtra(EXTRA_TITLE)
            val description = intent?.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
            if (title.isNullOrEmpty()) {
                stopSelf()
                return START_NOT_STICKY
            }
            showOverlay(title, description)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        owner.onDestroy()
        super.onDestroy()
    }

    // ===== 普通提醒 =====

    private fun showOverlay(title: String, description: String) {
        if (!ensureOverlayAllowed()) {
            return
        }
        if (overlayView != null) {
            stopSelf()
            return
        }

        val view = ComposeView(this).apply {
            setTreeOwners()
            setContent {
                ComposeEmptyActivityTheme {
                    AlertDialogView(
                        title = title,
                        description = description,
                        onDetails = {
                            openDetails(title, description)
                            removeOverlay()
                        },
                        onDismiss = { removeOverlay() },
                    )
                }
            }
        }
        addOverlay(view)
        mainHandler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
    }

    // ===== 强力模式提醒 =====

    private fun showStrongOverlay(appName: String, packageName: String) {
        if (!ensureOverlayAllowed()) {
            return
        }
        if (overlayView != null) {
            stopSelf()
            return
        }

        val view = ComposeView(this).apply {
            setTreeOwners()
            setContent {
                ComposeEmptyActivityTheme {
                    StrongAlertDialog(
                        appName = appName.ifEmpty { packageName },
                        packageName = packageName,
                        onFreeze = {
                            freeze(packageName)
                            removeOverlay()
                        },
                        onUninstall = {
                            uninstall(packageName)
                            removeOverlay()
                        },
                        onIgnore = {
                            SessionIgnoreList.add(packageName)
                            removeOverlay()
                        },
                    )
                }
            }
        }
        addOverlay(view)
        // 30 秒无响应 → 冻结该应用
        mainHandler.postDelayed(autoDismiss, STRONG_TIMEOUT_MS)
    }

    /** 超时：强力模式下自动冻结；其余情况直接移除。 */
    private fun onTimeout() {
        if (strongMode) {
            pendingPackage?.let { freeze(it) }
        }
        removeOverlay()
    }

    private fun freeze(packageName: String) {
        actionScope.launch { StellarShell.run("pm suspend $packageName") }
    }

    private fun uninstall(packageName: String) {
        actionScope.launch { StellarShell.run("pm uninstall $packageName") }
    }

    // ===== 公共 =====

    /** 检查悬浮窗权限；无权限则降级为通知并提示重新授权。 */
    private fun ensureOverlayAllowed(): Boolean {
        if (Settings.canDrawOverlays(this)) {
            return true
        }
        notifyOverlayRequired()
        stopSelf()
        return false
    }

    private fun notifyOverlayRequired() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(FALLBACK_CHANNEL_ID, "提醒降级", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "缺少悬浮窗权限时的降级提醒"
            },
        )
        val notification = NotificationCompat.Builder(this, FALLBACK_CHANNEL_ID)
            .setContentTitle("需要悬浮窗权限")
            .setContentText("检测到异常但无法弹出悬浮窗，请在设置中重新授权悬浮窗权限")
            .setStyle(NotificationCompat.BigTextStyle().bigText("检测到异常但无法弹出悬浮窗，请在设置中重新授权悬浮窗权限"))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(FALLBACK_NOTIFICATION_ID, notification)
        } catch (_: Throwable) {
            // 忽略
        }
    }

    private fun ComposeView.setTreeOwners() {
        setViewTreeLifecycleOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
    }

    private fun addOverlay(view: View) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = 48
        }
        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (t: Throwable) {
            stopSelf()
        }
    }

    private fun removeOverlay() {
        mainHandler.removeCallbacks(autoDismiss)
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
            // 可能已被系统移除
        }
        stopSelf()
    }

    private fun openDetails(title: String, description: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_DESCRIPTION, description)
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            // 无法启动时静默忽略
        }
    }

    private val windowManager: WindowManager
        get() = getSystemService(WINDOW_SERVICE) as WindowManager

    companion object {
        private const val EXTRA_MODE = "extra_mode"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_DESCRIPTION = "extra_description"
        private const val EXTRA_PACKAGE = "extra_package"
        private const val EXTRA_APP_NAME = "extra_app_name"

        private const val MODE_NORMAL = "normal"
        private const val MODE_STRONG = "strong"

        private const val AUTO_DISMISS_MS = 20_000L
        private const val STRONG_TIMEOUT_MS = 30_000L

        private const val FALLBACK_CHANNEL_ID = "grimdroid_overlay_fallback"
        private const val FALLBACK_NOTIFICATION_ID = 2001

        /** 冻结/卸载等动作不随服务停止而取消，故放在进程级作用域。 */
        private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 展示普通提醒。调用方应自行判断是否有悬浮窗权限。 */
        fun show(context: Context, alert: SecurityAlert) {
            val intent = Intent(context, AlertOverlayService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_NORMAL)
                putExtra(EXTRA_TITLE, alert.title)
                putExtra(EXTRA_DESCRIPTION, alert.description)
            }
            context.startService(intent)
        }

        /** 展示强力模式提醒（可疑应用：冻结 / 卸载 / 忽略）。 */
        fun showStrongAlert(context: Context, packageName: String) {
            val intent = Intent(context, AlertOverlayService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_STRONG)
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_APP_NAME, appLabel(context, packageName))
            }
            context.startService(intent)
        }
    }
}

/** 强力模式弹窗：可疑应用 + 冻结 / 卸载 / 忽略。 */
@Composable
private fun StrongAlertDialog(
    appName: String,
    packageName: String,
    onFreeze: () -> Unit,
    onUninstall: () -> Unit,
    onIgnore: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    // 装饰性图标：标题文本已表达含义
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "检测到可疑应用",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = appName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onIgnore, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("忽略")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onFreeze, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("冻结")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onUninstall, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("卸载")
                }
            }
        }
    }
}

/**
 * ComposeView 在 Service 中使用时需要的三合一 owner。
 *
 * 通过 `View.setViewTreeLifecycleOwner` 等 KTX 扩展设置到 View 上，
 * 使 Compose 能找到生命周期与状态保存所需的宿主。
 */
private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
