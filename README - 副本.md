# AutoGLM Android（autoglm_test）

本仓库是一个 **Android App（Kotlin + Compose）**，内置 **Chaquopy(Python)** 运行环境，在手机端完成：
- 通过 **无线调试 ADB（mDNS 扫描 + adb pair/connect）** 连接到设备（常见场景是连接到本机/同机的 `127.0.0.1:PORT`）。
- 通过 **Python 侧 Agent（`phone_agent` / `autoglm`）** 调用 OpenAI 兼容接口的多模态模型服务，生成动作并驱动 ADB 执行。
- 提供一个对话 UI（`ChatActivity`）用于输入指令、展示模型/执行日志，并带有语音录入（录音 + Python 语音转写）。

> 面向 AI 新对话：优先阅读本 README 的「项目结构」「关键链路」「排障入口」三节。

## 1. 技术栈与关键约束

- **Android**
  - `compileSdk = 36` / `targetSdk = 36`
  - `minSdk = 30`
  - UI：Compose + 部分 XML Layout（`res/layout`）
- **Python（Chaquopy）**
  - Chaquopy 插件版本：`17.0.0`（见 `gradle/libs.versions.toml`）
  - Python 解释器版本：`3.13`（见 `app/build.gradle.kts` -> `chaquopy.defaultConfig.version`）
  - pip 依赖（节选）：`openai`、`httpx`、`requests`、`PyJWT`、`Pillow`
- **模型服务**
  - 走 OpenAI 兼容风格调用（具体在 Python 侧实现）
  - 运行时配置通过 Android 侧注入环境变量（见 `PythonConfigBridge`）
- **ADB**
  - 项目将 ADB 二进制放进 `app/src/main/assets/`（如 `adb.bin-arm64`、`adb-libs/`）
  - ADB 配对/连接由 App 内部逻辑执行（`PairingService`），并会扫描 mDNS：
    - `_adb-tls-pairing._tcp`（配对端口）
    - `_adb-tls-connect._tcp`（连接端口）

## 2. 运行方式（开发者视角）

### 2.1 构建/运行

- 用 Android Studio 打开根目录工程
- 直接运行 `app` 模块

主要 Gradle 文件：
- `settings.gradle.kts`：包含 Chaquopy Maven 源
- `gradle/libs.versions.toml`：统一版本
- `app/build.gradle.kts`：Android + Chaquopy + pip 依赖

### 2.2 App 内部使用流程（典型）

1. 启动 App -> 进入 `MainActivity`
2. 首次引导/权限校准：
   - 通知权限（Android 13+）
   - 悬浮窗权限（用于状态悬浮窗/麦克风入口等）
   - 电池白名单（避免后台被杀）
3. 引导用户进入系统开发者选项的无线调试界面，并通过通知栏输入 6 位配对码
4. `PairingService` 执行：扫描端口 -> `adb pair` -> 扫描连接端口 -> `adb connect` -> `adb devices` 校验
5. 成功后进入 `ChatActivity`：输入指令，触发 Python 侧任务执行

## 3. 项目结构（AI 快速导航）

