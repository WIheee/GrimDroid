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

#include <jni.h>

#include <fcntl.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <string>

// 哨兵轮询间隔（秒）。
static const int CHECK_INTERVAL_SECONDS = 5;

// 两个哨兵的进程名与 pid 文件名。
static const char *SENTINEL_A_NAME = "grimguard_a";
static const char *SENTINEL_B_NAME = "grimguard_b";

/** 把 pid 以十进制写入文件（供排查 / 外部读取）。 */
static void writePidFile(const std::string &path, pid_t pid) {
    const int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) {
        return;
    }
    char buffer[32];
    const int length = snprintf(buffer, sizeof(buffer), "%d\n", static_cast<int>(pid));
    if (length > 0) {
        if (write(fd, buffer, static_cast<size_t>(length)) < 0) {
            // 写入失败不影响守护主流程，忽略。
        }
    }
    close(fd);
}

/** 降低被低内存杀手（LMK）回收的概率。 */
static void tuneOomScoreAdj() {
    const int fd = open("/proc/self/oom_score_adj", O_WRONLY);
    if (fd < 0) {
        return;
    }
    const char value[] = "0";
    if (write(fd, value, sizeof(value) - 1) < 0) {
        // 忽略
    }
    close(fd);
}

/** 丢弃标准输入/输出/错误，避免占用 App 的控制台。 */
static void detachStdio() {
    const int devNull = open("/dev/null", O_RDWR);
    if (devNull < 0) {
        return;
    }
    dup2(devNull, STDIN_FILENO);
    dup2(devNull, STDOUT_FILENO);
    dup2(devNull, STDERR_FILENO);
    if (devNull > STDERR_FILENO) {
        close(devNull);
    }
}

/** 用 `am start-foreground-service` 拉起 GuardService。 */
static void spawnService(const std::string &command) {
    const pid_t child = fork();
    if (child == 0) {
        execlp("sh", "sh", "-c", command.c_str(), static_cast<char *>(nullptr));
        _exit(0);
    }
}

/**
 * 哨兵主循环。
 *
 * @param command      拉起服务的命令
 * @param selfPidFile  本哨兵 pid 文件路径
 * @param peerPidFile  对端哨兵 pid 文件路径
 * @param mainPid      主（App）进程 pid
 * @param peerPid      对端哨兵 pid；<=0 表示尚未创建
 * @param selfName     本哨兵进程名
 * @param peerName     对端哨兵进程名
 *
 * 主进程消失 → 拉起服务并退出（新服务会重新 fork 哨兵）；
 * 对端哨兵消失 → fork 一个新的对端，两者互为守护。
 */
[[noreturn]] static void sentinelLoop(const std::string &command,
                                      const std::string &selfPidFile,
                                      const std::string &peerPidFile,
                                      pid_t mainPid,
                                      pid_t peerPid,
                                      const std::string &selfName,
                                      const std::string &peerName);

/** 初始化一个哨兵进程（脱离会话、改名、写 pid）后进入主循环。 */
[[noreturn]] static void startSentinel(const std::string &command,
                                       const std::string &selfPidFile,
                                       const std::string &peerPidFile,
                                       pid_t mainPid,
                                       pid_t peerPid,
                                       const std::string &selfName,
                                       const std::string &peerName) {
    setsid();
    prctl(PR_SET_NAME, selfName.c_str(), 0, 0, 0);
    tuneOomScoreAdj();
    detachStdio();
    writePidFile(selfPidFile, getpid());
    sentinelLoop(command, selfPidFile, peerPidFile, mainPid, peerPid, selfName, peerName);
}

[[noreturn]] static void sentinelLoop(const std::string &command,
                                      const std::string &selfPidFile,
                                      const std::string &peerPidFile,
                                      pid_t mainPid,
                                      pid_t peerPid,
                                      const std::string &selfName,
                                      const std::string &peerName) {
    for (;;) {
        // 主进程已消失：拉起服务后退出，交由新服务重新 fork 哨兵。
        if (kill(mainPid, 0) != 0) {
            spawnService(command);
            _exit(0);
        }

        // 对端哨兵不存在（pid<=0）或已消失：fork 一个替代者。
        if (peerPid <= 0 || kill(peerPid, 0) != 0) {
            const pid_t replacement = fork();
            if (replacement == 0) {
                // 替代者：自身与对端角色互换，其对端即当前进程。
                startSentinel(command, peerPidFile, selfPidFile, mainPid, getppid(),
                              peerName, selfName);
            }
            if (replacement > 0) {
                peerPid = replacement;
            }
        }

        sleep(CHECK_INTERVAL_SECONDS);
    }
}

/**
 * 启动双哨兵守护进程。
 *
 * Kotlin 侧传入要拉起的组件名（如 "com.grimdroid/com.grimdroid.service.GuardService"）
 * 与应用 files 目录，用于写 sentinel_a.pid / sentinel_b.pid。
 *
 * grimguard_a 监控主进程与 grimguard_b，grimguard_b 监控主进程与 grimguard_a：
 * 一方被杀由另一方重建；主进程被杀则两者都会尝试拉起服务。
 *
 * 注意：fork 之后子进程不再触碰任何 Android/JNI API，只做纯 POSIX 操作。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_grimdroid_service_GuardService_nativeStartGuard(JNIEnv *env, jobject /* this */,
                                                         jstring component, jstring filesDir) {
    if (component == nullptr || filesDir == nullptr) {
        return;
    }

    const char *componentChars = env->GetStringUTFChars(component, nullptr);
    if (componentChars == nullptr) {
        return;
    }
    // 在 fork 之前把所有字符串准备好，子进程只读取既有内存。
    std::string command = "am start-foreground-service -n ";
    command += componentChars;
    env->ReleaseStringUTFChars(component, componentChars);

    const char *dirChars = env->GetStringUTFChars(filesDir, nullptr);
    if (dirChars == nullptr) {
        return;
    }
    const std::string directory(dirChars);
    env->ReleaseStringUTFChars(filesDir, dirChars);

    const std::string pidAFile = directory + "/sentinel_a.pid";
    const std::string pidBFile = directory + "/sentinel_b.pid";

    // 记录主（App）进程 pid；fork 之后子进程的 getppid() 不可靠。
    const pid_t mainPid = getpid();

    const pid_t sentinelA = fork();
    if (sentinelA != 0) {
        // 父进程（或 fork 失败）直接返回 JVM。
        return;
    }

    // ===== 哨兵 A：peerPid 传 0，首轮循环会 fork 出哨兵 B =====
    startSentinel(command, pidAFile, pidBFile, mainPid, 0,
                  SENTINEL_A_NAME, SENTINEL_B_NAME);
}
