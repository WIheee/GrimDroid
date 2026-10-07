# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

GrimDroid is an open-source Android antivirus app (GPL-3.0-or-later). Single-module (`:app`) project:
Kotlin + Jetpack Compose UI, a C++ NDK native library exposed over JNI, and integration with
**Stellar** (a Shizuku-style privileged binder service) for elevated operations.

It is a **prototype**: the UI and service scaffolding are complete, but there is no real virus
signature database, scanning, or interception yet. Before changing behavior, read
[TECH_REPORT.md](TECH_REPORT.md) — it is the honest inventory of what works, what is a placeholder,
and what is unverified. `README.md` is the entry point for humans; `改动文件.md` is a per-round
change log.

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

Every source file must start with the copyright header from `codeformat/HEADER` (wrapped in `/* */` for Kotlin, C++, and XML-in-drawable, and in `<!-- -->` for markdown). Enforced by the Yumi Licenser plugin (`dev.yumi.gradle.licenser`).

The plugin only auto-discovers sources from Java-plugin source sets, and this project keeps Kotlin sources under `src/main/kotlin` (there is no `src/main/java`), so `app/build.gradle` manually registers the module's check/apply tasks via:

```groovy
license.registerTasks("android", fileTree("src") {
    include "**/*.kt"; include "**/*.cpp"; include "**/*.h"; include "**/*.hpp"
})
```

These aggregate into the global `checkLicenses` / `applyLicenses`. Both the `license {}` block and this `registerTasks` call must list the same extensions — when adding a new source type, update **both**. Note the licenser covers `.kt/.cpp/.h/.hpp` only; other file types (`.xml`, `.md`) are not checked.

## Architecture

Single Activity (`MainActivity`) with two top-level branches inside `setContent`:

- **Onboarding gate** — `SettingsRepository.onboardingDone()` decides whether to show `OnboardingScreen`
  (a 4-step flow: intro → welcome → Stellar authorization → batch authorization) or the main UI.
- **Main UI** — `ui/MainScreen.kt` hosts a bottom `NavigationBar` + `NavHost` with three tabs
  (安全模式 / 病毒扫描 / 设置). Tabs are independent Composables in `ui/`.

Persistent state is **DataStore** (`data/SettingsRepository.kt` for settings, `data/MonitorStateRepository.kt`
for monitoring snapshots). Both use `context.applicationContext`; note they are **two separate DataStore
files** (`grimdroid_settings`, `grimdroid_monitor`).

Runtime behavior is driven by `service/GuardService.kt` (a foreground service started from
`MainActivity.onCreate`). It is the single owner of the background machinery:

- **System monitoring** (`service/SystemMonitor.kt`): every 30 s diffs accessibility services, device
  admins, and notification listeners against a DataStore snapshot and raises alerts for new entries.
  App install/update is handled separately by `receiver/PackageChangeReceiver.kt`.
- **Alerting** (`service/AlertOverlayService.kt`): a *non*-foreground Service that draws a ComposeView
  over other apps via `TYPE_APPLICATION_OVERLAY`, or falls back to a high-priority notification when
  overlay permission is missing. Because a `ComposeView` outside an Activity needs tree owners, the
  service supplies them through the `OverlayOwner` class + `View.setViewTree*Owner` KTX extensions.
- **Strong mode** (`strong_mode_enabled`): gates `service/OverlayDetector.kt`, which polls the focused
  window via Stellar shell and matches against `VirusDatabase` (currently a placeholder set).
- **Emergency cleanup** (`service/EmergencyCleanup.kt`): `am kill-all` then a serial `am force-stop`
  loop over third-party packages. Triggered from the notification action (`receiver/EmergencyReceiver.kt`)
  or by plugging in power (a dynamically-registered receiver in `GuardService`, gated on
  `charge_trigger_enabled`).
- **Keep-alive**: three layers — (1) `START_STICKY` foreground service; (2) a native dual-sentinel
  watchdog; (3) `worker/GuardWorker.kt` (WorkManager, 15 min). Boot restore is
  `receiver/BootReceiver.kt` (gated on `auto_start_on_boot`, default off).

