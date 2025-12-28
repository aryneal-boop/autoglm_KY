from phone_agent.autoglm_handler import AutoGLMHandler


def run_task(task: str, callback=None, internal_adb_path: str | None = None) -> str:
    handler = AutoGLMHandler(callback=callback, internal_adb_path=internal_adb_path)
    return handler.start_task(task)


__all__ = ["run_task", "AutoGLMHandler"]
