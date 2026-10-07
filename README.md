# GrimDroid

GrimDroid 是一款开源免费的 Android 手机安全防护软件，采用 GPL-3.0-or-later 许可。

> ⚠️ **当前状态：原型 / 未完成**。这是一个交互完整、但核心能力尚未落地的"防护 App 外壳"——
> 尚无真实病毒特征库、无真实扫描与拦截。完整的、不美化的现状盘点与已知问题见
> **[TECH_REPORT.md](TECH_REPORT.md)**。接手前请务必先读它。

## 功能概览

| 模块 | 说明 | 状态 |
| --- | --- | --- |
| 新手引导 | 4 步引导（说明 → 欢迎 → Stellar 授权 → 批量授权） | 流程完整，未真机验证 |
| 安全模式 | 3 档模式卡片选择 | 纯 UI，未联动任何逻辑 |
| 病毒扫描 | 云端特征库入口 + 扫描 | 3 秒模拟，非真实扫描 |
| 设置 | 开关 + 悬浮窗权限引导 | 部分开关生效，部分为占位 |
| 常驻防护（GuardService） | 前台服务 + 常驻通知 | 逻辑完整，未真机验证 |
| 保活 | 双哨兵进程互拉 + WorkManager 看护 + 开机自启 | 逻辑完整，未真机验证 |
| 日常保护 | 监控无障碍/设备管理员/通知监听/应用安装 | 前三类完整，第四类受广播限制 |
| 悬浮窗提醒 | 命中异常时弹悬浮窗，无权限则降级通知 | 逻辑完整，未真机验证 |
| 强力保护模式 | 前台应用检测（OverlayDetector） | 病毒库为空占位，实际不触发 |
| 紧急清场 | `am kill-all` + 串行 `am force-stop` 第三方包 | 测试环境Android16可以使用 |
| 紧急清场触发 | 通知按钮、充电触发（常驻开关） | 逻辑完整，未真机验证 |

各项的**实现度、可用性、已知缺陷**详见 [TECH_REPORT.md](TECH_REPORT.md) 的末尾状态表。

## 环境要求

- **设备**：Android 9（API 29）及以上
- **Stellar Manager**：必须安装并激活。GrimDroid 的"系统级"能力（清场、冻结/卸载、悬浮窗检测、
  批量授权命令等）全部通过 [Stellar](https://github.com/roro2239/Stellar)（类 Shizuku 的特权服务）
  执行；未安装或不授权时，这些功能整体失效。
- **悬浮窗**：需在系统设置授予"显示在其他应用上层"权限，否则提醒降级为通知。
- **开发环境**：JDK 17、Android SDK（compileSdk 36）、NDK `30.0.15729638`、CMake `4.1.2`。

## 构建与安装

```bash
./gradlew assembleDebug     # 构建 debug APK
./gradlew installDebug      # 构建并安装到已连接设备
./gradlew clean             # 清理构建产物
./gradlew checkLicenses     # 校验所有源码的 GPLv3 版权头
./gradlew applyLicenses     # 自动补全/修正版权头
```

> 本项目在 **Termux（on-device）** 环境下开发，`app/build.gradle` 中的
> `packaging.jniLibs.keepDebugSymbols` 是针对该环境的 `llvm-strip` 规避，请勿删除。
> 目前**没有单元/仪器测试**，也没有测试任务。

## 项目结构

```
app/src/main/
├── kotlin/com/grimdroid/
│   ├── MainActivity.kt          # 唯一 Activity：引导/主界面分流、Stellar 监听、C++ 调用
│   ├── data/                    # DataStore 持久化（设置项、监控快照）
│   ├── service/                 # 前台服务、系统监控、悬浮窗、强力模式、紧急清场、Stellar Shell
│   ├── receiver/                # 开机自启、应用安装/更新、紧急清场按钮
│   ├── worker/                  # WorkManager 周期看护
│   └── ui/                      # Compose UI：引导页、底部三 Tab、设置、弹窗
└── cpp/                         # libgrimdroid.so：JNI 演示函数 + 双哨兵守护进程
```

## 权限

| 权限 | 用途 |
| --- | --- |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 运行常驻前台防护服务 |
| `POST_NOTIFICATIONS` | 展示防护状态通知与降级提醒（Android 13+ 需运行时授予） |
| `RECEIVE_BOOT_COMPLETED` | 开机自启防护服务 |
| `SYSTEM_ALERT_WINDOW` | 弹悬浮窗提醒（需系统设置单独授予） |

> ⚠️ 已知缺口：代码使用了振动反馈（`EmergencyCleanup`），但 manifest **未声明 `VIBRATE` 权限**，
> 振动可能无效。详见 TECH_REPORT 的已知问题。

## 文档索引（交接用）

| 文档 | 内容 |
| --- | --- |
| [TECH_REPORT.md](TECH_REPORT.md) | **技术报告**：架构、逐功能说明、权限、已知问题与技术债、威胁模型、状态总结表 |
| [CLAUDE.md](CLAUDE.md) | 面向开发者的仓库约定：工具链、命令、许可流程、JNI/Stellar 集成、包结构 |
| [NOTICE](NOTICE) | 第三方架构参考的许可声明（youlong-security / AGPL-3.0，未复制源码） |
| [改动文件.md](改动文件.md) | 按轮次记录的历史改动日志 |
| [codeformat/HEADER](codeformat/HEADER) | GPLv3 版权头文本（licenser 的事实来源） |

## 许可

本项目采用 **GNU General Public License v3.0 or later（GPL-3.0-or-later）**。所有源文件必须
保留 `codeformat/HEADER` 中的版权头，由 Yumi Licenser 插件校验。详见 [LICENSE](LICENSE)。

## 致谢

GrimDroid 在设计上参考了以下项目的架构思路与经验教训：

- [Shizuku](https://github.com/RikkaApps/Shizuku)
- [Stellar](https://github.com/roro2239/Stellar)
- **youlong-security**（https://github.com/iill392/youlong-security），采用 AGPL-3.0 许可。

  参考的设计思路（**未复制任何源代码**）：

  * 哨兵进程互拉（mutual-watchdog）模式
  * 音量键监听进程的自动恢复策略
  * 悬浮窗权限丢失后的降级设计

  **重要说明**：本项目不包含来自 youlong-security 的任何源代码。GrimDroid 采用
  GPL-3.0-or-later 许可，与 AGPL-3.0 不兼容，仅参考了其公开文档中的架构思路。
  详见根目录 `NOTICE` 文件。
