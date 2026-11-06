# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

视界守卫者 —— 装在安卓电视/电视盒子上的儿童防沉迷应用。纯 Java + Android 框架 API，**运行时零第三方依赖**（Junit/Robolectric 仅测试期使用）。minSdk 21 / targetSdk 33 / compileSdk 34。

核心行为：连续观看 N 秒后自动弹全屏答题盖住画面 → 答对恢复播放；连错 M 题锁定 T 分钟；另有一条独立的"强制休息"链路。

## 常用命令

```bash
# 编译（产物 app/build/outputs/apk/debug/app-debug.apk）
./gradlew assembleDebug

# 正式签名版（app/build/outputs/apk/release/tvguard-v<versionName>.apk）
./gradlew assembleRelease

# 单元测试（当前只有 Robolectric 截图测试，见下方"已知问题"）
./gradlew :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests "com.parental.tvguard.ShotTest"   # 单个测试类

# 安装到已连接设备
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 可选强化：设为设备所有者后屏幕固定静默生效，主页键彻底失效
adb shell dpm set-device-owner com.parental.tvguard/.GuardDeviceAdminReceiver
```

SDK 路径写在 `local.properties`（`sdk.dir`），本机为 `D:/Auto/AI/TV/.sdk`。首次构建会下载 Gradle 8.6 与 AGP 8.2.2。

## 版本发布约定

每次发版在 `app/build.gradle` 里同时改 `versionCode`（+1）与 `versionName`；构建产物已统一命名为 `tvguard-v${versionName}.apk`（见 `applicationVariants` 块）。签名用根目录 `release.keystore`（alias `tvguard`，密码 `tvguard2024`，与 `signingConfigs.release` 中的配置一致），签名不一致将无法覆盖安装。

`.build-tmp/` 目录里是每个版本用完即弃的一次性 Python 补丁脚本（`patch_v1NN.py`），已 gitignore，不是工程代码。

## 架构总览

```
ScreenReceiver(开机/亮灭屏) → MonitorService(前台服务,1秒 tick 计时)
                                  │
                                  ├─ 到达间隔 → QuizActivity(全屏 Activity)  ← 主通道
                                  │              └─ QuizOverlayView(与悬浮窗共用)
                                  │            握手失败 → WindowManager 悬浮窗兜底
                                  │              └─ QuizOverlayView / LockOverlayView
                                  └─ 锁定中 → QuizActivity 或 LockOverlayView
AlarmReceiver(60秒看门狗) ──→ 唤醒进程 + 补执行逾期的界面切换
```

### 双通道阻断（最关键的设计）

弹题/锁定有**两条**实现路径，共享同一套 View 类：

| 通道 | 类 | 说明 |
|---|---|---|
| 主 | `QuizActivity` | 全屏不透明 Activity，`singleInstance` + `excludeFromRecents` + `showWhenLocked` |
| 兜底 | `MonitorService.showOverlay()` | `WindowManager` 的 `TYPE_APPLICATION_OVERLAY` 全屏悬浮窗 |

`QuizOverlayView` / `LockOverlayView` 是共用的 `FrameLayout` 子类，两个通道都能用；`QuizOverlayView.Listener` 的 `onPassed()` / `onMaxWrong()` 由各通道分别接到 `MonitorService.onQuizPassed()` / `MonitorService.startLock()`。

**握手协议**（改动这块时务必保持一致）：

1. 服务触发弹题 → 置 `Prefs.KEY_QUIZ_PENDING = 1` → `startActivity(QuizActivity)`。
2. `QuizActivity.onWindowFocusChanged(hasFocus)` 里才置 `MonitorService.quizShowing = hasFocus`。**后台被创建不算显示成功**——这是判断弹窗真正上屏的唯一依据。
3. 服务延迟 2.5 秒检查 `quizShowing`；若仍为 false，判定 Activity 未获焦点 → 挂悬浮窗兜底。
4. 悬浮窗成功接管后调用 `QuizActivity.finishRunning()` 关掉孤儿 Activity；`QuizActivity.onResume` 里若 `MonitorService.isOverlayActive()` 则自己 `finish()`。

### 计时与状态机

