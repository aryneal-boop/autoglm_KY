import os
from typing import List, Optional


INTERNAL_ADB_PATH: str = os.environ.get("INTERNAL_ADB_PATH") or "adb"


def set_internal_adb_path(path: str) -> None:
    global INTERNAL_ADB_PATH
    INTERNAL_ADB_PATH = path


def adb_prefix(device_id: Optional[str] = None) -> List[str]:
    base = INTERNAL_ADB_PATH
    # Android 上 filesDir 可能被挂载为 noexec，或脚本文件没有可执行权限。
    # 此时直接 exec 会报 PermissionError: [Errno 13]。
    # 统一用 /system/bin/sh 包装启动脚本，可绕过 exec 限制。
    try:
        base_argv = [base]
        if base and "/" in base and os.path.exists(base):
            # 只要它不是 ELF（二进制），就认为是脚本包装器，强制用 sh。
            # 这样不依赖 X_OK/noexec 的行为差异，彻底避免 PermissionError。
            try:
                with open(base, "rb") as f:
                    head = f.read(4)
                is_elf = head == b"\x7fELF"
            except Exception:
                is_elf = False

            if not is_elf:
                base_argv = ["/system/bin/sh", base]
    except Exception:
        base_argv = [base]

    if device_id:
        return base_argv + ["-s", device_id]
    return base_argv


def get_display_id() -> str | None:
    v = (os.environ.get("PHONE_AGENT_DISPLAY_ID") or "").strip()
    if not v:
        return None
    try:
        int(v)
        return v
    except Exception:
        return None
