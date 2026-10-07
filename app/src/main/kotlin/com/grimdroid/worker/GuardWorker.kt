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

package com.grimdroid.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.grimdroid.service.GuardService
import java.util.concurrent.TimeUnit

/**
 * 周期看护任务：每隔 15 分钟检查 [GuardService] 是否存活，不在则拉起。
 *
 * 与 Native 守护形成双保险：Native 负责进程被杀后的快速重建，
 * WorkManager 则在更长的时间尺度上兜底。
 */
class GuardWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (GuardService.isRunning) {
            return Result.success()
        }
        return try {
            GuardService.start(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            // Android 12+ 在后台启动前台服务可能被拒绝，交给退避重试
            Log.w("GrimDroid", "看护任务启动防护服务失败", t)
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "grimdroid_guard_worker"
        private const val INTERVAL_MINUTES = 15L

        /** 连续失败达到该次数后放弃重试。 */
        private const val MAX_ATTEMPTS = 3

        /** 注册唯一的周期任务；已存在时保持原样，不重复入队。 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GuardWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
