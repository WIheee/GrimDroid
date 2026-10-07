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

    // ===== 声明 C++ 函数 =====
    external fun helloFromCpp(): String
    external fun addFromCpp(a: Int, b: Int): Int
    external fun reverseFromCpp(input: String): String

    // Stellar 状态
    private var stellarStatus by mutableStateOf("Stellar 未连接")

    // C++ 调用结果
    private var cppGreeting by mutableStateOf("(未加载)")
    private var cppSum by mutableStateOf("(未计算)")
    private var cppReversed by mutableStateOf("(未反转)")

    // Stellar 服务连接监听
    private val binderReceivedListener = Stellar.OnBinderReceivedListener {
        Log.i("GrimDroid", "Stellar 服务已连接")
        stellarStatus = "Stellar 已连接"
        if (Stellar.pingBinder()) {
            Stellar.requestPermission("stellar", REQUEST_CODE)
        }
    }

    private val binderDeadListener = Stellar.OnBinderDeadListener {
        Log.w("GrimDroid", "Stellar 服务已断开")
        stellarStatus = "Stellar 已断开"
    }

    private val permissionResultListener =
        Stellar.OnRequestPermissionResultListener { requestCode, allowed, _ ->
            if (requestCode == REQUEST_CODE) {
                stellarStatus = if (allowed) "权限已授予" else "权限被拒绝"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ===== 调用 C++ =====
        try {
            cppGreeting = helloFromCpp()
            cppSum = "${addFromCpp(3, 4)}"
            cppReversed = reverseFromCpp("GrimDroid")
            Log.i("GrimDroid", "C++ ok: $cppGreeting / $cppSum / $cppReversed")
        } catch (t: Throwable) {
            Log.e("GrimDroid", "C++ 调用失败", t)
            cppGreeting = "调用失败: ${t.message}"
        }

        // ===== Stellar 监听注册 =====
        Stellar.addBinderReceivedListenerSticky(binderReceivedListener)
        Stellar.addBinderDeadListener(binderDeadListener)
        Stellar.addRequestPermissionResultListener(permissionResultListener)

        enableEdgeToEdge()
        setContent {
            ComposeEmptyActivityTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        stellarStatus = stellarStatus,
                        cppGreeting = cppGreeting,
                        cppSum = cppSum,
                        cppReversed = cppReversed,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Stellar.removeBinderReceivedListener(binderReceivedListener)
        Stellar.removeBinderDeadListener(binderDeadListener)
        Stellar.removeRequestPermissionResultListener(permissionResultListener)
    }

    companion object {
        private const val REQUEST_CODE = 1001

        init {
            System.loadLibrary("grimdroid")
        }
    }
}

@Composable
fun MainScreen(
    stellarStatus: String,
    cppGreeting: String,
    cppSum: String,
    cppReversed: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "GrimDroid")
        Spacer(modifier = Modifier.height(24.dp))

        Text(text = "Stellar: $stellarStatus")
        Spacer(modifier = Modifier.height(12.dp))

        Text(text = "C++ 问候: $cppGreeting")
        Spacer(modifier = Modifier.height(8.dp))

        Text(text = "C++ 加法 3+4 = $cppSum")
        Spacer(modifier = Modifier.height(8.dp))

        Text(text = "C++ 反转 GrimDroid = $cppReversed")
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    ComposeEmptyActivityTheme {
        MainScreen(
            stellarStatus = "Stellar 已连接",
            cppGreeting = "Hello from C++!",
            cppSum = "7",
            cppReversed = "diordmirG"
        )
    }
}
