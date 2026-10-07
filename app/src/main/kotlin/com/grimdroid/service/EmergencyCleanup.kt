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
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import roro.stellar.Stellar

/**
 * 紧急清场。
 *
 * 由通知按钮 / 充电触发。清场命令共两步：
 *   1. 记录当前 Stellar 与 GuardService 状态；
 *   2. `am kill-all`；
 *   3. `am force-stop` 所有第三方包（白名单：自身、Stellar Manager），串行执行；
 *   4. 3 秒后复查，Stellar 断开或服务未运行则重新拉起 [GuardService]。
 *
 * 两条命令依次单独发送、等待执行完毕再发下一条；任一步失败只记录日志、继续后续步骤。
 */
object EmergencyCleanup {

    private const val TAG = "GrimDroid"
    private const val RECOVER_DELAY_MS = 3_000L

    // 白名单：自身 + Stellar Manager，不参与 force-stop。
    private const val FORCE_STOP_WHITELIST = "^(com\\.grimdroid|roro\\.stellar\\.manager)$"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun trigger(context: Context) {
        // 振动反馈，让用户知道清场已启动
        val vibrator = context.getSystemService(Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(
                VibrationEffect.createOneShot(100L, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(100L)
        }

        val appContext = context.applicationContext
        scope.launch {
            // 1. 记录状态
            val stellarAliveBefore = isStellarAlive()
            val guardRunningBefore = GuardService.isRunning
            Log.i(
                TAG,
                "紧急清场开始：Stellar=$stellarAliveBefore, GuardService=$guardRunningBefore",
            )

            // 2. am kill-all
            runStep("am kill-all", "am kill-all")

            // 3. force-stop 第三方包（串行执行，避免多个 am 命令并发抢占 binder）
            val forceStopThirdParty =
                "for pkg in \$(pm list packages -3 | sed 's/package://' | grep -vE '$FORCE_STOP_WHITELIST'); do " +
                    "am force-stop \"\$pkg\"; done"
            runStep("force-stop 第三方包", forceStopThirdParty)

            // 5. 3 秒后复查，任一失效则重新拉起防护服务
            delay(RECOVER_DELAY_MS)

            val stellarAliveAfter = isStellarAlive()
            if (!stellarAliveAfter) {
                Log.w(TAG, "清场后 Stellar 已断开，重新拉起防护服务")
                GuardService.start(appContext)
            }
            if (!GuardService.isRunning) {
                Log.w(TAG, "清场后防护服务未运行，重新拉起")
                GuardService.start(appContext)
            }

            Log.i(
                TAG,
                "紧急清场结束：Stellar=$stellarAliveAfter, GuardService=${GuardService.isRunning}",
            )
        }
    }

    /** 单独发送一条命令并等待其执行完毕；异常或失败只记录日志，不影响后续步骤。 */
    private suspend fun runStep(name: String, command: String) {
        val result = try {
            StellarShell.run(command)
        } catch (t: Throwable) {
            Log.w(TAG, "清场步骤[$name]异常", t)
            return
        }
        Log.i(TAG, "清场步骤[$name]完成：success=${result.success}, output=${result.output.take(200)}")
    }

    private fun isStellarAlive(): Boolean = try {
        Stellar.pingBinder()
    } catch (_: Throwable) {
        false
    }
}
