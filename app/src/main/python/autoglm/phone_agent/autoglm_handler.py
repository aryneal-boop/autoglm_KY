import os
import random
import subprocess
import sys
import time
import traceback
from contextlib import contextmanager
from typing import Any

try:
    from java import jclass

    _TaskControl = jclass("com.example.autoglm.TaskControl")
except Exception:
    _TaskControl = None


def _safe_call(cb: Any, name: str, *args) -> None:
    try:
        if cb is None:
            return
        fn = getattr(cb, name, None)
        if fn is None:
            return
        fn(*args)
    except Exception:
        pass


class _CallbackWriter:
    """将 stdout/stderr 重定向到回调，用于捕获 AI 思考内容。"""
    def __init__(self, callback: Any, target: str = "on_action"):
        self.callback = callback
        self.target = target  # 目标回调方法名：on_action 或 on_assistant
        self._buf = ""

    def write(self, s: str) -> int:
        if not s:
            return 0
        self._buf += s
        while "\n" in self._buf:
            line, self._buf = self._buf.split("\n", 1)
            line = (line or "").strip()
            if line:
                # 根据目标回调发送，思考内容添加 [[THINK]] 前缀
                if self.target == "on_assistant":
                    _safe_call(self.callback, "on_assistant", "[[THINK]]" + line)
                else:
                    _safe_call(self.callback, "on_action", line)
        return len(s)

    def flush(self) -> None:
        buf = (self._buf or "").strip()
        if buf:
            if self.target == "on_assistant":
                _safe_call(self.callback, "on_assistant", "[[THINK]]" + buf)
            else:
                _safe_call(self.callback, "on_action", buf)
        self._buf = ""


@contextmanager
def _redirect_std_to_callback(callback: Any, target: str = "on_action"):
    """重定向 stdout/stderr 到指定回调。target 可以是 on_action 或 on_assistant。"""
    old_out, old_err = sys.stdout, sys.stderr
    w = _CallbackWriter(callback, target=target)
    try:
        sys.stdout = w
        sys.stderr = w
        yield
    finally:
        try:
            w.flush()
        except Exception:
            pass
        sys.stdout = old_out
        sys.stderr = old_err


