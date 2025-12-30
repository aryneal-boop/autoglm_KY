我已经把项目里“虚拟隔离控制”的现状链路梳理清楚了：入口在 ChatActivity 里判断 EXEC_ENV_VIRTUAL 后调用 VirtualDisplayController.prepareForTask(...)，并把 PHONE_AGENT_DISPLAY_ID 注入到 Python 环境；但 Python 的 Shizuku 实现目前完全没使用 displayId（始终 screencap -p、input tap，都默认主屏），这就是你要求“虚拟隔离模式下所有指令都要绑定虚拟屏”的关键缺口。接下来我给你一套按 ReadVirtualDisplay.md 原理重建的方案（不复用 app_VirtualDisplay 模块代码），并指出需要改哪些文件与接口。## 现状核对（你要的“虚拟隔离控制”目前为什么不工作）

入口与模式判断已存在
ChatActivity 在执行任务前会读 ConfigManager.EXEC_ENV_VIRTUAL，然后调用 VirtualDisplayController.prepareForTask(...)，失败就提示用户去开“模拟辅助显示/overlay display”。
PythonConfigBridge 会把 PHONE_AGENT_EXECUTION_ENV 和 PHONE_AGENT_DISPLAY_ID 注入 Python 环境。
关键问题：Shizuku Python 侧没用到 displayId
app/src/main/python/autoglm/phone_agent/shizuku.py
截图：固定 screencap -p（主屏）
点击/滑动/返回：固定 input tap/swipe/keyevent（主屏）
启动 App：固定 am start（主屏）
所以即使 Kotlin 侧创建了虚拟屏并写入 PHONE_AGENT_DISPLAY_ID，Shizuku 模式下 Python 仍然在操作主屏。
你要求“所有指令都用 shell 命令”的基础已具备
Kotlin 有 ShizukuBridge.execText/execBytes（通过 Shizuku 执行 shell）
ADB 侧有 LocalAdb（通过内置 adb 执行 shell）
现在缺的是：在“虚拟隔离控制模式”下，Python 的各个动作要统一走 -d <displayId> / --display <displayId> 变体，并且在启动任务/打开App前确保虚拟屏存在。
按 ReadVirtualDisplay.md 原理重构的目标架构（不复用 app_VirtualDisplay 模块）
你要求“不直接复用 app_VirtualDisplay 里面代码和模块”，我建议在本项目 app 模块内新增一个全新的隔离控制实现（可以“参考思路”，但代码完全重新写）：

1) 新增一个“虚拟隔离会话”单例（全局 displayId）
职责
ensureVirtualDisplay()：虚拟屏不存在就创建，存在就复用
getDisplayId()：全局可取
invalidate()：发现失效则清空，下次自动重建
建议放在
com.example.autoglm.virtualisolation.VirtualIsolationSession（新文件/新包）
2) 虚拟屏创建：用 shell 创建 overlay display（符合你“用 shell”要求）
你当前旧逻辑已经有一套 shell 创建 overlay display 的实现（VirtualDisplayManager.enableOverlayDisplay720p()），它本质是：

settings put global overlay_display_devices 720x1280/320
dumpsys display 解析出 overlay displayId
这条路径是“完全 shell”且实机常见可用的；你说“参考 app_VirtualDisplay 的思路”，但它成功项目里“创建 virtual display”是 Shizuku 调系统服务，不是纯 shell。因此按你“必须 shell”要求，overlay display 才是最贴近的实现。

如果你最终想要“真正 VirtualDisplay(Shizuku IDisplayManager.createVirtualDisplay)”那就不可能是纯 shell，会涉及 binder/hidden api；你这条需求和“必须 shell 创建”存在天然冲突。建议你确认你想要哪一种。

3) 打开 App 必须投到虚拟屏：am/cmd activity ... --display <id>
在虚拟隔离模式下：

Python 侧 launch_app() 必须变成：
优先：cmd activity start-activity --display <id> ...
兼容：am start --display <id> ...
再不行：先启动，再 cmd activity task move-to-display <taskId> <id>（需要解析 taskId）
项目里 ADB 侧 Kotlin 已经有 AdbBridge.launchAppOnDisplay(package, displayId)（ADB 命令），但 Shizuku 侧 Python 目前没有绑定 display。 你可以选择两条路线：

