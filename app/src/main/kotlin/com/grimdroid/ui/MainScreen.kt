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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/** 底部导航的三个目的地。 */
private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Security("security", "安全模式", Icons.Default.Shield),
    Scan("scan", "病毒扫描", Icons.Default.Search),
    Settings("settings", "设置", Icons.Default.Settings),
}

/**
 * 应用主界面：底部 [NavigationBar] + [NavHost]。
 *
 * Stellar 状态与原生库调用结果由 [androidx.activity.ComponentActivity] 持有，
 * 这里只负责把它们转发到需要展示的页面（设置页的“关于”区域）。
 */
@Composable
fun MainScreen(
    stellarStatus: String,
    cppGreeting: String,
    cppSum: String,
    cppReversed: String,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val selected =
                        currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                navController.navigate(destination.route) {
                                    // 避免在返回栈中堆积大量目的地
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        // 图标为装饰性：label 已提供可访问名称，避免重复播报
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Security.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.Security.route) {
                SecurityModeScreen()
            }
            composable(Destination.Scan.route) {
                ScanScreen()
            }
            composable(Destination.Settings.route) {
                SettingsScreen(
                    stellarStatus = stellarStatus,
                    cppGreeting = cppGreeting,
                    cppSum = cppSum,
                    cppReversed = cppReversed,
                )
            }
        }
    }
}
