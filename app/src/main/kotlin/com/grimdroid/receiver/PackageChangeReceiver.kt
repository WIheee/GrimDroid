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
import com.grimdroid.service.AlertType
import com.grimdroid.service.SecurityAlert
import com.grimdroid.service.appLabel
import com.grimdroid.service.dispatchAlert

/**
 * 监听应用安装 / 更新广播。
 *
 * [Intent.ACTION_PACKAGE_ADDED]（非 replacing）视为新应用安装，
 * [Intent.ACTION_PACKAGE_REPLACED] 视为应用更新。忽略自身包名。
 */
class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val changedPackage = intent.data?.schemeSpecificPart ?: return
        // 忽略自身安装/更新
        if (changedPackage == context.packageName) {
            return
        }

        val alert = when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    // 属于更新流程，交给 PACKAGE_REPLACED 处理
                    return
                }
                SecurityAlert(
                    type = AlertType.PACKAGE,
                    title = "检测到新应用安装",
                    description = "「${appLabel(context, changedPackage)}」刚刚安装，建议扫描确认安全",
                )
            }

            Intent.ACTION_PACKAGE_REPLACED -> SecurityAlert(
                type = AlertType.PACKAGE_UPDATE,
                title = "检测到应用更新",
                description = "「${appLabel(context, changedPackage)}」刚刚更新，建议扫描确认安全",
            )

            else -> return
        }

        dispatchAlert(context, alert)
    }
}