- `MonitorService.tick()` 每秒执行，只有在 **亮屏 && 无阻断界面 && `KEY_QUIZ_PENDING == 0` && 家长不在配置界面** 时才累加 `KEY_WATCHED`。
- tick 被 ROM 冻结导致 `delta > 1` 时会补偿计入（上限 300 秒），因为那段。孩子同样在看电视。
- `KEY_QUIZ_PENDING` 是"已弹题但未作答"的中心标志，所有自愈逻辑都围绕它。
- 触发弹题时**不**清零观看时间，只有弹窗真正上屏（Activity 获焦点或悬浮窗 `addView` 成功）才清零——否则弹窗被系统拦截时孩子白赚一整轮时间。
- 锁定用 `KEY_LOCK_UNTIL`（绝对时间戳），重启后仍有效。

### 自愈机制清单（分散在 `MonitorService.tick()` 里，改动需整体理解）

| 机制 | 触发条件 | 行为 |
|---|---|---|
| 看门狗闹钟 | 每 60 秒（`AlarmReceiver.setExactAndAllowWhileIdle`） | 唤醒被冻结的进程，拉起服务，补执行逾期的答题切换（`QuizOverlayView.recoverIfStuck()`） |
| 快速重新拉起 | `QuizActivity.onStop()` → `onQuizHidden()` | 0.6 秒内重新拉起阻断界面，不等秒级轮询 |
| 状态失真复位 | `quizShowing == true` 但 Activity 与悬浮窗都不存在 | 强制置 false |
| 弹窗 30 秒未显示 | `pendingSince` 超时 | 放弃本轮、恢复计时、半轮后重试，并通知家长去授权 |
| 悬浮窗被 ROM 压制 | 挂上后 90 秒无人点击 | 撤掉悬浮窗、恢复计时、稍后重试（华为/荣耀常见） |
| 屏幕固定 | `QuizActivity` 获焦点一次 | `startLockTask()`；设备所有者模式下静默生效，普通模式弹系统确认框 |

弹出被拦截时（`displayFailCount >= 3`）家长设置页会显示醒目卡片，并发一条高优先级通知（`notifyBlocked()`，id=2）引导去系统设置授权悬浮窗/后台弹出界面。

### 家长配置界面（`MainActivity`）

- 横屏左右双栏：左栏监控规则，右栏题库与状态。**所有 UI 用代码构建，没有 XML 布局**。
- 底部「完成 · 开始守护」按钮 = 保存并 `moveTaskToBack(true)` 退后台开始计时。
- 尺寸/字号统一走 `UiUtil.px(ctx, designPx)`，缩放系数 = 屏幕短边 / 1080（钳制在 0.4~3.0），保证 720p 电视到 2800×1280 高分手机都能完整显示。`UiUtil.TEXT_*` 是设计基准字号。
- 配色常量集中在 `MainActivity` 顶部（`BG` / `CARD` / `ACCENT` / `OK_GREEN` 等）。
- 家长密码保护：每次进入/回到本页都先弹 `PasswordActivity`；`onStop` 时若已不在任何配置界面则重新上锁（应用内子页面导航保持解锁）。
- 计时暂停：`MonitorService.enterConfigUi()` / `exitConfigUi()` 维护一个计数器，`BankActivity` / `StatsActivity` / `PasswordActivity` 也在 `onResume`/`onPause` 里成对调用。

### 题库

