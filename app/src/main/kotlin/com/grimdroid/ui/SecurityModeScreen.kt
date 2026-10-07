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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** 单个安全模式的展示数据。 */
private data class SecurityMode(
    val title: String,
    val subtitle: String,
    val description: String,
    val icon: ImageVector,
)

private val SecurityModes = listOf(
    SecurityMode(
        title = "强力模式",
        subtitle = "全面防护，性能消耗较高",
        description = "强力模式会实时分析所有应用行为并拦截可疑操作，防护最全面，但对性能和电量消耗较高。",
        icon = Icons.Default.Bolt,
    ),
    SecurityMode(
        title = "日常保护",
        subtitle = "平衡性能与防护",
        description = "日常保护在性能与防护之间取得平衡，适合大多数使用场景，推荐日常使用。",
        icon = Icons.Default.Shield,
    ),
    SecurityMode(
        title = "病毒测试",
        subtitle = "仅检测不拦截",
        description = "病毒测试只报告检测到的威胁，不会主动拦截或清除，供你自行判断与排查。",
        icon = Icons.Default.BugReport,
    ),
)

private const val DefaultSelectedIndex = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityModeScreen(modifier: Modifier = Modifier) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(DefaultSelectedIndex) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("安全模式") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                // 让内部的可选项作为一组单选语义暴露给无障碍服务
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SecurityModes.forEachIndexed { index, mode ->
                SecurityModeCard(
                    mode = mode,
                    selected = index == selectedIndex,
                    onSelect = { selectedIndex = index },
                )
            }

            Text(
                text = SecurityModes[selectedIndex].description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SecurityModeCard(
    mode: SecurityMode,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton,
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = mode.icon,
                // 装饰性图标：标题文本已表达含义，避免重复播报
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mode.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                )
                Text(
                    text = mode.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }
            // 整卡负责点击，RadioButton 仅作状态指示，不再单独接收点击
            RadioButton(selected = selected, onClick = null)
        }
    }
}
