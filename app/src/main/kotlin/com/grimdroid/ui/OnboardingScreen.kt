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

package com.grimdroid.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.grimdroid.service.StellarShell
import kotlinx.coroutines.launch

// Stellar Manager 官方仓库地址（用于引导安装）。
private const val STELLAR_MANAGER_URL = "https://github.com/roro2239/Stellar"

private const val TOTAL_STEPS = 3

/** 单个批量授权项的状态。 */
private enum class TaskStatus { Pending, Working, Done, Failed, Unavailable }

/**
 * 新手引导。
 *
 * Step 0 说明（系统设置提醒）→ Step 1 欢迎 → Step 2 Stellar 授权（必过，可跳过）
 * → Step 3 批量授权。
 * [onFinish] 与 [onSkip] 都会把 onboarding_done 置为 true 并进入主界面；
 * 区别在于跳过时未获得 Stellar 授权，主界面会提示功能受限。
 */
@Composable
fun OnboardingScreen(
    stellarConnected: Boolean,
    stellarAuthorized: Boolean,
    stellarDenied: Boolean,
    onRequestStellar: () -> Unit,
    onFinish: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }

    // 授权成功后自动从 Step 2 进入 Step 3
    LaunchedEffect(stellarAuthorized) {
        if (stellarAuthorized && step == 2) {
            step = 3
        }
    }

    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Step 0 为前置说明页，不显示步骤指示器
            if (step >= 1) {
                StepIndicator(current = step, total = TOTAL_STEPS)
                Spacer(modifier = Modifier.height(32.dp))
            }

            when (step) {
                0 -> IntroStep(onContinue = { step = 1 })
                1 -> WelcomeStep(onNext = { step = 2 })
                2 -> StellarStep(
                    connected = stellarConnected,
                    authorized = stellarAuthorized,
                    denied = stellarDenied,
                    onRequestStellar = onRequestStellar,
                    onSkip = onSkip,
                )
                else -> BatchStep(
                    stellarAuthorized = stellarAuthorized,
                    onFinish = onFinish,
                )
            }
        }
    }
}

@Composable
private fun StepIndicator(current: Int, total: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics {
            contentDescription = "第 $current 步，共 $total 步"
        },
    ) {
        repeat(total) { index ->
            val active = index < current
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(if (active) 32.dp else 24.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
            )
        }
    }
}

@Composable
private fun IntroStep(onContinue: () -> Unit) {
    val context = LocalContext.current

    Text(
        text = "先说清楚",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "有几个权限 App 自己搞不定，需要你手动去系统设置里开：\n" +
            "1. 后台允许高耗电（不然会被系统冻住）\n" +
            "2. 自启动（开不开看你自己喜好）\n\n" +
            "这段只是提醒，你可以先去开好再回来。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(32.dp))
    Button(
        onClick = { openAppDetailsSettings(context) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text("去设置")
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = onContinue,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text("继续")
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Icon(
        imageVector = Icons.Default.Shield,
        contentDescription = "GrimDroid 图标",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(96.dp),
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = "欢迎使用 GrimDroid",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "GrimDroid 是一款开源免费的手机安全防护工具，帮助你检测威胁、管理权限并保持系统整洁。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(32.dp))
    Button(
        onClick = onNext,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text("下一步")
    }
}

@Composable
private fun StellarStep(
    connected: Boolean,
    authorized: Boolean,
    denied: Boolean,
    onRequestStellar: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current

    Icon(
        imageVector = Icons.Default.Shield,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(64.dp),
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "授权 Stellar",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "GrimDroid 需要 Stellar 才能执行系统级防护。授权后即可调用系统能力进行深度检测与拦截。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = when {
            authorized -> "已授权"
            denied -> "授权被拒绝"
            connected -> "Stellar 已连接，等待授权"
            else -> "Stellar 未连接，请先安装并激活 Stellar Manager"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (authorized) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )

    if (denied) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "授权失败，你可以重试；若尚未安装，请先安装 Stellar Manager。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = { openUrl(context, STELLAR_MANAGER_URL) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("安装 Stellar Manager")
        }
    }

    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onRequestStellar,
        enabled = !authorized,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(if (denied) "重试授权" else "授权")
    }

    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("跳过（功能受限）")
    }
}

@Composable
private fun BatchStep(
    stellarAuthorized: Boolean,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 通知权限：可直接申请
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationGranted = granted }

    // 需要 Stellar 的两项
    var batteryStatus by remember { mutableStateOf(TaskStatus.Pending) }
    var backgroundStatus by remember { mutableStateOf(TaskStatus.Pending) }

    Text(
        text = "批量授权",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "以下设置能让防护更稳定地运行。可用 Stellar 完成的项目会通过 Stellar 执行。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))

    AuthorizationRow(
        title = "通知权限",
        subtitle = "用于展示防护状态通知",
        status = if (notificationGranted) TaskStatus.Done else TaskStatus.Pending,
        actionLabel = "授权",
        enabled = !notificationGranted,
        onAction = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
    )

    AuthorizationRow(
        title = "电池优化白名单",
        subtitle = "避免系统在息屏后冻结防护",
        status = if (stellarAuthorized) batteryStatus else TaskStatus.Unavailable,
        actionLabel = "开启",
        enabled = stellarAuthorized && batteryStatus != TaskStatus.Working,
        onAction = {
            batteryStatus = TaskStatus.Working
            scope.launch {
                val result = StellarShell.run(
                    "dumpsys deviceidle whitelist +${context.packageName}",
                )
                batteryStatus = if (result.success) TaskStatus.Done else TaskStatus.Failed
            }
        },
    )

    AuthorizationRow(
        title = "自启动 / 后台限制",
        subtitle = "尽力解除后台限制以提高存活率",
        status = if (stellarAuthorized) backgroundStatus else TaskStatus.Unavailable,
        actionLabel = "尝试",
        enabled = stellarAuthorized && backgroundStatus != TaskStatus.Working,
        onAction = {
            backgroundStatus = TaskStatus.Working
            scope.launch {
                val result = StellarShell.run(
                    "cmd appops set ${context.packageName} RUN_IN_BACKGROUND allow",
                )
                backgroundStatus = if (result.success) TaskStatus.Done else TaskStatus.Failed
            }
        },
    )

    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onFinish,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text("完成")
    }
}

@Composable
private fun AuthorizationRow(
    title: String,
    subtitle: String,
    status: TaskStatus,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            when (status) {
                TaskStatus.Done -> Text(
                    text = "已完成",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { contentDescription = "$title，已完成" },
                )

                TaskStatus.Working -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                )

                TaskStatus.Failed -> Text(
                    text = "失败",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { contentDescription = "$title，执行失败" },
                )

                TaskStatus.Unavailable -> Text(
                    text = "需要 Stellar",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TaskStatus.Pending -> Button(
                    onClick = onAction,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(actionLabel)
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    )
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Throwable) {
        // 没有可处理该链接的应用时静默忽略
    }
}

private fun openAppDetailsSettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Throwable) {
        // 无法跳转时静默忽略
    }
}
