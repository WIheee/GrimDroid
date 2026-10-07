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

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections

/** 本次会话内被用户“忽略”的包名（内存态，重启即失效）。 */
object SessionIgnoreList {
    private val packages: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun add(packageName: String) {
        packages.add(packageName)
    }

    fun contains(packageName: String): Boolean = packages.contains(packageName)
}

/**
 * 桌面级占位病毒库。
 *
 * 真实实现应替换为签名/特征库；此处仅提供一个可命中的示例集合，
 * 用于跑通“命中 → 强力弹窗”的链路。
 */
object VirusDatabase {
    val suspiciousPackages: Set<String> = setOf(
        "com.example.test.malware",
        "com.example.ransomware.demo",
    )
}

/**
 * 悬浮窗（前台应用）检测：每 5 秒读取当前获得焦点的窗口包名，命中病毒库则触发强力弹窗。
 * 仅在强力模式开启时运行。
 */
object OverlayDetector {

    private const val INTERVAL_MS = 5_000L

    // 当前前台窗口包名，形如 "  mCurrentFocus=Window{1234 u0 com.foo/com.foo.MainActivity}"
    private val focusRegex = Regex("mCurrentFocus=Window\\{[^}]*\\s([A-Za-z0-9_.]+)/")

    private var job: Job? = null

    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) {
            return
        }
        val appContext = context.applicationContext
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    checkOnce(appContext)
                } catch (t: Throwable) {
                    Log.w("GrimDroid", "悬浮窗检测失败", t)
                }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun checkOnce(context: Context) {
        val result = StellarShell.run("dumpsys window windows | grep mCurrentFocus")
        if (!result.success && result.output.isBlank()) {
            return
        }
        val packageName = parseForegroundPackage(result.output) ?: return
        if (packageName in VirusDatabase.suspiciousPackages && !SessionIgnoreList.contains(packageName)) {
            AlertOverlayService.showStrongAlert(context, packageName)
        }
    }

    private fun parseForegroundPackage(output: String): String? =
        focusRegex.find(output)?.groupValues?.getOrNull(1)
}
