# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

GrimDroid is an open-source Android antivirus app. Single-module (`:app`) Android project: Kotlin + Jetpack Compose UI, a C++ NDK native library exposed over JNI, and integration with Stellar (a Shizuku-style privileged binder service) for elevated operations.

## Toolchain

- Gradle 9.0.0 (wrapper), AGP 8.13.0, Kotlin 2.1.0, Java/JVM target 17
- compileSdk 36, minSdk 29, targetSdk 34
- NDK `30.0.15729638`, CMake `4.1.2` (both pinned in `app/build.gradle`)
- `gradle.properties` enables caching, parallel, and configuration cache. Docs for the current Gradle version: https://docs.gradle.org/9.0.0/userguide/userguide.html

The project is developed on-device under **Termux**. `app/build.gradle` sets `packaging.jniLibs.keepDebugSymbols += "**/libandroidx.graphics.path.so"` to work around `llvm-strip` failing in that environment — do not remove it.

## Commands

```bash
./gradlew assembleDebug          # build debug APK
./gradlew installDebug           # build + install on connected device
./gradlew clean                  # wipe build outputs (root build.gradle registers this)
./gradlew checkLicenses          # verify GPLv3 headers on all sources
./gradlew applyLicenses          # auto-insert/fix GPLv3 headers
```

There are currently **no unit or instrumentation test source sets** (`app/src/test`, `app/src/androidTest` do not exist), though `testInstrumentationRunner` is configured. Do not assume a test task exists until one is added.

## Licensing (GPLv3) — mandatory

Every source file must start with the copyright header from `codeformat/HEADER` (wrapped in `/* */` for Kotlin and C++). Enforced by the Yumi Licenser plugin (`dev.yumi.gradle.licenser`).

The plugin only auto-discovers sources from Java-plugin source sets, and this project keeps Kotlin sources under `src/main/kotlin` (there is no `src/main/java`), so `app/build.gradle` manually registers the module's check/apply tasks via:

```groovy
license.registerTasks("android", fileTree("src") {
    include "**/*.kt"; include "**/*.cpp"; include "**/*.h"; include "**/*.hpp"
})
```

These aggregate into the global `checkLicenses` / `applyLicenses`. Both the `license {}` block and this `registerTasks` call must list the same extensions — when adding a new source type, update **both**.

## Native layer (JNI)

- `app/src/main/cpp/native-lib.cpp` — JNI implementations, compiled into `libgrimdroid.so` and loaded with `System.loadLibrary("grimdroid")` in `MainActivity`'s companion `init`.
- `app/src/main/cpp/CMakeLists.txt` — builds the `grimdroid` SHARED library, links the NDK `log` library.
- JNI symbol names follow `Java_<package>_<Class>_<method>`, e.g. `Java_com_grimdroid_MainActivity_helloFromCpp`. Renaming/moving the Kotlin class or the `external fun` names requires updating these symbols.
- ABIs are limited to `arm64-v8a` and `x86_64` via `defaultConfig.ndk.abiFilters`.
- `.so` files are produced by Gradle's `externalNativeBuild` (CMake); there is no checked-in `jniLibs/` directory.

## Stellar integration

- Dependency: `com.github.roro2239:Stellar-API` from JitPack (declared in `app/build.gradle`; JitPack repo is in `settings.gradle.kts`).
- `AndroidManifest.xml` declares `roro.stellar.StellarProvider`, a signature-level custom permission `com.grimdroid.permission.STELLAR`, and meta-data `roro.stellar.permissions = "stellar"`.
- `MainActivity` registers `Stellar.OnBinderReceivedListener` / `OnBinderDeadListener` / `OnRequestPermissionResultListener` and calls `Stellar.requestPermission(...)` with a private request code. Listeners are removed in `onDestroy`.

## Structure

- `app/src/main/kotlin/com/grimdroid/MainActivity.kt` — the single Activity: declares the `external fun` native bindings, wires Stellar listeners, renders the Compose UI showing Stellar status and native-call results.
- `app/src/main/kotlin/com/grimdroid/ui/theme/` — Compose theme (`Color.kt`, `Theme.kt`, `Type.kt`); the app theme is `ComposeEmptyActivityTheme`.
- `settings.gradle.kts` — repositories: `google()`, `mavenCentral()`, JitPack.
- `codeformat/HEADER` — the GPLv3 header text (source of truth for the licenser).
