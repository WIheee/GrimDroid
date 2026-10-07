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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.grimdroid.data.SettingsRepository
import com.grimdroid.service.GuardService
import com.grimdroid.ui.MainScreen
import com.grimdroid.ui.OnboardingScreen
import com.grimdroid.ui.theme.ComposeEmptyActivityTheme
import com.grimdroid.worker.GuardWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import roro.stellar.Stellar

class MainActivity : ComponentActivity() {

    // ===== 声明 C++ 函数 =====
    external fun helloFromCpp(): String
    external fun addFromCpp(a: Int, b: Int): Int
    external fun reverseFromCpp(input: String): String

    // Stellar 状态
    private var stellarStatus by mutableStateOf("Stellar 未连接")
    private var stellarConnected by mutableStateOf(false)
    private var stellarDenied by mutableStateOf(false)

    // C++ 调用结果
    private var cppGreeting by mutableStateOf("(未加载)")
    private var cppSum by mutableStateOf("(未计算)")
    private var cppReversed by mutableStateOf("(未反转)")

    // Android 13+ 需要运行时申请通知权限，前台服务通知才能正常展示
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果不影响服务运行 */ }

    // Stellar 服务连接监听
    private val binderReceivedListener = Stellar.OnBinderReceivedListener {
        Log.i("GrimDroid", "Stellar 服务已连接")
        stellarStatus = "Stellar 已连接"
        stellarConnected = true
        if (Stellar.pingBinder()) {
            Stellar.requestPermission("stellar", REQUEST_CODE)
        }
    }

    private val binderDeadListener = Stellar.OnBinderDeadListener {
        Log.w("GrimDroid", "Stellar 服务已断开")
        stellarStatus = "Stellar 已断开"
        stellarConnected = false
    }

    private val permissionResultListener =
        Stellar.OnRequestPermissionResultListener { requestCode, allowed, _ ->
            if (requestCode == REQUEST_CODE) {
                stellarStatus = if (allowed) "权限已授予" else "权限被拒绝"
                stellarDenied = !allowed
                // 持久化授权状态，供主界面与引导页判断
                CoroutineScope(Dispatchers.IO).launch {
                    SettingsRepository.setStellarAuthorized(applicationContext, allowed)
                }
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

        // ===== 保活：启动前台防护服务，并注册周期看护 =====
        requestNotificationPermissionIfNeeded()
        GuardService.start(this)
        GuardWorker.schedule(this)

        enableEdgeToEdge()
        setContent {
            ComposeEmptyActivityTheme {
                val context = LocalContext.current
                // null 表示尚未从 DataStore 读到，避免引导页闪烁
                val onboardingDone by
                    SettingsRepository.onboardingDone(context).collectAsState(initial = null)
                val stellarAuthorized by
                    SettingsRepository.stellarAuthorized(context).collectAsState(initial = false)
                val scope = rememberCoroutineScope()

                when (onboardingDone) {
                    null -> Unit // 读取中，保持空白
                    false -> OnboardingScreen(
                        stellarConnected = stellarConnected,
                        stellarAuthorized = stellarAuthorized,
                        stellarDenied = stellarDenied,
                        onRequestStellar = { requestStellarPermission() },
                        onFinish = {
                            scope.launch { SettingsRepository.setOnboardingDone(context, true) }
                        },
                        onSkip = {
                            scope.launch { SettingsRepository.setOnboardingDone(context, true) }
                        },
                    )

                    else -> MainScreen(
                        stellarStatus = stellarStatus,
                        stellarAuthorized = stellarAuthorized,
                        onRequestStellar = { requestStellarPermission() },
                        cppGreeting = cppGreeting,
                        cppSum = cppSum,
                        cppReversed = cppReversed,
                    )
                }
            }
        }
    }

    private fun requestStellarPermission() {
        stellarDenied = false
        if (Stellar.pingBinder()) {
            Stellar.requestPermission("stellar", REQUEST_CODE)
        } else {
            // 未连接时视作失败，引导页会提示安装 Stellar Manager
            stellarDenied = true
            stellarStatus = "Stellar 未连接"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
