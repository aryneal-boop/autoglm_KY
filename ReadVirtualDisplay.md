# pingmutest 项目架构说明（Architecture）

> 本文件用于说明当前仓库的模块划分、关键路径、核心实现原理与关键代码入口，便于二次开发与排障。

## 1. 仓库结构总览

根目录主要包含：

- `app_VirtualDisplay/`
  - 当前 **Gradle 正式纳入构建** 的 Android App（见 `settings.gradle.kts` 仅 `include(":app")`）
  - 该模块是本仓库当前主要实验/验证入口（Jetpack Compose UI + Shizuku + VirtualDisplay/输入注入）

- `app-mirror/`
  - 历史/参考实现模块（包含更完整的“单应用投屏/虚拟屏交互/IME 路由”等链路，且带 NDK 代码）
  - **注意**：该模块当前不在根工程 `settings.gradle.kts` 的 `include(...)` 中，因此默认不会随根工程一起编译；但其源码对理解实现原理非常有参考价值。

- `fenxi.md`
  - 对 `app-mirror` 的实现链路分析文档（VirtualDisplay + 启动到 display + 输入注入/IME/路由）

- `settings.gradle.kts`
  - 目前仅 `include(":app")`

## 2. 模块说明

### 2.1 `:app`（当前主要模块）

- **路径**：`app_VirtualDisplay/`
- **包名/namespace**：`com.example.pingmutest`
- **技术栈**：
  - Jetpack Compose UI
  - Shizuku（`dev.rikka.shizuku:api/provider`）
  - Hidden API bypass（`org.lsposed.hiddenapibypass`）

#### 2.1.1 主要能力（当前实现）

- **创建 VirtualDisplay（通过 Shizuku 调系统服务）**
  - 关键类：`app_VirtualDisplay/src/main/java/com/example/pingmutest/display/ShizukuVirtualDisplayCreator.kt`
  - 关键点：创建时 flags 默认包含：
    - `VIRTUAL_DISPLAY_FLAG_PUBLIC`
    - `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`
    - `VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH`
    - 以及项目当前启用的其他 flags（按 Android 版本条件追加）

- **采集 VirtualDisplay 画面并在 UI 实时预览**
  - 关键类：`app_VirtualDisplay/src/main/java/com/example/pingmutest/display/VirtualDisplayFrameGrabber.kt`
  - 说明：通过 `ImageReader` / frame grab 的方式获取帧并在主界面做预览刷新。

- **启动目标应用到指定 displayId（Shizuku Shell / cmd/am 兼容）**
  - 关键类：`app_VirtualDisplay/src/main/java/com/example/pingmutest/system/ActivityLaunchUtils.kt`
  - 说明：
    - 先尝试带 windowing 参数的候选命令（不同 Android/ROM 对 `--windowing-mode`/`--windowingMode` 支持不一致）
    - 若系统不支持则自动降级为不带 windowing 参数重试

- **向指定 displayId 注入输入（tap/swipe/pinch/back）**
  - 关键类：`app_VirtualDisplay/src/main/java/com/example/pingmutest/display/TouchForwarder.kt`
  - 关键点：
    - 使用 `input -d <displayId> ...` 注入 tap/swipe/back
    - pinch 使用 `IInputManager.injectInputEvent`（反射调用）并设置 MotionEvent displayId

- **虚拟屏焦点策略（避免 IME/主屏抢焦点导致交互失效）**
  - 关键类：`app_VirtualDisplay/src/main/java/com/example/pingmutest/display/TouchForwarder.kt`
  - 关键方法：`ensureFocusedDisplay(displayId)`
    - 若系统存在 `setFocusedDisplay(int)`，会尝试将焦点拉回目标 display
    - 若存在 `getFocusedDisplayId()` 且已在目标 display，则跳过设置

