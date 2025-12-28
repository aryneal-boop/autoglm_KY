from phone_agent.autoglm_handler import AutoGLMHandler


def run_task(task: str, callback=None, internal_adb_path: str | None = None) -> str:
    handler = AutoGLMHandler(callback=callback, internal_adb_path=internal_adb_path)
    return handler.start_task(task)


def reset_state() -> None:
    try:
        from phone_agent import device_factory

        device_factory._device_factory = None
    except Exception:
        pass

    try:
        from phone_agent.adb import adb_path

        if hasattr(adb_path, "INTERNAL_ADB_PATH"):
            adb_path.INTERNAL_ADB_PATH = None
    except Exception:
        pass


__all__ = ["run_task", "reset_state", "AutoGLMHandler"]