- 格式：`{ "version": 1, "questions": [ {"q": "...", "options": ["A","B"], "answer": 0} ] }`，UTF-8，`answer` 是**从 0 开始的下标**。
- 来源：assets 内嵌 `grade4_bank.json`（可用 `Prefs.KEY_EMBED_GRADE4` 停用）+ 用户导入的 `files/questions/*.json`。
- **题库级管理**（`BankActivity`）：每个题库可启用/禁用/删除。禁用列表存 `Prefs.KEY_DISABLED_BANKS`（逗号分隔文件名），`QuestionBank.load()` 跳过禁用的文件；禁用保留文件与统计，删除则连该题库的答题统计一并清除（`QuestionStats.removeBank`，按 id 前缀匹配）；删除按钮需二次确认（`pendingDeleteName`）。
- `QuestionBank.importBank(ctx, fileName, jsonText)` 校验全部题目后才落盘，文件名前加时间戳（`sanitizeName`），多次导入会共存、随机混抽。
- **题目 id = 来源名 + ":" + 文件内序号**（如 `四年级题库:12`）。`QuestionStats` 按这个 id 记录答对/答错次数，答对 5 次（`QuestionStats.RETIRE_AFTER`）该题"毕业"不再出现。
- `QuestionStats` 是**进程级单例**（`getInstance`），统计持久化在 `files/question_stats.json`，写入用临时文件 + rename 原子替换。
- 全部题目毕业后，答题界面变为家长密码输入框，密码正确则 `stats().resetAll()` 重置统计并重新加载。
- 出题规则：随机抽题 + 避开最近 5 题；未毕业题池为空则 `allGraduated = true`。

### 导入题库的两种方式

1. **U盘/本地**：`MainActivity.pickImportFile()` → `ACTION_OPEN_DOCUMENT` → `importFromUri()`（后台线程读流，剥 UTF-8 BOM）。
2. **局域网**：`LanServer` 用裸 `ServerSocket` 监听 8765 端口，手写解析 multipart（自己找 boundary / filename），浏览器访问 `http://<电视IP>:8765` 上传。是单例，`LanServer.get(ctx)`。

两条路径最终都调 `QuestionBank.importBank` + `MonitorService.reloadBank(ctx)`。

### 家长密码

`Prefs` 里存 `salt:hash` 格式的 SHA-256（`PasswordUtil.hash`，盐 + ":" + 明文 + ":tvguard"`）。明文不落盘，忘记只能卸载重装。

### 其他

- `QuizActivity` 拦截 BACK 键；`QuizOverlayView` / `LockOverlayView` 通过 `dispatchKeyEventPreIme` 拦截 BACK；悬浮窗额外拦截 MENU 键并 `requestFocus()` 夺取输入焦点。
- `SoundUtil` 用 `SoundPool` 播放 `res/raw/` 里的提示音（sound_correct / sound_wrong / sound_cheer，OGG 格式），音效来自 Kenney "Music Jingles"（CC0 公有领域；该包命名规律：奇数编号=下行/失败，偶数编号=上扬/成功）。`QuizOverlayView.TRANSITION_DELAY_MS`（2 秒）保证提示音播完后才切换界面。
- `GuardDeviceAdminReceiver` 是空实现，仅为支持可选的设备所有者模式。
- `HelpActivity` 是应用内的使用说明页（家长密码保护范围内）。

## 已知问题

**`ShotTest` 目前编译不过**：`app/src/test/java/com/parental/tvguard/ShotTest.java:69` 调用了 `main.renderPanels()`，但 `MainActivity.renderPanels()` 是 `private`（自 `f9c6f8c` 起一直是 private），`./gradlew :app:testDebugUnitTest` 会失败在 `compileDebugUnitTestJavaWithJavac`。要跑这个截图测试，需先把 `renderPanels()` 改成包级可见（或调整测试），以及处理 `buildDiagText()` 的可见性。

这个测试的价值在于**不接设备就能验证高分屏显示不全/文字被裁**：Robolectric 以 `GraphicsMode.NATIVE` 按真实分辨率渲染 `MainActivity` / `PasswordActivity` / `QuizActivity`，输出 PNG 到 `app/build/shots/` 并导出视图树布局（`dumpTree` 会标出超出屏幕的节点）。两个 `@Config` 分辨率：`w1280dp-h720dp-land-hdpi`（电视 1920×1080）和 `w933dp-h426dp-land-xxhdpi`（手机 2800×1280）。

## 约定

- 源码与注释统一用中文；每个 Java 文件头部保留 Apache-2.0 版权头（作者 WangQingshan / github.com/qingshanw）。
- 无第三方运行时依赖，不要引入（除非用户明确要求）。
- 新增界面元素前先确认它在 720p 与高分屏两种分辨率下都不越界——参考 `ShotTest` 的做法，尺寸一律走 `UiUtil.px()`，长内容用 `ScrollView` 包裹。
