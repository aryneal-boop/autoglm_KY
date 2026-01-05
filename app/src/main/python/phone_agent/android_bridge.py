from phone_agent.autoglm_handler import AutoGLMHandler


def run_task(task: str, callback=None, internal_adb_path: str | None = None) -> str:
    """Android 侧 Python 入口（phone_agent 版本）。

    **用途**
    - Kotlin/Chaquopy 通常调用该入口来执行任务。
    - 内部委托给 [AutoGLMHandler]，提供更完整的任务闭环（截图/模型调用/动作执行/回调流）。

    Args:
        task: 用户任务文本。
        callback: Kotlin 侧回调对象（on_action/on_screenshot/on_assistant/on_error/on_done）。
        internal_adb_path: Android 私有目录下 adb 路径（用于 Python 侧本地 ADB）。

    Returns:
        最终文本结果。
    """
    handler = AutoGLMHandler(callback=callback, internal_adb_path=internal_adb_path)
    return handler.start_task(task)


__all__ = ["run_task", "AutoGLMHandler"]
