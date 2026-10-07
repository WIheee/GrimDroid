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

package com.grimdroid.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.grimdroid.data.SettingsRepository
import com.grimdroid.service.GuardService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启接收器。
 *
 * 仅响应 [Intent.ACTION_BOOT_COMPLETED]，并读取设置开关（默认关闭）决定
 * 是否拉起 [GuardService]。读取 DataStore 是异步的，因此使用 [goAsync] 延长
 * 广播生命周期，避免 onReceive 返回后被系统回收。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (SettingsRepository.autoStartOnBoot(appContext).first()) {
                    GuardService.start(appContext)
                }
            } catch (t: Throwable) {
                Log.w("GrimDroid", "开机自启防护服务失败", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
