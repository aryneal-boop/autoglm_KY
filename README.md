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

## 4. 关键链路（从“用户输入”到“ADB 执行”）

- **文本指令**：`ChatActivity` -> `runPythonTask(...)` -> Python 侧解析/调用模型 -> 返回动作 -> Kotlin/ADB 执行
- **语音指令**：录音生成 wav -> `autoglm.bridge.speech_to_text(wavPath)` -> 得到文本 -> 同上
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