When wiring a new detection or action, follow the existing pattern: `SettingsRepository` flag →
`GuardService` observes/flips behavior → alerts go through `AlertOverlayService` / `dispatchAlert`.

## Native layer (JNI)

- `app/src/main/cpp/native-lib.cpp` — the three demo JNI functions (`helloFromCpp`, `addFromCpp`,
  `reverseFromCpp`) bound to `MainActivity`.
- `app/src/main/cpp/guard.cpp` — the dual-sentinel watchdog bound to `GuardService.nativeStartGuard`.
  It `fork`s `grimguard_a`, which forks `grimguard_b`; the two watch each other and the main process,
  writing `files/sentinel_a.pid` / `sentinel_b.pid` and respawning `GuardService` via
  `am start-foreground-service` when the main process dies. After `fork` the children touch **no** JNI/
  Android API — POSIX only.
- `app/src/main/cpp/CMakeLists.txt` — builds the `grimdroid` SHARED library from both `.cpp` files
  (add new sources here), links the NDK `log` library.
- JNI symbol names follow `Java_<package>_<Class>_<method>` and match the declaring Kotlin member, e.g.
  `Java_com_grimdroid_service_GuardService_nativeStartGuard`. Renaming/moving the Kotlin class or the
  `external fun` names requires updating these symbols.
- ABIs are limited to `arm64-v8a` and `x86_64` via `defaultConfig.ndk.abiFilters`.
- `.so` files are produced by Gradle's `externalNativeBuild` (CMake); there is no checked-in `jniLibs/` directory.

## Stellar integration

- Dependency: `com.github.roro2239:Stellar-API` from JitPack (declared in `app/build.gradle`; JitPack repo is in `settings.gradle.kts`).
- `AndroidManifest.xml` declares `roro.stellar.StellarProvider`, a signature-level custom permission
  `com.grimdroid.permission.STELLAR`, and meta-data `roro.stellar.permissions = "stellar"`.
- `MainActivity` registers `Stellar.OnBinderReceivedListener` / `OnBinderDeadListener` /
  `OnRequestPermissionResultListener` and calls `Stellar.requestPermission(...)`; the result is persisted
  as `stellar_authorized`. Listeners are removed in `onDestroy`.
- Privileged shell commands go through `service/StellarShell.kt` (`StellarShell.run(cmd)` →
  `Stellar.newProcess(arrayOf("sh","-c",cmd), null, null)`). **This `newProcess` signature is an
  assumption** based on public docs and has not been verified against the artifact — if it is wrong,
  every caller fails to compile. See TECH_REPORT §4.

## Structure

- `app/src/main/kotlin/com/grimdroid/`
  - `MainActivity.kt` — single Activity: onboarding/main gate, Stellar listeners, native calls.
  - `data/` — `SettingsRepository.kt`, `MonitorStateRepository.kt` (DataStore).
  - `service/` — `GuardService`, `SystemMonitor`, `AlertOverlayService`, `OverlayDetector`,
    `EmergencyCleanup`, `StellarShell`.
  - `receiver/` — `BootReceiver`, `PackageChangeReceiver`, `EmergencyReceiver`.
  - `worker/` — `GuardWorker`.
  - `ui/` — `OnboardingScreen`, `MainScreen`, `SecurityModeScreen`, `ScanScreen`, `SettingsScreen`,
    `AlertDialogView`; `ui/theme/` holds the Compose theme (`ComposeEmptyActivityTheme`).
- `settings.gradle.kts` — repositories: `google()`, `mavenCentral()`, JitPack.
- `codeformat/HEADER` — the GPLv3 header text (source of truth for the licenser).

## Known build risks (see TECH_REPORT.md §4 for the full list)

- `guard.cpp` uses `snprintf` but does not `#include <cstdio>` — may fail to compile.
- `Stellar.newProcess(...)` signature is unverified.
- The project has not been built in a clean environment recently; verify with `./gradlew assembleDebug`
  before trusting compilation.