class AutoGLMHandler:
    def __init__(self, callback: Any = None, internal_adb_path: str | None = None):
        self.callback = callback
        self.internal_adb_path = internal_adb_path

    def start_task(self, user_goal: str) -> str:
        user_goal = (user_goal or "").strip()
        if not user_goal:
            return "请输入任务目标。"

        def _should_continue() -> bool:
            try:
                if _TaskControl is None:
                    return True
                return bool(_TaskControl.shouldContinue())
            except Exception:
                return True

        def _sleep_interruptible(seconds: float) -> bool:
            """Sleep in small chunks. Return False if should stop."""
            try:
                remaining = float(seconds or 0.0)
            except Exception:
                remaining = 0.0
            step = 0.1
            while remaining > 0:
                if not _should_continue():
                    return False
                time.sleep(step if remaining > step else remaining)
                remaining -= step
            return _should_continue()

        try:
            connect_mode = (os.environ.get("AUTOGM_CONNECT_MODE") or os.environ.get("PHONE_AGENT_CONNECT_MODE") or "").strip().upper()
            is_shizuku_mode = connect_mode == "SHIZUKU"

            if self.internal_adb_path and not is_shizuku_mode:
                os.environ["INTERNAL_ADB_PATH"] = str(self.internal_adb_path)

            # 重要：尽早让 phone_agent.adb.adb_path 的全局 INTERNAL_ADB_PATH 生效，避免后续模块使用旧值。
            try:
                from phone_agent.adb.adb_path import set_internal_adb_path

                if self.internal_adb_path and not is_shizuku_mode:
                    set_internal_adb_path(str(self.internal_adb_path))
            except Exception:
                pass

            base_url = os.environ.get("PHONE_AGENT_BASE_URL")
            api_key = os.environ.get("PHONE_AGENT_API_KEY")
            try:
                from com.chaquo.python import Python as ChaquopyPython
                from java import jclass

                app = ChaquopyPython.getPlatform().getApplication()
                ConfigManager = jclass("com.example.autoglm.ConfigManager")
                cm = ConfigManager(app)
                cfg = cm.getConfig()
                base_url = str(cfg.getBaseUrl()) or base_url
                api_key = str(cfg.getApiKey()) or api_key
            except Exception:
                pass

            if not (api_key or "").strip() and "api.openai.com" in (base_url or ""):
                _safe_call(self.callback, "on_action", "提示：当前 Base URL 是 Open 官方接口，但 API Key 为空。")

            if is_shizuku_mode:
                try:
                    from java import jclass

                    ShizukuBridge = jclass("com.example.autoglm.ShizukuBridge")
                    if not bool(ShizukuBridge.pingBinder()) or not bool(ShizukuBridge.hasPermission()):
                        return "Shizuku 不可用：请先在启动界面完成 Shizuku 授权。"
                except Exception:
                    return "Shizuku 不可用：请先在启动界面完成 Shizuku 授权。"
            else:
                ok, adb_state_msg = self._check_adb_connected()
                if not ok:
                    return adb_state_msg

            from phone_agent.actions.handler import ActionHandler
            from phone_agent.actions.handler import finish, parse_action
            from phone_agent.config import get_system_prompt
            from phone_agent.device_factory import get_device_factory
            from phone_agent.device_factory import DeviceType, set_device_type
            from phone_agent.model import ModelClient, ModelConfig
            from phone_agent.model.client import MessageBuilder

            if is_shizuku_mode:
                try:
                    set_device_type(DeviceType.SHIZUKU)
                except Exception:
                    pass
            else:
                try:
                    set_device_type(DeviceType.ADB)
                except Exception:
                    pass

            model_client = ModelClient(ModelConfig())
            action_handler = ActionHandler(device_id=None)

            context: list[dict[str, Any]] = []
            step_count = 0
            max_steps = 50

            system_prompt = get_system_prompt("cn")
            context.append(MessageBuilder.create_system_message(system_prompt))

            while step_count < max_steps:
                if not _should_continue():
                    return "已停止"

                step_count += 1

                _safe_call(self.callback, "on_action", f"第 {step_count} 步：正在查阅屏幕")
                device_factory = get_device_factory()
                screenshot = device_factory.get_screenshot(device_id=None)
                _safe_call(self.callback, "on_screenshot", screenshot.base64_data)
                current_app = device_factory.get_current_app(device_id=None)
                screen_info = MessageBuilder.build_screen_info(current_app)

                if step_count == 1:
                    text_content = f"{user_goal}\n\n{screen_info}"
                else:
                    text_content = f"** Screen Info **\n\n{screen_info}"

                context.append(
                    MessageBuilder.create_user_message(
                        text=text_content,
                        image_base64=screenshot.base64_data,
                        image_mime=getattr(screenshot, "mime", None),
                    )
                )

                try:
                    _safe_call(self.callback, "on_action", "正在调用模型...")
                    # 将模型流式输出的思考内容重定向到 on_assistant 回调
                    with _redirect_std_to_callback(self.callback, target="on_assistant"):
                        response = model_client.request(context)
                except Exception as e:
                    msg = self._format_api_error(e)
                    _safe_call(self.callback, "on_error", msg)
                    return msg

                if not _should_continue():
                    return "已停止"

                try:
                    action = parse_action(response.action)
                except Exception:
                    action = finish(message=str(response.action))

                # 将操作描述发送到 on_action，使用 [[ACTION]] 前缀标识
                try:
                    meta = action.get("_metadata")
                    action_name = action.get("action", "")
                    if meta == "finish":
                        plan = action.get("message") or "任务完成"
                        # 完成也单独归类，便于 UI 拆分气泡。
                        _safe_call(self.callback, "on_action", f"[[ACTION:FINISH]]完成：{plan}")
                    elif meta == "do":
                        # 格式化操作描述，便于 UI 显示
                        action_desc = self._format_action_description(action)
                        # 输出动作类型，便于 Kotlin 侧按 kind 拆分（点击/滑动/等待等必须独立气泡）。
                        tag = (action_name or "OPERATION").strip().replace(" ", "_")
                        _safe_call(self.callback, "on_action", f"[[ACTION:{tag}]]{action_desc}")
                    else:
                        tag = (action_name or "OPERATION").strip().replace(" ", "_")
                        _safe_call(self.callback, "on_action", f"[[ACTION:{tag}]]{action}")
                except Exception:
                    _safe_call(self.callback, "on_action", "[[ACTION]]动作解析失败")

                context[-1] = MessageBuilder.remove_images_from_message(context[-1])

                # 如果是 Tap/Long Press/Double Tap 动作，显示点击指示器
                action_name = action.get("action", "")
                if action_name in ("Tap", "Long Press", "Double Tap"):
                    try:
                        # 坐标存储在 element 字段中，格式为 [x, y]（相对坐标 0-1000）
                        element = action.get("element")
                        if element and len(element) >= 2:
                            tap_x = float(element[0])
                            tap_y = float(element[1])
                            # 转换为屏幕像素坐标
                            screen_x = tap_x * screenshot.width / 1000.0
                            screen_y = tap_y * screenshot.height / 1000.0
                            _safe_call(self.callback, "on_tap_indicator", screen_x, screen_y)
                    except Exception:
                        pass  # 忽略指示器显示失败
                try:
                    result = action_handler.execute(action, screenshot.width, screenshot.height)
                except Exception as e:
                    _safe_call(self.callback, "on_error", f"动作执行异常：{e}")
                    result = action_handler.execute(
                        finish(message=str(e)), screenshot.width, screenshot.height
                    )

                if not _should_continue():
                    return "已停止"

                context.append(
                    MessageBuilder.create_assistant_message(
                        f"<think>{response.thinking}</think><answer>{response.action}</answer>"
                    )
                )

                finished = action.get("_metadata") == "finish" or result.should_finish
                if finished:
                    final_msg = result.message or action.get("message") or "任务完成"
                    _safe_call(self.callback, "on_done", final_msg)
                    return final_msg

                delay = random.uniform(1.0, 2.0)
                _safe_call(self.callback, "on_action", f"等待 {delay:.1f}s 后继续...")
                if not _sleep_interruptible(delay):
                    if not is_shizuku_mode:
                        # ADB 掉线检测（每步后检测一次）
                        from phone_agent.adb.connection import ADBConnection

                        if not ADBConnection().is_connected(device_id=None):
                            msg = "ADB 连接已断开：请重新连接设备后再继续。"
                            _safe_call(self.callback, "on_error", msg)
                            return msg

            msg = "已达到最大步数限制，任务仍未完成。请尝试缩短目标或手动接管。"
            _safe_call(self.callback, "on_done", msg)
            return msg

        except Exception as e:
            err = f"任务执行异常：{e}"
            _safe_call(self.callback, "on_error", err)
            _safe_call(self.callback, "on_action", traceback.format_exc())
            return "任务失败：发生未知错误，请查看日志。"

    @staticmethod
    def _format_action_description(action: dict) -> str:
        """格式化操作描述，便于 UI 显示。"""
        action_name = action.get("action", "")
        # 操作类型中英文映射
        action_names_cn = {
            "Launch": "启动应用",
            "Tap": "点击",
            "Type": "输入文本",
            "Swipe": "滑动",
            "Back": "返回",
            "Home": "返回桌面",
            "Long Press": "长按",
            "Double Tap": "双击",
            "Wait": "等待",
            "Take_over": "请求人工接管",
        }
        cn_name = action_names_cn.get(action_name, action_name)
        
        # 根据不同操作类型构建描述
        if action_name == "Launch":
            app = action.get("app", "")
            return f"{cn_name}：{app}" if app else cn_name
        elif action_name == "Tap":
            # 坐标存储在 element 字段中，格式为 [x, y]
            element = action.get("element", [])
            if element and len(element) >= 2:
                return f"{cn_name}：({element[0]}, {element[1]})"
            return cn_name
        elif action_name == "Type":
            text = action.get("text", "")
            # 截断过长文本
            if len(text) > 20:
                text = text[:20] + "..."
            return f"{cn_name}：{text}"
        elif action_name == "Swipe":
            # 滑动坐标存储在 start 和 end 字段中
            start = action.get("start", [])
            end = action.get("end", [])
            if start and end and len(start) >= 2 and len(end) >= 2:
                return f"{cn_name}：({start[0]},{start[1]}) → ({end[0]},{end[1]})"
            return cn_name
        elif action_name == "Long Press":
            element = action.get("element", [])
            if element and len(element) >= 2:
                return f"{cn_name}：({element[0]}, {element[1]})"
            return cn_name
        elif action_name == "Double Tap":
            element = action.get("element", [])
            if element and len(element) >= 2:
                return f"{cn_name}：({element[0]}, {element[1]})"
            return cn_name
        elif action_name == "Wait":
            duration = action.get("duration", "")
            return f"{cn_name}：{duration}" if duration else cn_name
        elif action_name == "Take_over":
            reason = action.get("message", "")
            return f"{cn_name}：{reason}" if reason else cn_name
        else:
            return cn_name if cn_name else str(action)

    @staticmethod
    def _format_api_error(e: Exception) -> str:
        text = f"{type(e).__name__}: {e}"
        lower = text.lower()
        if "timeout" in lower or "timed out" in lower:
            return "API 调用超时：请检查网络/Base URL 是否可达，或稍后重试。"
        if "401" in lower or "unauthorized" in lower:
            return "API 鉴权失败：请检查 API Key 是否正确。"
        if "404" in lower:
            return "API 地址不存在：请检查 Base URL 是否填写正确（是否需要 /v1）。"
        return "API 调用失败：" + text

    def _get_android_dirs(self) -> tuple[str | None, str | None]:
        """获取 Android 应用 filesDir/cacheDir，用于给 adb subprocess 设置 HOME/TMPDIR。"""
        try:
            from com.chaquo.python import Python as ChaquopyPython

            app = ChaquopyPython.getPlatform().getApplication()
            files_dir = str(app.getFilesDir().getAbsolutePath())
            cache_dir = str(app.getCacheDir().getAbsolutePath())
            return files_dir, cache_dir
        except Exception:
            return None, None

    def _check_adb_connected(self) -> tuple[bool, str]:
        """用 adb devices 作为权威判断，并返回更可诊断的中文信息。"""
        try:
            from phone_agent.adb.adb_path import adb_prefix

            files_dir, cache_dir = self._get_android_dirs()
            env = os.environ.copy()
            if files_dir:
                env["HOME"] = files_dir
            if cache_dir:
                env["TMPDIR"] = cache_dir

            r = subprocess.run(
                adb_prefix(device_id=None) + ["devices"],
                capture_output=True,
                text=True,
                timeout=8,
                env=env,
            )

            out = (r.stdout or "") + (r.stderr or "")
            out = out.strip()
            if out:
                _safe_call(self.callback, "on_action", "[adb devices]\n" + out)

            if r.returncode != 0:
                return False, "ADB 命令执行失败：请确认本地 adb 已正确释放且具备执行权限。"

            lines = out.splitlines()
            device_lines = []
            for ln in lines:
                ln = ln.strip()
                if not ln or ln.startswith("List of devices"):
                    continue
                device_lines.append(ln)

            parsed: list[tuple[str, str]] = []
            for ln in device_lines:
                parts = ln.split()
                if len(parts) >= 2:
                    parsed.append((parts[0], parts[1]))

            if any(state == "device" for _, state in parsed):
                return True, ""

            offline = next((dev for dev, state in parsed if state == "offline"), "")
            if offline:
                return False, f"ADB 已连接但设备离线（{offline}）：请在系统无线调试里重新连接/重新配对。"

            return False, "ADB 未连接：请先完成无线调试配对并连接设备，然后再开始任务。"
        except subprocess.TimeoutExpired:
            return False, "ADB 命令超时：可能 adb server 卡死或设备网络异常，请重试。"
        except Exception as e:
            return False, f"ADB 检测失败：{type(e).__name__}: {e}"
