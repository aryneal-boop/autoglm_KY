import os
import random
import sys
import time
import traceback
import io
from contextlib import contextmanager
from typing import Any


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
    def __init__(self, callback: Any):
        self.callback = callback
        self._buf = ""

    def write(self, s: str) -> int:
        if not s:
            return 0
        self._buf += s
        while "\n" in self._buf:
            line, self._buf = self._buf.split("\n", 1)
            line = (line or "").strip()
            if line:
                _safe_call(self.callback, "on_action", line)
        return len(s)

    def flush(self) -> None:
        buf = (self._buf or "").strip()
        if buf:
            _safe_call(self.callback, "on_action", buf)
        self._buf = ""


@contextmanager
def _redirect_std_to_callback(callback: Any):
    old_out, old_err = sys.stdout, sys.stderr
    w = _CallbackWriter(callback)
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


@contextmanager
def _capture_std() -> Any:
    old_out, old_err = sys.stdout, sys.stderr
    buf = io.StringIO()
    try:
        sys.stdout = buf
        sys.stderr = buf
        yield buf
    finally:
        sys.stdout = old_out
        sys.stderr = old_err


class _AssistantStreamWriter:
    def __init__(self, callback: Any, flush_threshold: int = 32):
        self.callback = callback
        self._buf = ""
        self._flush_threshold = flush_threshold

    def write(self, s: str) -> int:
        if not s:
            return 0
        # 直接把模型的流式输出转发给 UI 的 assistant 区域。
        # 这里做轻量缓冲，避免每次 write 都触发一次 UI 更新（部分库可能会非常碎片化写入）。
        self._buf += s
        if "\n" in self._buf or len(self._buf) >= self._flush_threshold:
            self.flush()
        return len(s)

    def flush(self) -> None:
        if not self._buf:
            return
        _safe_call(self.callback, "on_assistant", self._buf)
        self._buf = ""