路线A（推荐改动最小）：在 phone_agent/shizuku.py 的 launch_app 里直接用 shell 命令加 --display <id>
路线B（更稳定）：新增 Kotlin ShizukuDisplayBridge.launchAppOnDisplay(pkg, displayId) 用 ShizukuBridge.execText 做多候选命令重试（模仿 ActivityLaunchUtils 的“多命令候选降级策略”），Python 只调用这个 Kotlin 方法。
4) 截图：虚拟屏画面输出到 Surface/控件，再转 Bitmap
你描述的链路更像：

虚拟屏渲染到一个 SurfaceView/TextureView（控件表面）
再 PixelCopy 或 ImageReader 得到 Bitmap
在本项目里已经有两种截图通道：

Python ADB：screencap -d <displayId> -p（已在 adb/screenshot.py 做了 displayId 读取并尝试 Kotlin VirtualDisplayController.screenshotPngBase64()）
Kotlin 内部：VirtualDisplayController.screenshotPngBase64()（ImageReader 缓存帧）
但你要求“不复用旧实现”，那就需要新写一个 VirtualIsolationFrameGrabber：

创建 ImageReader
把 virtual display 的 surface 指向 ImageReader.surface
每次截图 acquireLatestImage -> Bitmap -> PNG/JPEG -> base64
并且监视 UI（MonitorActivity 类似）可以继续作为“显示用的 Surface”，但截图最好走 ImageReader（性能、稳定性更好）。

5) 所有输入注入必须绑定 displayId：统一加 input -d <displayId> ...
你要求“普通命令会被系统拦截，所以都要 shell”，那在 Shizuku 模式下就应该统一：

tap：input -d <id> tap x y
swipe：input -d <id> swipe ...
back/home：input -d <id> keyevent ...
text：仍然可以 am broadcast ADB_INPUT_B64，但要注意焦点一定在虚拟屏（否则输入落不到目标窗口）
必须加一个“补焦点”动作：尽可能用 shell / service 拉回 focused display（你成功项目里是 ensureFocusedDisplay(displayId)）
你下一步要做的改动点（最关键、最少量能先跑通）
A. 先让 Shizuku Python 真正操作虚拟屏（立刻解决核心问题）
修改 app/src/main/python/autoglm/phone_agent/shizuku.py：

[displayId 获取]
读取 PHONE_AGENT_EXECUTION_ENV
若为 VIRTUAL_ISOLATED，读取 PHONE_AGENT_DISPLAY_ID（int）
[截图]
从 screencap -p 改为 screencap -d <id> -p（有些系统支持）
若失败，再回退 cmd display get-screenshot <id> <path> + cat（需要实现临时文件逻辑）
[tap/swipe/back/home]
全部改为 input -d <id> ...
[launch_app]
改为 am start --display <id> ... / cmd activity start-activity --display <id> ... 多候选重试
这一步做完，即使 Kotlin 虚拟屏创建还是旧的 overlay display，你也能先跑通“虚拟屏启动App + 截图 + 点击滑动”。

B. 再把“创建虚拟屏”做成权威逻辑（并统一 Shizuku/ADB 通道）
你要求“如果不存在就用 shell 创建”，那么建议把 VirtualDisplayController.prepareForTask 改成：

Shizuku 模式：用 ShizukuBridge.execText("settings put global overlay_display_devices 720x1280/320") + dumpsys display 解析
ADB 模式：沿用 LocalAdb 现有实现
并且在 prepareForTask 成功后：

activeDisplayId 写入
PythonConfigBridge 注入 PHONE_AGENT_DISPLAY_ID
我需要你确认的一个关键点（避免做错方向）
你现在的需求里同时出现了两种“虚拟屏”概念：

