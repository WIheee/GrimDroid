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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import roro.stellar.Stellar

/** Shell 命令执行结果。 */
data class ShellResult(
    val success: Boolean,
    val output: String,
)

/**
 * 通过 Stellar 以特权身份执行 shell 命令。
 *
 * Stellar 重新启用了 Shizuku 的 `newProcess` API，返回的是一个 [Process]，
 * 可像本地进程一样读取输出、等待退出。所有调用都放到 IO 线程执行。
 */
object StellarShell {

    /**
     * 执行一条命令（经 `sh -c` 解释，支持管道/重定向等）。
     *
     * @return [ShellResult]，[ShellResult.success] 表示退出码为 0。
     */
    suspend fun run(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            if (!Stellar.pingBinder()) {
                return@withContext ShellResult(false, "Stellar 未连接")
            }

            val process = Stellar.newProcess(arrayOf("sh", "-c", command), null, null)
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            ShellResult(exitCode == 0, output)
        } catch (t: Throwable) {
            ShellResult(false, t.message ?: "命令执行失败")
        }
    }
}