- **启动界面（控制台/预览 UI）**
  - 关键文件：`app_VirtualDisplay/src/main/java/com/example/pingmutest/MainActivity.kt`
  - UI 结构：
    - 上方为虚拟屏预览区（约 70% 高度）
    - 下方为按钮区（约 30% 高度，支持滚动）
  - 预览区实现：
    - 使用 `AndroidView` + `FrameLayout(ImageView + overlay View)`
    - overlay View 透明且不获取焦点（`isFocusable=false`），用于承接触摸并转发给 `TouchForwarder`
  - 实时刷新策略：
    - 后台循环拉取最新 frame，绘制到复用的 `previewBitmap`
    - 通过 `frameSeq` 递增触发 Compose/AndroidView 更新，并主动 `invalidate()`
  - 资源释放/避免泄露：
    - `DisposableEffect` onDispose 中回收 `previewBitmap`、清空 ImageView bitmap
    - 停止 `VirtualDisplayFrameGrabber`（释放 ImageReader/线程等资源）

### 2.2 `app-mirror`（参考模块，不在根 Gradle include）

- **路径**：`app-mirror/`
- **包名/namespace**：`com.connect_screen.mirror`
- **定位**：更完整的“单应用投屏/镜像投屏/输入路由/IME 策略/系统服务封装”参考实现。

> 该模块的完整链路分析见根目录 `fenxi.md`。

#### 2.2.1 `fenxi.md` 中提到的关键链路（摘要）

- 创建 VirtualDisplay（Shizuku -> `IDisplayManager.createVirtualDisplay`）
- 将目标 App 启动到指定 display（`ActivityOptions.setLaunchDisplayId` 或 IActivityManager 走系统调用）
- 输入注入/路由（`IInputManager.injectInputEvent` + uniqueId/port association 的降级策略）
- IME 迁移（`IWindowManager.setDisplayImePolicy`）

## 3. 核心概念与实现原理（面向排障）

### 3.1 “单应用投屏”不是视频流

核心思想是 Android 多显示器：

- 创建一个新的 **VirtualDisplay**（拥有 `displayId`）
- 把 App 启动到该 `displayId`
- 通过 ImageReader 或 Surface 捕获该 display 的画面
- 把触摸/按键注入到该 display（并确保焦点在该 display）

因此 flags 与焦点策略很关键：

- `VIRTUAL_DISPLAY_FLAG_PUBLIC`：让系统更愿意把它当“真实的 display”参与窗口/任务分配
- `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`：更贴近“独立交互实体”的语义（只承载自己的内容）

### 3.2 为什么会“点主屏后虚拟屏丢焦点”

在 MIUI/部分系统中：

- IME/主屏交互会把 focused display 切回默认 display
- 即使事件注入到 virtual display，如果焦点域不在目标 display，交互可能异常

因此项目采用两层策略：

- **注入前补焦点**：每次注入 tap/swipe/pinch/back 前调用 `ensureFocusedDisplay(displayId)`
- **定时补焦点**：在 `MainActivity` 中针对当前 `displayId` 定时调用 `ensureFocusedDisplay(displayId)`（watchdog）

## 4. 关键代码索引（常用排查入口）

- **创建 VirtualDisplay（flags/版本分支）**
  - `app_VirtualDisplay/src/main/java/com/example/pingmutest/display/ShizukuVirtualDisplayCreator.kt`

- **抓帧与实时预览**
  - `app_VirtualDisplay/src/main/java/com/example/pingmutest/display/VirtualDisplayFrameGrabber.kt`
  - `app_VirtualDisplay/src/main/java/com/example/pingmutest/MainActivity.kt`

- **启动 App 到指定 display**
  - `app_VirtualDisplay/src/main/java/com/example/pingmutest/system/ActivityLaunchUtils.kt`

- **输入注入 + 焦点修正**
  - `app_VirtualDisplay/src/main/java/com/example/pingmutest/display/TouchForwarder.kt`

- **参考实现/完整链路分析**
  - `fenxi.md`
  - `app-mirror/...`（注意不在根 Gradle include）

## 5. 运行/调试建议

- 先确保 Shizuku 服务可用，并授予本 App Shizuku 权限。
- MIUI 设备上若 `AdbSecurityUtils.isUsbDebuggingSecurityEnabledOnMiui` 无法读取到开关，需检查“USB 调试（安全设置）”相关权限/设置项。
- 如果出现“画面刷新但无法交互/偶发失焦”，优先排查：
  - `TouchForwarder.ensureFocusedDisplay` 是否能成功调用 `setFocusedDisplay`
  - IME 弹出/切换时 focused display 是否被系统改写