@contextmanager
def _redirect_std_to_assistant(callback: Any):
    old_out, old_err = sys.stdout, sys.stderr
    w = _AssistantStreamWriter(callback)
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
        """AutoGLM Android 任务执行器（Python 侧）。

        **用途**
        - 作为 Android 端任务执行的核心闭环控制器：
          - ADB/连接状态检查
          - 截图 -> 模型决策 -> 动作执行 的循环
          - 将过程通过 callback 实时推送回 Kotlin UI

        Args:
            callback: Kotlin 侧回调对象。
            internal_adb_path: Android 私有目录下 adb 路径（用于 Python 侧走本地 ADB）。

        使用注意事项:
        - 该类必须保证“不崩溃宿主”：所有异常都应被捕获并回传到 UI。
        - 回调必须安全：不要让 callback 的异常影响主循环（见 _safe_call）。
        """
        self.callback = callback
        self.internal_adb_path = internal_adb_path

    def start_task(self, user_goal: str) -> str:
        """入口函数：启动任务并执行闭环。

        Args:
            user_goal: 用户任务目标文本。

        Returns:
            最终输出文本（也会通过 callback.on_done 推送）。
        """

        user_goal = (user_goal or "").strip()
        if not user_goal:
            return "请输入任务目标。"

        try:
            if self.internal_adb_path:
                os.environ["INTERNAL_ADB_PATH"] = str(self.internal_adb_path)

            # 读取 Android 侧配置（API Key / base_url / model），ModelClient 内部也会刷新，
            # 这里读取一次用于做友好错误提示。
            base_url = os.environ.get("PHONE_AGENT_BASE_URL")
            api_key = os.environ.get("PHONE_AGENT_API_KEY")
            model_name = os.environ.get("PHONE_AGENT_MODEL")
            try:
                from com.chaquo.python import Python as ChaquopyPython
                from java import jclass

                app = ChaquopyPython.getPlatform().getApplication()
                ConfigManager = jclass("com.example.autoglm.ConfigManager")
                cm = ConfigManager(app)
                cfg = cm.getConfig()
                base_url = str(cfg.getBaseUrl()) or base_url
                api_key = str(cfg.getApiKey()) or api_key
                model_name = str(cfg.getModelName()) or model_name
            except Exception:
                pass

            if not (api_key or "").strip() and "api.openai.com" in (base_url or ""):
                _safe_call(self.callback, "on_action", "提示：当前 Base URL 是 Open 官方接口，但 API Key 为空。")

            # ADB 掉线检查：确保至少有一个 device
            from phone_agent.adb.connection import ADBConnection

            if not ADBConnection().is_connected(device_id=None):
                return "ADB 未连接：请先完成无线调试配对并连接设备，然后再开始任务。"

            from phone_agent.adb import get_screenshot
            from phone_agent.adb.adb_path import set_internal_adb_path
            from phone_agent.actions.handler import ActionHandler
            from phone_agent.actions.handler import finish, parse_action
            from phone_agent.config import get_system_prompt
            from phone_agent.device_factory import get_device_factory
            from phone_agent.model import ModelClient, ModelConfig
            from phone_agent.model.client import MessageBuilder

            if self.internal_adb_path:
                set_internal_adb_path(str(self.internal_adb_path))

            model_client = ModelClient(ModelConfig())
            action_handler = ActionHandler(device_id=None)

            context: list[dict[str, Any]] = []
            step_count = 0
            max_steps = 50

            system_prompt = get_system_prompt("cn")
            context.append(MessageBuilder.create_system_message(system_prompt))

            def _kotlin_should_continue() -> bool:
                try:
                    from java import jclass

                    TaskControl = jclass("com.example.autoglm.TaskControl")
                    return bool(TaskControl.shouldContinue())
                except Exception:
                    return True

            def _cleanup_adb_best_effort() -> None:
                try:
                    if self.internal_adb_path:
                        os.environ["INTERNAL_ADB_PATH"] = str(self.internal_adb_path)
                    from phone_agent.adb.connection import ADBConnection

                    ADBConnection().restart_server()
                except Exception:
                    pass

            try:
                while step_count < max_steps:
                    if not _kotlin_should_continue():
                        raise RuntimeError("任务已停止")

                    step_count += 1

                    # 每一步开始前：截图 + 回传截图
                    _safe_call(self.callback, "on_action", "[[STATUS]]正在查阅屏幕")
                    screenshot = get_screenshot(device_id=None)
                    _safe_call(self.callback, "on_screenshot", screenshot.base64_data)

                    device_factory = get_device_factory()
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

                    # 调模型：把 client.py 内部 print 输出转为 callback 消息
                    try:
                        if not _kotlin_should_continue():
                            raise RuntimeError("任务已停止")
                        _safe_call(self.callback, "on_action", "[[STATUS]]正在调用模型...")
                        with _capture_std():
                            response = model_client.request(context)
                    except Exception as e:
                        msg = self._format_api_error(e)
                        _safe_call(self.callback, "on_error", msg)
                        return msg

                    # 解析计划动作
                    try:
                        action = parse_action(response.action)
                    except Exception:
                        action = finish(message=str(response.action))

                    try:
                        meta = action.get("_metadata")
                        if meta == "finish":
                            thinking = (response.thinking or "").strip()
                            if thinking:
                                _safe_call(self.callback, "on_assistant", "[[THINK]]" + thinking)
                            try:
                                plan = (action.get("message") or "").strip() or "任务完成"
                                _safe_call(self.callback, "on_action", f"[[PLAN]]计划动作：完成（{plan}）")
                            except Exception:
                                pass

                            final_msg = (action.get("message") or "").strip() or "任务完成"
                            _safe_call(self.callback, "on_done", final_msg)
                            return final_msg
                    except Exception:
                        pass

                    # 回传“计划动作”
                    try:
                        meta = action.get("_metadata")
                        if meta == "finish":
                            plan = action.get("message") or "任务完成"
                            _safe_call(self.callback, "on_action", f"计划动作：完成（{plan}）")
                        elif meta == "do":
                            _safe_call(
                                self.callback,
                                "on_action",
                                f"计划动作：{action.get('action')} {action}",
                            )
                        else:
                            _safe_call(self.callback, "on_action", f"计划动作：{action}")
                    except Exception:
                        _safe_call(self.callback, "on_action", "计划动作解析失败（已忽略）")

                    # 为节省上下文：移除图片
                    context[-1] = MessageBuilder.remove_images_from_message(context[-1])

                    # 执行动作（调用本地 adb_bin）
                    try:
                        if not _kotlin_should_continue():
                            raise RuntimeError("任务已停止")
                        result = action_handler.execute(action, screenshot.width, screenshot.height)
                    except Exception as e:
                        _safe_call(self.callback, "on_error", f"动作执行异常：{e}")
                        result = action_handler.execute(
                            finish(message=str(e)), screenshot.width, screenshot.height
                        )

                    # 记录 assistant 回答进上下文
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

                    # 等待后进入下一轮
                    delay = random.uniform(1.0, 2.0)
                    _safe_call(self.callback, "on_action", f"等待 {delay:.1f}s 后继续...")
                    slept = 0.0
                    while slept < delay:
                        if not _kotlin_should_continue():
                            raise RuntimeError("任务已停止")
                        step = min(0.2, delay - slept)
                        time.sleep(step)
                        slept += step

                    # ADB 掉线检测（每步后检测一次）
                    if not ADBConnection().is_connected(device_id=None):
                        msg = "ADB 连接已断开：请重新连接设备后再继续。"
                        _safe_call(self.callback, "on_error", msg)
                        return msg

                msg = "已达到最大步数限制，任务仍未完成。请尝试缩短目标或手动接管。"
                _safe_call(self.callback, "on_done", msg)
                return msg
            except Exception as e:
                if str(e) == "任务已停止":
                    _safe_call(self.callback, "on_done", "任务已停止")
                    return "任务已停止"
                raise
            finally:
                _cleanup_adb_best_effort()

        except Exception as e:
            err = f"任务执行异常：{e}"
            _safe_call(self.callback, "on_error", err)
            _safe_call(self.callback, "on_action", traceback.format_exc())
            return "任务失败：发生未知错误，请查看日志。"

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