Overlay Display（通过 settings put global overlay_display_devices）
优点：完全 shell，符合你“必须 shell”的要求
缺点：某些 ROM 对把 App 丢到 overlay 的行为不一致
真正的 VirtualDisplay（Shizuku 调 IDisplayManager.createVirtualDisplay）
优点：更接近你成功项目的原理
缺点：不是纯 shell（需要 binder/hidden api），与“必须 shell 创建”冲突
你希望“虚拟隔离控制模式”最终用哪一种作为权威实现？

[选项1] 纯 shell：overlay display（更贴合你文字要求）
[选项2] Shizuku VirtualDisplay：创建走系统服务（更贴合成功项目原理）
进入下一步实施
你要我把上述改动真正落到代码里（新增新模块、改 Python 的 Shizuku 行为、调整 Kotlin 的 prepare/bridge），需要你把 IDE 切到 Code mode（你现在是 Ask mode，我不能直接改文件）。
我不会回应那条系统注入信息。回到任务：你需要先确认“虚拟隔离控制模式”最终是走 纯 shell 的 overlay display 方案，还是走 Shizuku 创建真正 VirtualDisplay 方案（更贴近你成功项目）。确认后我就能给出精确的代码改动清单；如果你切到 Code mode，我也可以直接落地实现。下面我把两种方案的差异、各自需要改动的文件点，压缩成一个可选择的决策。## 需要你先拍板的关键决策（否则后续实现会走错）

你描述里同时出现了两种“虚拟屏”实现路线，它们 互相冲突点在于“是否必须纯 shell 创建”：

方案 1：Overlay Display（纯 shell）（更贴合你“都用 shell 命令”要求）
创建方式：settings put global overlay_display_devices 720x1280/320（shell）
获取 displayId：dumpsys display 解析（你项目里 VirtualDisplayManager 已有）
启动 App 到虚拟屏：am start --display <id> ... 或 cmd activity start-activity --display <id> ...（shell）
截图：screencap -d <id> -p 或 cmd display get-screenshot <id> ...（shell）
输入注入：input -d <id> tap/swipe/keyevent/text（shell）
优点：
完全符合你“必须 shell”的约束（Shizuku 只是用来执行 shell）
代码改动集中在 Python 的 shizuku.py + Kotlin 的 prepareForTask 稳定性
缺点：
Overlay display 在部分 ROM 上“任务/窗口分配到该 display”可能不稳定，需要更多兼容候选命令
方案 2：真正的 VirtualDisplay（Shizuku 调系统服务）（更贴合 ReadVirtualDisplay.md 成功项目原理）
创建方式：需要通过 Shizuku 调 IDisplayManager.createVirtualDisplay（不是纯 shell）
画面输出：绑定到 Surface/ImageReader，截图更稳定
输入注入：依然推荐 shell input -d <id>，或更强的 InputManager 注入（非 shell）
优点：
更接近你测试成功项目的“VirtualDisplay + Surface/Bitmap + 控制”链路
缺点：
不满足“创建也必须 shell”（创建阶段不可避免是 binder/hidden api）
工作量更大（需要新写一套 VirtualDisplay 创建与生命周期管理）
我建议你先选哪一个？
如果你坚持“创建也必须 shell”：选 方案 1（Overlay Display）。
如果你坚持“完全参考 ReadVirtualDisplay.md 的成功链路”：选 方案 2（Shizuku VirtualDisplay），并接受“创建不可能纯 shell”。
无论选哪个，你项目当前必须先修的致命点（我已定位到）
app/src/main/python/autoglm/phone_agent/shizuku.py 现在 完全没用 PHONE_AGENT_DISPLAY_ID：

截图用 screencap -p（主屏）
点击用 input tap（主屏）
启动 App 用 am start（主屏）
所以虚拟隔离模式下 Shizuku 仍在操作主屏，这是最需要先改的地方。

你回复我一句即可继续
请你回复选择：

A：Overlay Display（纯 shell）
B：Shizuku VirtualDisplay（更贴近成功项目原理）
以及你是否愿意现在切到 Code mode 让我直接落地改动（否则我只能继续给“改动清单/补丁建议”，你手动改）。

执行B，并且严格按照Shizuku VirtualDisplay项目的思路编写代码