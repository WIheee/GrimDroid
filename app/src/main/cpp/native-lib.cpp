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

#include <algorithm>
#include <string>

/// 返回一段字符串
/// Kotlin 侧: external fun helloFromCpp(): String
extern "C" JNIEXPORT jstring JNICALL
Java_com_grimdroid_MainActivity_helloFromCpp(JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF("Hello from C++!");
}

/// 整数相加
/// Kotlin 侧: external fun addFromCpp(a: Int, b: Int): Int
extern "C" JNIEXPORT jint JNICALL
Java_com_grimdroid_MainActivity_addFromCpp(JNIEnv * /* env */, jobject /* this */, jint a, jint b) {
    return a + b;
}

/// 反转字符串
/// Kotlin 侧: external fun reverseFromCpp(input: String): String
extern "C" JNIEXPORT jstring JNICALL
Java_com_grimdroid_MainActivity_reverseFromCpp(JNIEnv *env, jobject /* this */, jstring input) {
    if (input == nullptr) {
        return env->NewStringUTF("");
    }

    // 把 Java String 转成 C++ String
    const char *chars = env->GetStringUTFChars(input, nullptr);
    if (chars == nullptr) {
        // 内存不足，GetStringUTFChars 已抛出异常
        return nullptr;
    }

    std::string reversed(chars);
    env->ReleaseStringUTFChars(input, chars);

    // 反转
    std::reverse(reversed.begin(), reversed.end());

    // 把 C++ String 转回 Java String
    return env->NewStringUTF(reversed.c_str());
}
