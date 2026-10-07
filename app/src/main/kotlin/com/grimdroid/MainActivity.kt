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

package com.grimdroid

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.grimdroid.ui.theme.ComposeEmptyActivityTheme
import roro.stellar.Stellar

class MainActivity : ComponentActivity() {

    // Stellar 状态，用于 Compose 显示
    private var stellarStatus by mutableStateOf("Stellar 未连接")

    // 服务连接监听
    private val binderReceivedListener = Stellar.OnBinderReceivedListener {
        Log.i("GrimDroid", "Stellar 服务已连接")
        stellarStatus = "Stellar 已连接"
        // 服务已连接，请求权限（传入权限名 + requestCode）
        if (Stellar.pingBinder()) {
            Stellar.requestPermission("stellar", REQUEST_CODE)
        }
    }

    // 服务断开监听
    private val binderDeadListener = Stellar.OnBinderDeadListener {
        Log.w("GrimDroid", "Stellar 服务已断开")
        stellarStatus = "Stellar 已断开"
    }

    // 权限请求结果监听
    private val permissionResultListener =
        Stellar.OnRequestPermissionResultListener { requestCode, allowed, onetime ->
            if (requestCode == REQUEST_CODE) {
                if (allowed) {
                    Log.i("GrimDroid", "Stellar 权限已授予")
                    stellarStatus = "权限已授予"
                } else {
                    Log.w("GrimDroid", "Stellar 权限被拒绝")
                    stellarStatus = "权限被拒绝"
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 注册 Stellar 监听器（Sticky 版本：若服务已连接则立即回调）
        Stellar.addBinderReceivedListenerSticky(binderReceivedListener)
        Stellar.addBinderDeadListener(binderDeadListener)
        Stellar.addRequestPermissionResultListener(permissionResultListener)

        enableEdgeToEdge()
        setContent {
            ComposeEmptyActivityTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        stellarStatus = stellarStatus,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销监听器，避免内存泄漏
        Stellar.removeBinderReceivedListener(binderReceivedListener)
        Stellar.removeBinderDeadListener(binderDeadListener)
        Stellar.removeRequestPermissionResultListener(permissionResultListener)
    }

    companion object {
        private const val REQUEST_CODE = 1001
    }
}

@Composable
fun Greeting(name: String, stellarStatus: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Hello $name!")
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Stellar 状态: $stellarStatus")
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ComposeEmptyActivityTheme {
        Greeting(name = "Android", stellarStatus = "Stellar 已连接")
    }
}