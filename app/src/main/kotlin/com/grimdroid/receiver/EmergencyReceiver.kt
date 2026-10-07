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
import com.grimdroid.service.EmergencyCleanup

/**
 * 接收防护通知上的“紧急清场”按钮点击，触发 [EmergencyCleanup]。
 */
class EmergencyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        EmergencyCleanup.trigger(context.applicationContext)
    }
}
