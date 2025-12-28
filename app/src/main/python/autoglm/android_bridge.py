import os
import traceback
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
        # Never let callback crash the agent loop
        pass


def run_task(task: str, callback: Any = None, internal_adb_path: str | None = None) -> str:
    """Android entrypoint.

    Args:
        task: 用户任务文本
        callback: Kotlin 侧对象，支持 on_action/on_screenshot/on_assistant/on_error/on_done
        internal_adb_path: Android 私有目录下的 adb/adb.bin 路径

    Returns:
        最终文本结果（会同时通过 callback.on_done 推送一份）
    """

    try:
        if internal_adb_path:
            os.environ["INTERNAL_ADB_PATH"] = str(internal_adb_path)

        from phone_agent.adb import get_screenshot
        from phone_agent.adb.adb_path import set_internal_adb_path
        from phone_agent.agent import AgentConfig, PhoneAgent
        from phone_agent.model import ModelConfig

        if internal_adb_path:
            set_internal_adb_path(str(internal_adb_path))

        # 先截一张图，让用户实时看到 AI 在看什么
        _safe_call(callback, "on_action", "正在查阅屏幕")
        ss = get_screenshot(device_id=None)
        _safe_call(callback, "on_screenshot", ss.base64_data)

        _safe_call(callback, "on_action", "正在思考...")

        agent = PhoneAgent(
            model_config=ModelConfig(),
            agent_config=AgentConfig(max_steps=30, device_id=None, lang="cn", verbose=False),
        )

        # step 循环：每一步前都推送截图，让聊天流里能看到 AI 的观察过程
        first = True
        last_action_json = None
        for i in range(agent.agent_config.max_steps):
            if first:
                step = agent.step(task)
                first = False
            else:
                _safe_call(callback, "on_action", "正在查阅屏幕")
                ss = get_screenshot(device_id=None)
                _safe_call(callback, "on_screenshot", ss.base64_data)
                step = agent.step(None)

            if step.action is not None:
                try:
                    last_action_json = step.action
                    meta = step.action.get("_metadata")
                    action_name = step.action.get("action")
                    if meta == "finish":
                        msg = step.action.get("message") or "任务完成"
                        _safe_call(callback, "on_action", f"已完成：{msg}")
                    elif meta == "do":
                        _safe_call(callback, "on_action", f"执行动作：{action_name}")
                    else:
                        _safe_call(callback, "on_action", f"动作：{step.action}")
                except Exception:
                    _safe_call(callback, "on_action", "动作解析失败（已忽略）")

            if step.finished:
                final_msg = step.message or "任务完成"
                _safe_call(callback, "on_done", final_msg)
                return final_msg

        final_msg = "已达到最大步数限制，任务未完成"
        if last_action_json is not None:
            try:
                final_msg += f"\n最后动作：{last_action_json}"
            except Exception:
                pass
        _safe_call(callback, "on_done", final_msg)
        return final_msg

    except Exception as e:
        err = f"Python 任务异常：{e}"\
            f"\n{traceback.format_exc()}"
        _safe_call(callback, "on_error", err)
        _safe_call(callback, "on_done", "任务失败")
        return "任务失败"
