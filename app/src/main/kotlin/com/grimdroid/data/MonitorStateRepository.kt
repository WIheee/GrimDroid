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

package com.grimdroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

// 独立的 DataStore 文件，避免与 SettingsRepository 的文件冲突。
private val Context.monitorDataStore: DataStore<Preferences> by preferencesDataStore(name = "grimdroid_monitor")

/** 被监控的三类系统列表快照（存包名）。 */
data class MonitorSnapshot(
    val accessibility: Set<String>,
    val deviceAdmins: Set<String>,
    val notificationListeners: Set<String>,
)

/**
 * 记录上一次轮询到的系统列表，用于对比出“新增项”。
 *
 * 首次运行（[isInitialized] 为 false）只建立基线、不告警，避免把设备上已有的项
 * 误报为新变化。
 */
object MonitorStateRepository {

    private val Accessibility = stringSetPreferencesKey("monitor_accessibility")
    private val DeviceAdmins = stringSetPreferencesKey("monitor_device_admins")
    private val NotificationListeners = stringSetPreferencesKey("monitor_notification_listeners")
    private val Initialized = booleanPreferencesKey("monitor_initialized")

    suspend fun isInitialized(context: Context): Boolean =
        context.applicationContext.monitorDataStore.data.first()[Initialized] ?: false

    suspend fun setInitialized(context: Context, value: Boolean) {
        context.applicationContext.monitorDataStore.edit { preferences ->
            preferences[Initialized] = value
        }
    }

    suspend fun currentSnapshot(context: Context): MonitorSnapshot {
        val preferences = context.applicationContext.monitorDataStore.data.first()
        return MonitorSnapshot(
            accessibility = preferences[Accessibility] ?: emptySet(),
            deviceAdmins = preferences[DeviceAdmins] ?: emptySet(),
            notificationListeners = preferences[NotificationListeners] ?: emptySet(),
        )
    }

    suspend fun saveSnapshot(context: Context, snapshot: MonitorSnapshot) {
        context.applicationContext.monitorDataStore.edit { preferences ->
            preferences[Accessibility] = snapshot.accessibility
            preferences[DeviceAdmins] = snapshot.deviceAdmins
            preferences[NotificationListeners] = snapshot.notificationListeners
        }
    }
}
