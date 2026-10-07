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
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "grimdroid_settings")

/** 应用设置的持久化存储，基于 DataStore Preferences。 */
object SettingsRepository {

    private val AutoStartOnBoot = booleanPreferencesKey("auto_start_on_boot")
    private val OnboardingDone = booleanPreferencesKey("onboarding_done")
    private val StellarAuthorized = booleanPreferencesKey("stellar_authorized")
    private val StrongModeEnabled = booleanPreferencesKey("strong_mode_enabled")
    private val ChargeTriggerEnabled = booleanPreferencesKey("charge_trigger_enabled")

    /** 是否在开机后自动启动防护服务，默认关闭。 */
    fun autoStartOnBoot(context: Context): Flow<Boolean> =
        context.applicationContext.dataStore.data.map { preferences ->
            preferences[AutoStartOnBoot] ?: false
        }

    suspend fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { preferences ->
            preferences[AutoStartOnBoot] = enabled
        }
    }

    /** 是否已完成新手引导，默认未完成。 */
    fun onboardingDone(context: Context): Flow<Boolean> =
        context.applicationContext.dataStore.data.map { preferences ->
            preferences[OnboardingDone] ?: false
        }

    suspend fun setOnboardingDone(context: Context, done: Boolean) {
        context.applicationContext.dataStore.edit { preferences ->
            preferences[OnboardingDone] = done
        }
    }

    /** 是否已成功获得 Stellar 授权，默认否。 */
    fun stellarAuthorized(context: Context): Flow<Boolean> =
        context.applicationContext.dataStore.data.map { preferences ->
            preferences[StellarAuthorized] ?: false
        }

    suspend fun setStellarAuthorized(context: Context, authorized: Boolean) {
        context.applicationContext.dataStore.edit { preferences ->
            preferences[StellarAuthorized] = authorized
        }
    }

    /** 是否开启强力保护模式，默认关闭。 */
    fun strongModeEnabled(context: Context): Flow<Boolean> =
        context.applicationContext.dataStore.data.map { preferences ->
            preferences[StrongModeEnabled] ?: false
        }

    suspend fun setStrongModeEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { preferences ->
            preferences[StrongModeEnabled] = enabled
        }
    }

    /**
     * 充电触发清场开关，默认关闭。
     *
     * 该开关是一次性的：插电触发后会被 [setChargeTriggerEnabled] 立即复位为 false，
     * 避免每次插电都清场。
     */
    fun chargeTriggerEnabled(context: Context): Flow<Boolean> =
        context.applicationContext.dataStore.data.map { preferences ->
            preferences[ChargeTriggerEnabled] ?: false
        }

    suspend fun setChargeTriggerEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { preferences ->
            preferences[ChargeTriggerEnabled] = enabled
        }
    }
}
