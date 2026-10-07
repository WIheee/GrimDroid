<!--
  This file is part of GrimDroid (https://github.com/WIheee/GrimDroid).

  Copyright (c) 2026 WIhee

  GrimDroid is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  GrimDroid is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with GrimDroid. If not, see <https://www.gnu.org/licenses/>.
-->

# GrimDroid 技术报告

本报告是对当前代码库的**静态盘点**，目的是如实记录实现了什么、依赖什么、哪些还不能工作。
不美化、不夸大。凡属推断或未经验证的，均明确标注。

> **盘点前提**：本报告基于仓库当前源码的静态阅读。本次盘点环境**未执行过 Gradle 构建**（无
> Gradle 缓存），仓库内也**没有任何测试代码**。因此除"逻辑上必然成立"或"明显未实现"的结论外，
> 所有"能否工作"的判断都是**未经运行时验证**的推测。凡涉及真机/特定 ROM 才能确定的行为，均以
> "未验证"标注。

---

## 1. 架构总览

### 1.1 模块清单

| 模块 | 包/目录 | 职责 |
| --- | --- | --- |
| UI（Compose） | `com.grimdroid.ui.*`、`ui/theme` | 引导页、底部三 Tab 主界面、设置页、悬浮窗弹窗 UI |
| Service（前台/普通服务） | `com.grimdroid.service.*` | 前台防护服务、系统变化轮询、悬浮窗、三种强力模式触发器、紧急清场、Stellar Shell 封装 |
| Receiver（广播） | `com.grimdroid.receiver.*` | 开机自启、应用安装/更新、紧急清场按钮 |
| Worker | `com.grimdroid.worker.*` | WorkManager 周期看护 |
| Data（持久化） | `com.grimdroid.data.*` | DataStore：设置项、监控快照 |
| Native（JNI） | `app/src/main/cpp` | `libgrimdroid.so`：C++ 演示函数 + 双哨兵守护进程 |
| 入口 | `com.grimdroid.MainActivity` | 引导/主界面分流、Stellar 监听、C++ 调用、启动防护服务 |

### 1.2 模块依赖图

```
MainActivity
 ├── OnboardingScreen ── SettingsRepository, StellarShell, Stellar
 ├── MainScreen ── SecurityModeScreen / ScanScreen / SettingsScreen
 │                                   └── SettingsRepository, StellarShell(未直接用)
 ├── GuardService ─┬─ SystemMonitor ── MonitorStateRepository, dispatchAlert
 │                 │        └─(弹窗) AlertOverlayService / (降级) 通知
 │                 ├─ OverlayDetector ── StellarShell, VirusDatabase(占位)
 │                 ├─ EmergencyCleanup ─ StellarShell, Stellar, GuardService
 │                 ├─ SettingsRepository
 │                 ├─ chargeReceiver（动态注册，ACTION_POWER_CONNECTED）→ EmergencyCleanup
 │                 └─ nativeStartGuard() ─→ libgrimdroid.so (guard.cpp: 双哨兵)
 ├── GuardWorker (WorkManager) ── GuardService
 └── SettingsRepository (DataStore)

receivers: BootReceiver → GuardService
           PackageChangeReceiver → dispatchAlert → AlertOverlayService / 通知
           EmergencyReceiver → EmergencyCleanup
```

依赖方向总体是单向的：UI → Service/Data → 系统 API / Stellar。Native 只被 `GuardService` 调用。

---

## 2. 各功能详细说明

### 2.1 Stellar 授权

- **实现了什么**：`MainActivity` 注册 `Stellar` 的 binder 连接/断开/权限结果监听；连接成功后自动
  `requestPermission("stellar", 1001)`；结果写入 DataStore（`stellar_authorized`）。引导页 Step 2 也
  可手动触发授权。
- **技术/API**：Stellar SDK（`roro.stellar.Stellar`）、JitPack 依赖 `com.github.roro2239:Stellar-API`。
- **依赖**：必须安装并激活 **Stellar Manager** 应用；`AndroidManifest` 声明了 `StellarProvider` 与
  签名级自定义权限 `com.grimdroid.permission.STELLAR`。
- **能否工作**：**逻辑完整，未真机验证**。未安装 Stellar Manager 时 `pingBinder()` 为 false，所有
  Stellar 相关功能整体失效；此时引导页会提示"Stellar 未连接"并显示"安装 Stellar Manager"按钮。

### 2.2 C++ JNI（hello / add / reverse）

- **实现了什么**：`native-lib.cpp` 三个函数：`helloFromCpp()` 返回 "Hello from C++!"，
  `addFromCpp(a,b)` 返回 a+b，`reverseFromCpp(s)` 反转字符串。`MainActivity.onCreate` 调用一次并
  把结果通过 Settings 页"关于"展开项展示。
- **技术/API**：JNI + CMake（`externalNativeBuild`），库名 `grimdroid`。
- **依赖**：无额外权限。
- **能否工作**：**理论可用**（代码简单，无外部依赖）。属第 1 版遗留功能，本次盘点未重新验证。

### 2.3 引导页（Onboarding）

- **实现了什么**：4 个页面 —— Step 0 说明页（提醒去系统设置开高耗电/自启动，按钮"去设置"跳应用详情、
  "继续"）、Step 1 欢迎页、Step 2 Stellar 授权（成功自动进 Step 3，可跳过）、Step 3 批量授权（通知权限、
  电池优化白名单、自启动/后台限制，"完成"写入 `onboarding_done=true`）。完成后整个引导不再出现。
- **技术/API**：Compose + Navigation 无关（引导与主界面在 `MainActivity` 里用 `when(onboardingDone)` 分流）；
  通知权限用 `rememberLauncherForActivityResult`；Step 3 两项走 `StellarShell.run`。
- **依赖**：Stellar（Step 3 两项）、系统设置页、POST_NOTIFICATIONS。
- **能否工作**：**UI 与流程完整，未真机验证**。若跳过 Stellar 授权，主界面顶部显示"功能受限"横幅。
  已知问题见 §4（Step 3 的两条 shell 命令 ROM 相关）。

### 2.4 GuardService 前台服务

- **实现了什么**：`START_STICKY` 前台服务，常驻通知（标题"GrimDroid 防护中"、内容显示拦截数、
  附加"🚨 紧急清场"按钮）。启动时调用 `nativeStartGuard()` 起哨兵，启动系统变化轮询与强力模式
  检测器观察，并动态注册 `ACTION_POWER_CONNECTED` 接收器（充电触发清场，见 §2.11）。
- **技术/API**：`Service.startForeground`、`NotificationCompat`、`foregroundServiceType="dataSync"`。
- **依赖**：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`（Android 13+
  不授权则通知不显示，但服务仍运行）。
- **能否工作**：**逻辑完整，未真机验证**。`MainActivity.onCreate` 无条件拉起（与引导是否完成无关）。
  通知中的"拦截数"恒为 0 —— 没有任何真实拦截逻辑在递增它。

### 2.5 双哨兵进程守护（guard.cpp）

- **实现了什么**：主进程 fork 出哨兵 A，A 再 fork 出哨兵 B；两者互相监控对方 + 主进程 pid，每 5 秒
  `kill(pid,0)` 判活。对端死亡则 fork 替代者；主进程死亡则各自 `am start-foreground-service` 后退出。
  pid 写入 `files/sentinel_a.pid`、`files/sentinel_b.pid`，进程名 `grimguard_a` / `grimguard_b`。
- **技术/API**：纯 POSIX（`fork`/`setsid`/`prctl`/`kill`/`open`），fork 后不再触碰 JNI。
- **依赖**：无权限，但拉起服务依赖 `am`（运行身份为 shell/app uid）。
- **能否工作**：**逻辑完整，未真机验证；且存在一个编译级疑点**：`guard.cpp` 使用了 `snprintf` 但
  未 `#include <cstdio>`（只包含了 `<string>` 等）。能否通过编译取决于 NDK 头文件的间接包含，
  **有可能直接编译失败**。详见 §4-①。

### 2.6 WorkManager 看护（GuardWorker）

- **实现了什么**：唯一周期任务，每 **15 分钟**检查 `GuardService.isRunning`，未运行则 `start()`；
  失败按线性退避重试，连续失败 3 次（`runAttemptCount >= 3`）后返回 `failure`。在 `MainActivity.onCreate`
  注册（`ExistingPeriodicWorkPolicy.KEEP`）。
- **技术/API**：`androidx.work`（`CoroutineWorker`、`PeriodicWorkRequestBuilder`）。
- **依赖**：无。
- **能否工作**：**逻辑完整，未真机验证**。Android 12+ 从后台启动前台服务可能被拒，已 try/catch 并
  返回 `retry`，即**兜底能力在部分 ROM 上会退化**（见 §5）。

### 2.7 开机自启（BootReceiver）

- **实现了什么**：监听 `BOOT_COMPLETED`，异步读 DataStore 的 `auto_start_on_boot`（**默认关**），
  为 true 才拉起 `GuardService`。用 `goAsync()` 延长广播生命周期。
- **技术/API**：`BroadcastReceiver` + DataStore + 协程。
- **依赖**：`RECEIVE_BOOT_COMPLETED`。
- **能否工作**：**逻辑完整，未真机验证**。默认关闭，用户需在设置页手动开启。多数国产 ROM 还需额外
  的"自启动"白名单，否则广播可能收不到或不生效。

### 2.8 日常保护（4 类系统监控）

- **实现了什么**：
  - 无障碍服务列表、设备管理员列表、通知监听服务列表：`GuardService` **每 30 秒**轮询，与 DataStore
    快照对比，新增项触发提醒；首次运行只建立基线不告警（`monitor_initialized`）。
  - 新应用安装 / 应用更新：`PackageChangeReceiver` 监听 `PACKAGE_ADDED`（非 replacing）/ `PACKAGE_REPLACED`。
  - 文案按类型固定（无障碍/设备管理员/通知监听/新应用/应用更新）。
- **技术/API**：`Settings.Secure`（ENABLED_ACCESSIBILITY_SERVICES、enabled_notification_listeners）、
  `DevicePolicyManager.getActiveAdmins()`、`PackageManager`、广播。
- **依赖**：第 4 类依赖 `PACKAGE_*` 广播能否送达（见 §4-⑤）。
- **能否工作**：**前三类逻辑完整、未真机验证**；**第四类不确定**——Android 8+ 对清单注册的隐式广播
  有限制，`PACKAGE_ADDED/REPLACED` 是否稳定送达需真机确认。所有提醒文案里的应用名通过
  `PackageManager` 解析，解析失败回退为包名。

### 2.9 悬浮窗弹窗 + 降级通知

- **实现了什么**：`AlertOverlayService`（普通 Service）用 `WindowManager` +
  `TYPE_APPLICATION_OVERLAY` 添加 `ComposeView`，展示 MD3 卡片；20 秒自动移除；"知道了"立即移除；
  "查看详情"打开 `MainActivity` 并携带 title/description（**MainActivity 目前不消费这两个 extra，属占位**）。
  弹窗前检查 `Settings.canDrawOverlays`：无权限则改为发一条高优先级通知提示重新授权。
- **技术/API**：`WindowManager`、`ComposeView`、自建三合一 owner（`OverlayOwner`）提供
  `LifecycleOwner`/`ViewModelStoreOwner`/`SavedStateRegistryOwner`。
- **依赖**：`SYSTEM_ALERT_WINDOW`（特殊权限，需用户在系统设置单独授予）、`POST_NOTIFICATIONS`。
- **能否工作**：**UI 与降级逻辑完整，未真机验证**。ComposeView 在 Service 中的宿主问题是本项目
  踩过坑后修过的点（原先用静态 `ViewTree*Owner.set` 编译不过，现改为 KTX 扩展 + `OverlayOwner`），
  但**尚未编译/运行验证**。

### 2.10 强力模式

- **OverlayDetector**：通过 Stellar 执行 `dumpsys window windows | grep mCurrentFocus`，正则解析当前
  焦点窗口包名，每 5 秒轮询，命中"病毒库"则触发强力弹窗（冻结/卸载/忽略）。
- **开关**：设置页"强力保护模式"（`strong_mode_enabled`），由 `GuardService` 观察 DataStore 启停该检测器。
- **依赖**：Stellar。
- **能否工作**：**实际不生效**——见 §4-②，病毒库为空占位集合，命中不了真实应用。
- **历史**：曾另有 `VolumeKeyDetector`（音量键 5 连）与 `ShakeDetector`（摇一摇 5 次）两个触发器，
  因可靠性不足已删除；详见 `改动文件.md`。

### 2.11 紧急清场（EmergencyCleanup）

- **实现了什么**：`trigger()` 先振动反馈，再记录当前 Stellar 与 GuardService 状态 → 依次执行**两条命令**：
  `am kill-all`，然后**串行** `am force-stop` 所有第三方包（白名单：自身 `com.grimdroid`、
  `roro.stellar.manager`；用 for 循环串行，避免多个 `am` 并发抢占 binder）→ 3 秒后复查，
  Stellar 断开或服务未运行则重新拉起。
- **触发**：通知按钮（`EmergencyReceiver`）、充电触发（常驻开关，每次插电都触发）。
- **技术/API**：Stellar Shell、协程（进程级作用域）、`Vibrator`。
- **依赖**：Stellar（`am kill-all` / `am force-stop` 需特权）；振动需 `VIBRATE` 权限（**当前未声明**，见 §4）。
- **能否工作**：**逻辑完整，未真机验证**。副作用大——会杀掉**所有第三方应用**（含用户正在用的），
  属"恐慌按钮"语义；`am kill-all` 本身效果有限，实测收益依 ROM 而异。
- **历史**：曾包含一条 `kill -9` 第三方进程的命令，因 shell 对普通应用进程通常无 kill 权限（被 SELinux 拒绝、
  结果被 `2>/dev/null` 吞掉、误报成功）而删除。

### 2.12 病毒库

- **实现了什么**：`OverlayDetector.kt` 内的 `VirusDatabase.suspiciousPackages`。
- **当前状态**：**纯占位**。内容是两个不会出现在真实设备上的示例包名（`com.example.test.malware`、
  `com.example.ransomware.demo`）。**没有任何真实的特征库、签名库或云端更新**。也就是说，
  强力模式的"命中"链路除了手动改成真包名外，**不会触发任何真实威胁**。

### 2.13 设置页各开关

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 开机自启 | **生效** | DataStore `auto_start_on_boot`，驱动 BootReceiver |
| 后台实时监控 | **无效** | 仅 `rememberSaveable` 本地状态，不驱动任何逻辑 |
| 拦截通知 | **无效** | 仅本地状态，不驱动任何逻辑 |
| 强力保护模式 | **生效** | DataStore `strong_mode_enabled`，驱动 OverlayDetector 启停 |
| 充电触发清场 | **生效（常驻）** | DataStore `charge_trigger_enabled`；开着就一直开，每次插电触发紧急清场 |
| 悬浮窗权限 | **生效** | 显示授权状态，点击跳 `ACTION_MANAGE_OVERLAY_PERMISSION`，返回后刷新 |
| 关于 GrimDroid | **展示** | 展开显示版本号（硬编码 "1.0"）、Stellar 状态、C++ 三个结果 |

### 2.14 主界面其余部分

- **安全模式页（3 档选择）**：卡片单选（强力/日常/病毒测试）+ 说明文字。**纯 UI，不影响任何行为**；
  选择只存在内存，未与任何检测/拦截逻辑联动。
- **病毒扫描页**：一张 Card + "开始扫描"按钮。点击后按钮变"扫描中…"、显示进度条、**3 秒后固定**
  显示"未发现威胁"，"上次更新"更新为当前时间。**是模拟，不做任何真实扫描**；扫描记录仅内存态。
- **设置页"关于"**：见上表。

---

## 3. 权限清单

`AndroidManifest.xml` 声明的全部权限：

| 权限 | 用途 | 少了会怎样 |
| --- | --- | --- |
| `FOREGROUND_SERVICE` | 允许启动前台服务（API 28+ 必需） | `GuardService` 无法 `startForeground`，防护服务整体不可用 |
| `FOREGROUND_SERVICE_DATA_SYNC` | Android 14 起，声明 `dataSync` 类型 FGS 所需 | Android 14+ 启动该服务抛异常 |
| `POST_NOTIFICATIONS` | 展示前台服务通知与降级提醒（Android 13+ 运行时授予） | 通知不显示（服务仍在跑）；降级提醒也看不到 |
| `RECEIVE_BOOT_COMPLETED` | 接收开机广播 | 开机自启完全失效 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗弹窗（特殊权限，需系统设置单独授予） | 悬浮窗失败，自动降级为通知 |
| `com.grimdroid.permission.STELLAR`（**自定义声明**，非申请） | Stellar 权限体系中的核心权限标识，与 `StellarProvider` 配套 | 授权流程无法通过校验 |

> 注意：`SYSTEM_ALERT_WINDOW` 与 `POST_NOTIFICATIONS` 都**不是** Manifest 声明即生效，需运行时/系统
> 设置授予；`INTERACT_ACROSS_USERS_FULL` 出现在 `StellarProvider` 上，是**限制调用方**的属性，不是本应用申请的权限。

---

## 4. 已知问题和技术债

按严重程度排列：

1. **① guard.cpp 可能编译失败**：`writePidFile` 使用 `snprintf` 但文件未 `#include <cstdio>`。C++ 下
   未声明函数是硬错误。是否报错取决于 NDK 头文件是否间接包含 `stdio.h`（不确定）。**这是必须先解决
   的构建风险。**

2. **② 病毒库是占位，强力模式实际不生效**：`VirusDatabase.suspiciousPackages` 是假包名集合，
   OverlayDetector 永远命中不了真实应用。所谓"强力保护"的检测链路目前是**空转**。

3. **③ mCurrentFocus 检测本身脆弱**：`dumpsys window windows | grep mCurrentFocus` 只能拿到
   **当前获得输入焦点**的窗口。对于以下情况会漏检或取不到：不可聚焦的悬浮窗/画中画、部分 ROM 上
   `dumpsys window windows` 的输出格式变化（有的版本字段名/位置不同）、多窗口/分屏、`mCurrentFocus=null`
   （无焦点）的时刻。这是**设计层面的盲区**，不是实现 bug。

4. **④ 设置页两个开关是"假开关"**：`后台实时监控`、`拦截通知`只改本地 `mutableStateOf`，切走再回来
   即复位，且不驱动任何逻辑。**属误导性 UI**，应接入实现或移除。

5. **⑤ 应用安装/更新广播不确定**：`PackageChangeReceiver` 是清单注册的隐式广播接收器。Android 8+
   对隐式广播有限制，`PACKAGE_ADDED/REPLACED` 在部分版本/ROM 上可能收不到，需真机确认；收不到则
   该功能静默失效。

6. **⑥ Stellar API 未经确认**：`StellarShell.run` 依赖 `Stellar.newProcess(String[],String[],String)`
   及其返回类型是 `Process`（或子类）。这是**基于公开文档的假设**，本地无 Stellar 依赖产物可供核对。
   若签名不符，`StellarShell` 及其所有调用方（OverlayDetector、EmergencyCleanup、引导页 Step 3、
   AlertOverlayService 的冻结/卸载）都会编译失败。

7. **⑦ 大量硬编码**：轮询间隔（30s / 5s）、哨兵检测间隔（5s）、弹窗自动关闭（20s / 30s）、
   版本号（"1.0"）、拦截数（恒 0）、病毒库——全部写死，无配置或云端下发。

8. **⑧ 拦截数永远是 0**：`GuardService.interceptCount` 没有任何地方递增，通知里的"已拦截 N 项威胁"
   始终显示 0。无真实拦截逻辑。

9. **⑨ 安全模式页无联动**：3 档模式选择不影响任何检测/拦截行为，纯展示。

10. **⑩ 病毒扫描是假的**：3 秒定时器 + 固定文案，无真实扫描；记录不持久化。

11. **⑪ "查看详情"占位**：悬浮窗把 title/description 作为 extra 传给 `MainActivity`，但 MainActivity
    未读取，未跳转任何详情页。

12. **⑫ ROM 相关命令**：`dumpsys deviceidle whitelist +<pkg>`、`cmd appops set <pkg> RUN_IN_BACKGROUND allow`
    在新版 Android 上可能已变更或不再生效（deviceidle 部分版本迁到 `cmd deviceidle`；`RUN_IN_BACKGROUND`
    appop 已被弱化）。这些"批量授权"项在很多 ROM 上是**无实际操作效果**的。

13. **⑬ 无任何测试**：仓库没有 `src/test` 或 `src/androidTest`，全部功能靠人工验证，目前也**未真机验证**。

14. **⑭ 振动反馈缺 `VIBRATE` 权限**：`EmergencyCleanup.trigger()` 调用了 `Vibrator.vibrate(...)`，但
    manifest **未声明 `android.permission.VIBRATE`**。缺权限时振动不会生效（可能抛 `SecurityException`
    或被忽略），**"清场时振动提示"这一反馈很可能无效**。

15. **⑮ 紧急清场的副作用与触发语义**：清场命令为 `am kill-all` + 串行 `am force-stop` 所有第三方包，
    会**杀掉所有第三方应用**（含用户正在使用的），不是"只杀可疑进程"。且"充电触发"是**常驻开关**，
    开着则**每次插电都清场**，需注意误触发风险。

16. **⑯ 本次环境未构建**：无 Gradle 缓存，未能通过构建来暴露编译问题。上述①⑥⑭都可能直接导致构建失败或功能失效。

---

## 5. 威胁模型

### 5.1 设计上能防什么（假设全部依赖就绪）

- **权限被悄悄开启**：应用被授予无障碍、设备管理员、通知监听权限时给出提醒（轮询式，最长 30 秒延迟）。
- **新装了应用**：安装/更新时提醒（受 §4-⑤ 限制）。
- **已知恶意包出现在前台**：若病毒库被填实，可在其进入前台时弹窗，允许冻结/卸载。
- **紧急场景**：用户可点通知按钮或插电触发清场（`am kill-all` + `am force-stop`）并尝试恢复防护服务。

### 5.2 设计上防不了什么

- **不装 Stellar Manager**：几乎所有"系统级"能力（清场、冻结、卸载、悬浮窗检测）全部失效。
- **不授予悬浮窗权限**：只能降级为通知，无法主动弹窗拦截。
- **真实病毒/木马查杀**：没有真实特征库、没有文件扫描、没有行为引擎。**当前不具备杀毒能力**。
- **实时拦截**：所有监控都是"轮询 + 事后提醒"，无内核/系统级拦截，攻击可在被察觉前完成。
- **权限提升类攻击**：本应用不获取 root，`am kill-all` 等能力也受系统约束。
- **反卸载 / 自我保护**：没有设备管理员激活、没有反卸载逻辑（哨兵只保活服务，不阻止卸载）。

### 5.3 在哪些 ROM 上效果打折

- **国产重定制 ROM（MIUI/EMUI/ColorOS 等）**：后台限制严，前台服务可能被清理；`PACKAGE_*` 广播、
  开机自启、`deviceidle`/`appops` 命令生效情况差异大；哨兵进程可能被一并回收。
- **Android 12+**：后台启动前台服务的限制会削弱 WorkManager 与哨兵"拉起服务"的兜底能力。
- **Android 13+**：不授予 `POST_NOTIFICATIONS` 则看不到任何提醒（服务仍运行）。
- **Android 14+**：`dataSync` 类型 FGS 的启动时机进一步受限。
- **无 Stellar Manager 的设备**：强力模式整套与紧急清场均为空壳。

---

## 6. 状态总结表

| 功能 | 实现度 | 可用性 | 备注 |
| --- | --- | --- | --- |
| Stellar 授权 | 90% | 未验证，依赖外部 App | 无 Stellar Manager 则整体失效 |
| C++ JNI（hello/add/reverse） | 100% | 理论可用 | 简单 JNI，未见问题 |
| 引导页（Step 0–3） | 95% | 未验证 | Step 3 两条命令 ROM 相关（§4-⑫） |
| GuardService 前台服务 | 90% | 未验证 | 拦截数恒 0；Android 12+ 后台启动受限 |
| 双哨兵进程守护 | 85% | 未验证 + **编译风险** | guard.cpp 疑缺 `<cstdio>`（§4-①） |
| WorkManager 看护 | 90% | 未验证 | 15 分钟周期；后台起 FGS 可能被拒 |
| 开机自启 | 90% | 未验证 | 默认关；需 ROM 自启动白名单 |
| 日常保护 · 无障碍 | 85% | 未验证 | 30 秒轮询 + 快照对比 |
| 日常保护 · 设备管理员 | 85% | 未验证 | 同上 |
| 日常保护 · 通知监听 | 85% | 未验证 | 同上 |
| 日常保护 · 应用安装/更新 | 60% | **不确定** | Android 8+ 隐式广播可能收不到（§4-⑤） |
| 悬浮窗弹窗 | 85% | 未验证 | 需 SYSTEM_ALERT_WINDOW |
| 降级通知 | 90% | 未验证 | 无悬浮窗权限时触发 |
| 强力 · OverlayDetector | 30% | **实际不生效** | 病毒库为空占位（§4-②） |
| 强力 · 音量键/摇一摇 | — | **已删除** | 两个不可靠触发器已移除（见 `改动文件.md`） |
| 紧急清场 | 80% | 未验证 | 两步命令；会杀掉所有第三方应用（§4-⑮） |
| 紧急清场 · 振动反馈 | 50% | **可能无效** | 未声明 `VIBRATE` 权限（§4-⑭） |
| 紧急清场 · 充电触发 | 80% | 未验证 | 常驻开关，每次插电触发（§4-⑮） |
| 病毒库 | 5% | **占位** | 假包名，无真实特征库 |
| 设置 · 开机自启 | 100% | 生效 | DataStore 驱动 |
| 设置 · 强力保护模式 | 100% | 生效 | DataStore 驱动 OverlayDetector 启停 |
| 设置 · 充电触发清场 | 100% | 生效 | DataStore 驱动；常驻不复位 |
| 设置 · 后台实时监控 | 10% | **无效** | 仅本地状态（§4-④） |
| 设置 · 拦截通知 | 10% | **无效** | 仅本地状态（§4-④） |
| 设置 · 悬浮窗权限 | 100% | 生效 | 跳系统设置，返回刷新 |
| 安全模式页（3 档） | 15% | **无效** | 纯 UI，未驱动任何行为（§4-⑨） |
| 病毒扫描页 | 20% | **假实现** | 3 秒模拟，无真实扫描（§4-⑩） |
| 关于（版本/Stellar/C++） | 100% | 展示 | 版本号硬编码 |

---

## 7. 结论

当前代码库是**一个骨架完整的原型**：UI、前台服务、保活（哨兵 + WorkManager + 开机自启）、权限引导、
悬浮窗与降级、三类系统监控的**代码框架都已就位**，但存在三类硬伤：

1. **可能构建不过**（§4-① `snprintf`；§4-⑥ Stellar API 假设）——必须先解决。
2. **关键能力空转**（§4-② 病毒库占位、§4-⑧ 拦截数恒 0、§4-⑩ 假扫描）——有壳无核。
3. **多处未真机验证，且强依赖外部环境（Stellar Manager）与 ROM 行为**——实际效果充满不确定性。
4. **细节缺口**（§4-⑭ 振动缺 `VIBRATE` 权限、§4-⑮ 紧急清场副作用大且充电触发为常驻）——需按需处理。

**一句话**：现在它是一个能演示交互的"防护 App 外壳"，**还不是一个能杀毒、能拦截的防护软件**。