> 这里的路径以仓库根目录为基准。

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── adb
│       │   ├── adb.bin-arm64
│       │   └── adb-libs/
│       ├── java/com/example/autoglm/
│       │   ├── AutoglmApp.kt
│       │   ├── MainActivity.kt
│       │   ├── ChatActivity.kt
│       │   ├── SettingsActivity.kt
│       │   ├── MonitorActivity.kt
│       │   ├── PairingService.kt
│       │   ├── PairingInputReceiver.kt
│       │   ├── LocalAdb.kt
│       │   ├── AdbPortScanner.kt
│       │   ├── AdbAutoConnectManager.kt
│       │   ├── AdbPermissionGranter.kt
│       │   ├── VirtualDisplayController.kt
│       │   ├── FloatingStatusService.kt
│       │   ├── TapIndicatorService.kt
│       │   ├── TaskControl.kt
│       │   ├── ConfigManager.kt
│       │   └── PythonConfigBridge.kt
│       ├── python/
│       │   ├── autoglm/
│       │   └── phone_agent/
│       └── res/
│           ├── layout/
│           ├── values/
│           └── xml/
├── gradle/
│   └── libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
```

### 3.1 Android 侧关键入口

- **Application**：`app/src/main/java/.../AutoglmApp.kt`
  - 负责初始化全局状态：`AppState.init`、`TaskControl.init`、`VirtualDisplayController` 等
- **启动入口 Activity**：`MainActivity.kt`
  - 引导/校准流程（权限、API 自检、ADB 自动连接）
  - 使用 Chaquopy 启动 Python 并调用 `phone_agent.model.client.test_api_connection`
- **对话与任务 Activity**：`ChatActivity.kt`
  - 聊天 UI（RecyclerView + Compose 输入条）
  - 语音录音 + Python 侧语音转写：调用 `autoglm.bridge.speech_to_text`
  - 执行任务前会校验 ADB 状态（`adb connect` + `adb devices`）
- **无线调试配对服务**：`PairingService.kt`
  - 通知栏 RemoteInput 输入 6 位配对码
  - 自动扫描配对端口与连接端口并重试
  - 成功后保存最近连接 endpoint（`ConfigManager.setLastAdbEndpoint`）

### 3.2 Kotlin -> Python 的配置注入

- `PythonConfigBridge.injectModelServiceConfig(context, py)`
  - 将 Android 侧配置写入 Python 的 `os.environ`：
    - `PHONE_AGENT_BASE_URL`
    - `PHONE_AGENT_MODEL`
    - `PHONE_AGENT_API_KEY`
    - `PHONE_AGENT_EXECUTION_ENV`
    - `PHONE_AGENT_DISPLAY_ID`
    - 以及 `ZHIPU_API_KEY`（复用同一份 key）

### 3.3 模块功能拆解（Android/Kotlin 侧）

> 本节是“按模块解释代码在做什么”，用于你要改代码/定位问题时快速找到责任归属。

- **[全局初始化与生命周期挂钩]**
  - `AutoglmApp.kt` / `AppState`
    - 保存全局 `applicationContext`，并在启动时调用 `TaskControl.init`。
    - 安装/重置与显示相关的全局观察者（见 `VirtualDisplayController.ensureProcessLifecycleObserverInstalled` / `hardResetOverlayAsync`）。
  - `TaskControl.kt`
    - 维护全局“任务是否运行/是否需要停止”的状态（`isTaskRunning` / `shouldStopTask`）。
    - 用单线程 `ExecutorService` 串行执行 Python 任务（避免并发的 ADB/Python 状态冲突）。
    - `stop()` / `forceStopPython()` 会尝试中断 Python 线程，并做 ADB/显示相关善后。

- **[配置与密钥管理]**
  - `ConfigManager.kt`
    - 使用 `EncryptedSharedPreferences` 保存模型服务配置（`baseUrl/apiKey/modelName`）。
    - 保存 ADB 最近一次连接信息（`LAST_ADB_ENDPOINT`）以及执行环境/连接模式（`EXECUTION_ENVIRONMENT`、`ADB_CONNECT_MODE`）。
  - `PythonConfigBridge.kt`
    - 将 `ConfigManager` 的配置同步到 Python `os.environ`。
    - 同时注入 `PHONE_AGENT_CONNECT_MODE`/`AUTOGM_CONNECT_MODE` 用于 Python 侧选择 ADB/Shizuku 等实现。

- **[本地 ADB 子系统（在手机上跑 adb）]**
  - `LocalAdb.kt`
    - 负责 ADB 二进制的定位/启动方式适配：
      - 优先使用 `filesDir/bin/adb` 的“脚本包装器”，统一通过 `/system/bin/sh` 启动，规避部分 ROM 的 `noexec`/`EACCES`。
      - 必要时通过系统 `linker64/linker` 启动 `adb.bin`。
    - 构造并注入运行环境变量（`HOME`/`TMPDIR`/`LD_LIBRARY_PATH`/`ADB_VENDOR_KEYS`）。
    - 承担命令执行与失败日志截断（用于排障时抓关键输出）。

- **[无线调试端口发现与自动连接]**
  - `AdbPortScanner.kt`
    - 用 NSD/mDNS 扫描 `_adb-tls-pairing._tcp.` 与 `_adb-tls-connect._tcp.`。
    - 为了提升 mDNS 发现成功率，会尝试获取 `WifiManager.MulticastLock`。
  - `AdbAutoConnectManager.kt`
    - 启动后优先使用历史 endpoint（`ConfigManager.getLastAdbEndpoint()`）快速重连。
    - 失败后退化为 mDNS 自动扫描 connect 端口，再执行 `adb connect` + `adb devices` 验证。
    - 连接成功后会触发 `AdbPermissionGranter.applyAdbPowerUserPermissions`（尽力自动授予常用权限）。

- **[通知栏配对/连接的前台服务]**
  - `PairingService.kt` + `PairingInputReceiver.kt`
    - 以前台服务形式承载配对流程，通知栏 `RemoteInput` 收集 6 位配对码。
    - 支持“端口变化/扫描失败”的重试策略：配对失败会刷新 `_adb-tls-pairing` 端口并重试，配对成功后再扫描 `_adb-tls-connect`。

- **[悬浮窗状态条与语音入口]**
  - `FloatingStatusService.kt`
    - 提供常驻前台服务 + 悬浮窗条：显示任务状态、提供“停止/开始任务”等入口。
    - 内置按住说话录音逻辑，录音后会通过 Chaquopy 调用 Python 侧语音转写（见 `autoglm.bridge.speech_to_text`）。
    - 通过广播与 `ChatActivity` 交互（例如 `ACTION_STOP_CLICKED`、`ACTION_START_TASK`）。

- **[Shizuku 通道（可选）]**
  - `ShizukuBridge.kt`
    - 通过 Shizuku 提供的 binder 启动 `sh -c` 命令并捕获 stdout/stderr。
    - Python 侧会在 `AUTOGM_CONNECT_MODE=SHIZUKU` 时走该通道执行 `input/screencap/am` 等命令（见下文 Python 模块）。

- **[聊天数据结构与 UI 适配层]**
  - `chat/`（如 `ChatAdapter`、`ChatEventBus`、`ChatMessage`、`MessageRole/Type`）
    - 承担“消息列表/流式追加/历史快照”的 UI 数据模型。
  - `ChatActivity.kt`
    - Kotlin 侧的主交互面：展示消息、接入悬浮窗广播、触发 Python 任务执行并把回调内容映射为聊天流。

### 3.4 模块功能拆解（Python/Chaquopy 侧）

- **[Android 侧入口：任务循环与回调协议]**
  - `autoglm/android_bridge.py`
    - `run_task(task, callback, internal_adb_path)` 是 Kotlin 调用的主入口。
    - 通过 `callback.on_action/on_screenshot/on_done/on_error` 将执行过程实时推回 Android UI。
    - 每一步都会先截屏（`phone_agent.adb.get_screenshot`）再让 Agent 决策，确保 UI 能看到“AI 在看什么”。

- **[语音转写]**
  - `autoglm/bridge.py`
    - `speech_to_text(audio_path)`：优先读取 `ZHIPU_API_KEY`，否则回退 `PHONE_AGENT_API_KEY`。
    - 可选对 wav 做归一化（由 `AUTOGLM_STT_NORMALIZE` 等环境变量控制）。

- **[模型调用（OpenAI 兼容）]**
  - `autoglm/phone_agent/model/client.py`
    - `ModelClient` 每次请求前会从 Android `ConfigManager`/环境变量刷新 `base_url/api_key/model_name`，保证“运行时改设置立即生效”。
    - 主要走 `chat.completions.create(stream=True)`，并按 `finish(message=...)` / `do(action=...)` 规则从流中切分 thinking 与 action。
    - `test_api_connection()` 是 Android 启动阶段用于自检连通性的入口（分别探测 `/models` 与 `/chat/completions`）。

- **[设备抽象与连接模式选择]**
  - `autoglm/phone_agent/device_factory.py`
    - 统一抽象 `tap/swipe/home/back/get_screenshot/...`，底层可切换 ADB/HDC/Shizuku。
  - `autoglm/phone_agent/adb/adb_path.py`
    - 通过 `INTERNAL_ADB_PATH` 支持 Android 私有目录内的 adb，并对脚本包装器统一用 `/system/bin/sh` 启动以规避 `PermissionError`。
  - `autoglm/phone_agent/shizuku.py`
    - 通过 `ShizukuBridge` 执行 `screencap/input/am/monkey` 等命令，提供截图与输入能力；并对“黑屏/敏感截图”做降级兜底。

## 4. 关键链路（从“用户输入”到“ADB 执行”）

- **文本指令**：~~`ChatActivity` -> `runPythonTask(...)` -> Python 侧解析/调用模型 -> 返回动作 -> Kotlin/ADB 执行~~
  - 实际实现：`ChatActivity` 触发 Chaquopy 调用 `autoglm/android_bridge.py::run_task`，由 Python 侧 `phone_agent` 直接通过 **内置 ADB/Shizuku 通道** 执行动作；Kotlin 侧主要负责 UI、权限/连接准备与把 Python 回调映射成聊天流。
- **语音指令**：~~录音生成 wav -> `autoglm.bridge.speech_to_text(wavPath)` -> 得到文本 -> 同上~~
  - 实际实现：Kotlin 录音生成 wav 后调用 `autoglm/bridge.py::speech_to_text` 转写为文本，再走与文本指令相同的 `run_task` 执行链路。
 - **ADB 可用性**：
   - 读取最近 endpoint（`ConfigManager.getLastAdbEndpoint`）
   - 执行 `adb connect <endpoint>`
   - 执行 `adb devices` 检查是否为 `device`

## 5. 常见排障入口（定位问题优先看这里）

- **Gradle/依赖问题**
  - `app/build.gradle.kts`（Chaquopy/pip 依赖在这里）
  - `gradle/libs.versions.toml`（统一版本）
- **ADB 连接/配对失败**
  - `PairingService.kt`（扫描 + 重试 + 通知提示）
  - `AdbPortScanner.kt` / `LocalAdb.kt`（mDNS/命令执行）
  - 确认系统无线调试已开启，且同一网络下可发现 mDNS 广播
- **模型 API 连接失败**
  - `MainActivity.kt` 中的 `test_api_connection`
  - `PythonConfigBridge.kt` 是否注入了正确的 `baseUrl/model/apiKey`
- **Python 运行异常**
  - Python 源码位于：`app/src/main/python/`
  - 重点模块：`autoglm.bridge`、`phone_agent.model.client`（具体实现以仓库实际代码为准）

## 6. 给新 AI 的协作提示（写代码时）

- 需要调整 Python 逻辑：优先在 `app/src/main/python/` 下改。
- 需要调整 ADB 配对/连接：优先看 `PairingService.kt`、`AdbPortScanner.kt`、`LocalAdb.kt`。
- 需要调整“模型服务配置/环境变量”：改 `ConfigManager` 和 `PythonConfigBridge`。
- 如果要改 SDK / Chaquopy / pip 依赖：先检查 `gradle/libs.versions.toml` 和 `app/build.gradle.kts` 是否会产生冲突。

## 7. 最新版架构总览（模块连接关系 / 引用路径）

> 本节用于“快速建立心智模型”：谁是入口、谁在管 ADB、谁在管虚拟隔离、Kotlin 与 Python 如何互相引用。

### 7.1 入口组件与生命周期

- **Application**：`app/src/main/java/com/example/autoglm/AutoglmApp.kt`
  - **初始化链路**：`AutoglmApp.onCreate` -> `AppState.init` -> `TaskControl.init` -> `VirtualDisplayController.ensureProcessLifecycleObserverInstalled` + `hardResetOverlayAsync`

- **LAUNCHER Activity**：`app/src/main/java/com/example/autoglm/MainActivity.kt`
  - **Python 初始化**：`Python.start(AndroidPlatform)` -> `PythonConfigBridge.injectModelServiceConfig`
  - **ADB 初始化**：`ensureLocalAdbReady`（内部会用 `LocalAdb` + `AdbAutoConnectManager`）
  - **流向**：校准完成后跳转到 `ChatActivity`

- **主交互 Activity**：`app/src/main/java/com/example/autoglm/ChatActivity.kt`
  - **任务入口**：`runPythonTask(...)`（通过 Chaquopy 调用 Python `phone_agent.android_bridge.run_task`）
  - **与悬浮窗交互**：接收/发送 `FloatingStatusService` 的 `ACTION_*` 广播
  - **人工介入**：订阅 `UserInterventionGate` 广播 + `ChatEventBus` 事件

- **悬浮窗前台服务**：`app/src/main/java/com/example/autoglm/FloatingStatusService.kt`
  - **语音入口**：录音 -> Python `autoglm/bridge.py::speech_to_text` -> 回到 `ChatActivity` 执行任务
  - **工具箱预览**：主屏预览 / 虚拟隔离预览（绑定 VirtualDisplay 输出 Surface）
  - **输入注入**：预览触摸层 -> `input/InputHelper` 或 `VirtualDisplayController.inject*` -> Shizuku IInputManager 注入
  - **IME 焦点死锁防护**：`vdiso/ImeFocusDeadlockController`（可选启用）

### 7.2 Kotlin -> Python（Chaquopy）调用与反向引用

- **Kotlin 注入配置到 Python 环境变量**
  - `app/src/main/java/com/example/autoglm/PythonConfigBridge.kt::injectModelServiceConfig`
  - 主要注入：
    - `PHONE_AGENT_BASE_URL` / `PHONE_AGENT_API_KEY` / `PHONE_AGENT_MODEL`
    - `PHONE_AGENT_EXECUTION_ENV`（MAIN_SCREEN / VIRTUAL_ISOLATED）
    - `PHONE_AGENT_CONNECT_MODE` / `AUTOGM_CONNECT_MODE`（WIRELESS_DEBUG / SHIZUKU）
    - `PHONE_AGENT_DISPLAY_ID`

- **Python 侧任务入口（推荐）**
  - `app/src/main/python/phone_agent/android_bridge.py::run_task`
  - 内部委托：`app/src/main/python/phone_agent/autoglm_handler.py::AutoGLMHandler.start_task`

- **Python 调用 Kotlin/系统能力（常见）**
  - 通过 Chaquopy `jclass(...)` 调用 Kotlin/Java 静态类（例如读取 `ConfigManager`）。
  - 通过 Kotlin 暴露的桥接能力间接调用系统服务：
    - Shizuku：`app/src/main/java/com/example/autoglm/ShizukuBridge.kt`
    - 虚拟隔离：`app/src/main/java/com/example/autoglm/VirtualDisplayController.kt`

### 7.3 本地 ADB 子系统链路（无线调试）

- **本地 ADB 运行环境**：`app/src/main/java/com/example/autoglm/LocalAdb.kt`
  - 关键点：脚本包装器 `/system/bin/sh` + 必要时使用 `linker64/linker` 启动 `adb.bin`

- **端口发现（mDNS/NSD）**：`app/src/main/java/com/example/autoglm/AdbPortScanner.kt`
  - `_adb-tls-pairing._tcp.`：配对端口
  - `_adb-tls-connect._tcp.`：连接端口

- **通知栏配对闭环**：
  - `PairingService`：`app/src/main/java/com/example/autoglm/PairingService.kt`
  - `PairingInputReceiver`：`app/src/main/java/com/example/autoglm/PairingInputReceiver.kt`
  - 配对成功后写入：`ConfigManager.setLastAdbEndpoint`

- **启动阶段自动连接**：`app/src/main/java/com/example/autoglm/AdbAutoConnectManager.kt`
  - 优先历史 endpoint，失败后扫描 connect 端口并重试

### 7.4 虚拟隔离（VirtualDisplay + Shizuku）架构链路

> 该链路用于“在独立 display 上运行/控制 UI”，并为模型持续提供虚拟屏截图。

- **门面入口**：`app/src/main/java/com/example/autoglm/VirtualDisplayController.kt`
  - `prepareForTask(context, adbExecPath)`：任务开始前确保虚拟屏存在
  - `screenshotPngBase64*`：提供给 Python 侧截图
  - `inject*BestEffort`：提供给 Python/Kotlin 的输入注入

- **虚拟屏引擎（核心）**：`app/src/main/java/com/example/autoglm/vdiso/ShizukuVirtualDisplayEngine.kt`
  - 创建 VirtualDisplay：通过 `vdiso/ShizukuServiceHub` 反射 `IDisplayManager.createVirtualDisplay`
  - 输出架构：默认输出到 GL 分发器离屏链路（用于截图）

- **GL 帧分发**：`app/src/main/java/com/example/autoglm/vdiso/VdGlFrameDispatcher.kt`
  - 输入：VirtualDisplay -> `SurfaceTexture`（OES 外部纹理）
  - 输出：
    - 预览 Surface（悬浮窗 TextureView/SurfaceView，0 bitmap）
    - ImageReader（离屏截图供识别）

- **IME 焦点死锁防护**：`app/src/main/java/com/example/autoglm/vdiso/ImeFocusDeadlockController.kt`
  - `dumpsys window windows` 解析 IME 激活状态
  - `wm set-focused-display <displayId>` 强制锁焦点（FORCE_LOCK）

### 7.5 输入注入链路（主屏/虚拟隔离）

- **虚拟屏输入注入（推荐）**：
  - Kotlin 门面：`VirtualDisplayController.injectTapBestEffort/injectSwipeBestEffort/...`
  - 底层：`app/src/main/java/com/example/autoglm/input/VirtualAsyncInputInjector.kt`
  - 机制：Shizuku -> `IInputManager.injectInputEvent`（签名自适配）

- **工具箱触摸层注入（高频 move 合并）**：
  - `app/src/main/java/com/example/autoglm/input/InputHelper.kt`：队列化触摸注入 + ensureFocusedDisplay 限频

- **点击反馈指示器**：`app/src/main/java/com/example/autoglm/TapIndicatorService.kt`
  - 主屏模式有历史坐标补偿，虚拟隔离模式不做偏移

### 7.6 人工介入链路（敏感操作确认/接管）

- **入口**：`app/src/main/java/com/example/autoglm/UserInterventionGate.kt`
  - `requestConfirmation`：阻塞等待用户同意/拒绝（必须后台线程调用）
  - `requestTakeover`：请求用户接管
  - 同步到：
    - `ChatEventBus.postInterventionRequest(...)`（进程内兜底）
    - 广播到 `ChatActivity`
    - `FloatingStatusService.setInterventionState(...)` 更新悬浮窗按钮

### 7.7 更新检查链路

- `app/src/main/java/com/example/autoglm/update/UpdateChecker.kt`：拉取更新 JSON 并解析
- `app/src/main/java/com/example/autoglm/update/UpdateInfo.kt`：更新信息模型
- 常见触发点：`ChatActivity` 启动后异步检查并决定是否弹窗

## 8. 代码不规范点（不影响使用的改进建议）

> 本节只给“合理的解决办法”，默认遵循 best-effort 风格，不强行大改以免影响现网使用。

### 8.1 过多的 `try/catch {}` 空吞异常

- **现状**：大量位置使用空 catch，优点是“不崩”，缺点是排障困难。
- **建议**：
  - 对关键链路（ADB/虚拟屏/权限注入/任务启动）至少 `Log.w(TAG, "...", t)`，并截断敏感信息。
  - 对“高频路径”保持轻量日志（例如每 N 秒一次），避免刷屏。

### 8.2 注释重复/风格不一致

- **现状**：个别文件可能同时存在旧注释与新 KDoc（例如 `TapIndicatorService`）。
- **建议**：
  - 后续可统一为“类头 KDoc + 必要的行内注释”，并逐步淘汰重复注释（需要单独的重构 PR，避免影响功能）。

### 8.3 可复用对象重复创建

- **现状**：例如 `PythonConfigBridge` 内部会创建多个 `ConfigManager(context)` 实例。
- **建议**：
  - 可改为单次创建并复用，减少 `EncryptedSharedPreferences` 初始化开销（属于安全的性能优化，不影响行为）。

### 8.4 Python/Android 版本约束不一致

- **现状**：项目实际 `minSdk=30`，Chaquopy Python 版本为 `3.13`；而 `.windsufrules copy` 中有“minSdk 21 / Python 3.10”的通用约束。
- **建议**：
  - 以仓库现有 Gradle 为准；若需要下调 minSdk 或固定 Python 版本，应单独评估依赖与 ROM 兼容性，避免引入运行时崩溃。
