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

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.grimdroid.data.SettingsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    stellarStatus: String,
    cppGreeting: String,
    cppSum: String,
    cppReversed: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 开机自启持久化在 DataStore 中，默认关闭
    val autoStart by SettingsRepository.autoStartOnBoot(context).collectAsState(initial = false)
    // 强力保护模式，默认关闭
    val strongMode by SettingsRepository.strongModeEnabled(context).collectAsState(initial = false)
    // 充电触发清场（一次性），默认关闭
    val chargeTrigger by SettingsRepository.chargeTriggerEnabled(context).collectAsState(initial = false)

    var backgroundMonitor by rememberSaveable { mutableStateOf(true) }
    var blockNotifications by rememberSaveable { mutableStateOf(true) }
    var aboutExpanded by rememberSaveable { mutableStateOf(false) }

    // 悬浮窗权限：返回设置页后重新检查一次
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { overlayGranted = Settings.canDrawOverlays(context) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SettingSwitchRow(
                label = "开机自启",
                checked = autoStart,
                onCheckedChange = { enabled ->
                    scope.launch { SettingsRepository.setAutoStartOnBoot(context, enabled) }
                },
            )
            SettingSwitchRow(
                label = "后台实时监控",
                checked = backgroundMonitor,
                onCheckedChange = { backgroundMonitor = it },
            )
            SettingSwitchRow(
                label = "拦截通知",
                checked = blockNotifications,
                onCheckedChange = { blockNotifications = it },
            )
            SettingSwitchRow(
                label = "强力保护模式",
                checked = strongMode,
                onCheckedChange = { enabled ->
                    scope.launch { SettingsRepository.setStrongModeEnabled(context, enabled) }
                },
            )
            SettingSwitchRow(
                label = "充电触发清场",
                checked = chargeTrigger,
                onCheckedChange = { enabled ->
                    scope.launch { SettingsRepository.setChargeTriggerEnabled(context, enabled) }
                },
            )

            ListItem(
                headlineContent = { Text("悬浮窗权限") },
                supportingContent = { Text(if (overlayGranted) "已授权" else "未授权") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable {
                        overlayPermissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                    .semantics {
                        stateDescription = if (overlayGranted) "已授权" else "未授权"
                    },
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            AboutSection(
                expanded = aboutExpanded,
                onToggle = { aboutExpanded = !aboutExpanded },
                stellarStatus = stellarStatus,
                cppGreeting = cppGreeting,
                cppSum = cppSum,
                cppReversed = cppReversed,
            )
        }
    }
}

/**
 * 整行可切换的开关项。
 *
 * 触控目标由 [toggleable] 覆盖整个 [ListItem]（高度 ≥ 56dp）；
 * [Switch] 自身不再接收点击，避免重复响应，并通过 stateDescription 暴露状态。
 */
@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val stateText = if (checked) "已开启" else "已关闭"

    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null)
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch,
            )
            .semantics { stateDescription = stateText },
    )
}

@Composable
private fun AboutSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    stellarStatus: String,
    cppGreeting: String,
    cppSum: String,
    cppReversed: String,
) {
    ListItem(
        headlineContent = { Text("关于 GrimDroid") },
        supportingContent = { Text("版本 1.0") },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Info,
                // 装饰性图标：标题文本已表达含义
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起详情" else "展开详情",
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onToggle)
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" },
    )

    if (expanded) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            AboutLine("版本号", "1.0")
            AboutLine("Stellar 服务", stellarStatus)
            AboutLine("原生库问候", cppGreeting)
            AboutLine("原生库加法 3+4", cppSum)
            AboutLine("原生库反转 GrimDroid", cppReversed)
        }
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Text(
        text = "$label：$value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}
