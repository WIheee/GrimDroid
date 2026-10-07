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

// 轮询父进程是否存活的间隔（秒）。
static const int CHECK_INTERVAL_SECONDS = 5;

/**
 * 启动原生守护进程。
 *
 * 调用方（Java/Kotlin 侧）传入要拉起的组件名，形如
 * "com.grimdroid/com.grimdroid.service.GuardService"。
 *
 * 流程：
 *   1. fork 出子进程，子进程调用 setsid 脱离父进程的会话/进程组，
 *      这样父进程（App 进程）被杀时不会连带把守护进程一起带走；
 *   2. 子进程进入循环，用 kill(parentPid, 0) 探测父进程是否存活；
 *   3. 一旦发现父进程已消失，就用 `am start-foreground-service` 重新拉起服务。
 *
 * 注意：fork 之后子进程不再触碰任何 Android/JNI API，只做纯 POSIX 操作，
 * 以免在 App 的多线程环境中调用非异步信号安全的接口而出问题。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_grimdroid_service_GuardService_nativeStartGuard(JNIEnv *env, jobject /* this */,
                                                         jstring component) {
    if (component == nullptr) {
        return;
    }

    const char *chars = env->GetStringUTFChars(component, nullptr);
    if (chars == nullptr) {
        return;
    }
    // 在 fork 之前把命令拼好，子进程只读取拷贝出来的内存，不再做分配/构造。
    std::string command = "am start-foreground-service -n ";
    command += chars;
    env->ReleaseStringUTFChars(component, chars);

    // 记录父进程（即 App 进程）的 pid；fork 之后子进程的 getppid() 不可靠。
    const pid_t parentPid = getpid();

    const pid_t pid = fork();
    if (pid != 0) {
        // pid > 0：父进程，直接返回 JVM；pid < 0：fork 失败，同样返回。
        return;
    }

    // ===== 以下仅子进程执行 =====
    setsid();

    // 设置进程名，便于在 ps/top 中识别守护进程。
    prctl(PR_SET_NAME, "grimguard", 0, 0, 0);

    // 将 oom_score_adj 调低，降低被低内存杀手（LMK）回收的概率。
    const int oomFd = open("/proc/self/oom_score_adj", O_WRONLY);
    if (oomFd >= 0) {
        const char oomValue[] = "0";
        if (write(oomFd, oomValue, sizeof(oomValue) - 1) < 0) {
            // 写入失败不影响守护主流程，忽略。
        }
        close(oomFd);
    }

    // 丢弃标准输入/输出/错误，避免占用 App 的控制台。
    const int devNull = open("/dev/null", O_RDWR);
    if (devNull >= 0) {
        dup2(devNull, STDIN_FILENO);
        dup2(devNull, STDOUT_FILENO);
        dup2(devNull, STDERR_FILENO);
        if (devNull > STDERR_FILENO) {
            close(devNull);
        }
    }

    for (;;) {
        // 父进程不存在时 kill 返回 -1（ESRCH），据此判断 App 已被杀死。
        if (kill(parentPid, 0) != 0) {
            const pid_t spawn = fork();
            if (spawn == 0) {
                execlp("sh", "sh", "-c", command.c_str(), static_cast<char *>(nullptr));
                _exit(0);
            }
            if (spawn > 0) {
                waitpid(spawn, nullptr, 0);
            }
            // 服务已重新拉起，本次守护使命完成，退出。
            _exit(0);
        }
        sleep(CHECK_INTERVAL_SECONDS);
    }
}
