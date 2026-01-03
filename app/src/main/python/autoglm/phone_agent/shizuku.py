"""Shizuku-based device operations for Android automation.

This module is used when AUTOGM_CONNECT_MODE=SHIZUKU.
It executes shell commands via Kotlin ShizukuBridge (rikka.shizuku).
"""

from __future__ import annotations

import base64
import os
import re
import time
from dataclasses import dataclass
from io import BytesIO

from PIL import Image
from phone_agent.config.apps import APP_PACKAGES


def _get_bridge():
    from java import jclass

    return jclass("com.example.autoglm.ShizukuBridge")


def _exec_bytes(cmd: str) -> bytes:
    b = _get_bridge().execBytes(cmd)
    try:
        return bytes(b)
    except Exception:
        # In some Chaquopy versions, jbyte[] already behaves like bytes
        try:
            return b  # type: ignore
        except Exception:
            return b""


def _exec_text(cmd: str) -> str:
    return str(_get_bridge().execText(cmd) or "")


def _resolve_package(app_or_pkg: str) -> str:
    q = (app_or_pkg or "").strip()
    if not q:
        return ""
    try:
        from phone_agent.app_package_resolver import resolve_package, is_package_installed

        pkg = resolve_package(q)
        if not pkg:
            return ""
        # 仅在能拿到包列表时校验，避免无权限/异常时误判。
        try:
            if is_package_installed(pkg):
                return pkg
        except Exception:
            return pkg
        return ""
    except Exception:
        if "." in q:
            return q
        return APP_PACKAGES.get(q, "")


def _is_virtual_isolated_mode() -> bool:
    return (os.environ.get("PHONE_AGENT_EXECUTION_ENV") or "").strip().upper() == "VIRTUAL_ISOLATED"


def _get_virtual_display_id() -> int | None:
    v = (os.environ.get("PHONE_AGENT_DISPLAY_ID") or "").strip()
    if not v:
        return None
    try:
        return int(v)
    except Exception:
        return None


def _ensure_virtual_display_started() -> int | None:
    if not _is_virtual_isolated_mode():
        return None
    try:
        from java import jclass

        Vdc = jclass("com.example.autoglm.VirtualDisplayController")
        try:
            from com.chaquo.python import Python as ChaquopyPython

            app = ChaquopyPython.getPlatform().getApplication()
            # adbExecPath 参数在新的 Shizuku VirtualDisplay 实现中不再使用，传空即可。
            Vdc.prepareForTask(app, "")
        except Exception:
            pass

        did = Vdc.getDisplayId()
        if did:
            try:
                return int(did)
            except Exception:
                pass
    except Exception:
        pass
    return _get_virtual_display_id()


def _ensure_virtual_display_focused_best_effort(display_id: int | None) -> None:
    did = int(display_id) if display_id is not None else 0
    if did <= 0:
        return
    if not _is_virtual_isolated_mode():
        return
    try:
        from java import jclass

        Vdc = jclass("com.example.autoglm.VirtualDisplayController")
        try:
            Vdc.ensureFocusedDisplayBestEffort()
            return
        except Exception:
            pass
    except Exception:
        pass

    try:
        _exec_text(f"cmd input set-focused-display {int(did)}")
        return
    except Exception:
        pass
    try:
        _exec_text(f"wm set-focused-display {int(did)}")
    except Exception:
        pass


def _find_task_id_for_package_from_dumpsys(text: str, package_name: str) -> int | None:
    if not text or not package_name:
        return None
    pkg = package_name.strip().lower()
    if not pkg:
        return None

    task_id: int | None = None
    matched_pkg_in_block = False
    best: int | None = None

    task_id_pattern = re.compile(r"\btaskId=(\d+)\b")
    task_hash_pattern = re.compile(r"Task\{[^}]*#(\d+)")

    def flush():
        nonlocal task_id, matched_pkg_in_block, best
        if task_id is not None and matched_pkg_in_block:
            best = task_id
        task_id = None
        matched_pkg_in_block = False

    for raw in (text.splitlines() if text else []):
        line = (raw or "").strip()

        m = task_id_pattern.search(line)
        if m:
            flush()
            try:
                task_id = int(m.group(1))
            except Exception:
                task_id = None

        if task_id is None:
            m2 = task_hash_pattern.search(line)
            if m2:
                flush()
                try:
                    task_id = int(m2.group(1))
                except Exception:
                    task_id = None

        if task_id is not None:
            l = line.lower()
            if f"{pkg}/" in l:
                matched_pkg_in_block = True

    flush()
    return best


def _try_move_existing_task_to_display(pkg: str, display_id: int) -> bool:
    try:
        text = _exec_text("dumpsys activity activities")
    except Exception:
        return False

    task_id = _find_task_id_for_package_from_dumpsys(text or "", pkg)
    if task_id is None:
        return False

    candidates = [
        f"cmd activity task move-to-display {task_id} {int(display_id)}",
        f"cmd activity task move-task-to-display {task_id} {int(display_id)}",
        f"cmd activity move-task {task_id} {int(display_id)}",
        f"am task move-to-display {task_id} {int(display_id)}",
    ]
    ok = False
    for c in candidates:
        try:
            _exec_text(c)
            ok = True
            break
        except Exception:
            continue
    return ok


def _resolve_launcher_component(pkg: str) -> str:
    """Best-effort resolve LAUNCHER Activity component (pkg/cls).

    Keep this best-effort and non-fatal: on failure, caller should fall back to old behavior.
    """
    p = (pkg or "").strip()
    if not p:
        return ""

    # Prefer cmd package resolve-activity (Android 9+ typically).
    candidates = [
        f"cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER {p}",
        f"cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER {p}",
        f"cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER",
    ]
    for c in candidates:
        try:
            out = (_exec_text(c) or "").strip()
            if not out:
                continue
            # Try common formats: last token may be component.
            for raw in out.splitlines():
                line = (raw or "").strip()
                if not line:
                    continue
                if "/" in line and p in line:
                    # Sometimes prints "pkg/cls" or "name: pkg/cls".
                    m = re.search(r"([\w.]+/[\w.$]+)", line)
                    if m:
                        comp = m.group(1)
                        if comp.startswith(p + "/"):
                            return comp
        except Exception:
            continue
    return ""


def _is_likely_black_image(img: Image.Image) -> bool:
    try:
        rgb = img.convert("RGB")
        w, h = rgb.size
        tw, th = min(64, w), min(64, h)
        thumb = rgb.resize((tw, th), resample=Image.BILINEAR)
        pixels = thumb.getdata()
        non_black = 0
        for r, g, b in pixels:
            if r > 10 or g > 10 or b > 10:
                non_black += 1
                if non_black >= 20:
                    return False
        return True
    except Exception:
        return True


def _encode_jpeg_to_target(img: Image.Image, target_bytes: int = 25 * 1024) -> tuple[bytes, str]:
    def _to_rgb(im: Image.Image) -> Image.Image:
        if im.mode in ("RGB",):
            return im
        if im.mode in ("RGBA", "LA"):
            bg = Image.new("RGB", im.size, (0, 0, 0))
            bg.paste(im, mask=im.split()[-1])
            return bg
        return im.convert("RGB")

    lo, hi = 25, 85
    best = None
    for q in (85, 70, 55, 45, 35, 25):
        buf = BytesIO()
        _to_rgb(img).save(buf, format="JPEG", quality=int(q), optimize=True, progressive=True)
        b = buf.getvalue()
        best = b
        if len(b) <= target_bytes:
            break
    return best or b"", "image/jpeg"


@dataclass
class Screenshot:
    base64_data: str
    width: int
    height: int
    mime: str = "image/jpeg"
    is_sensitive: bool = False


def get_screenshot(device_id=None, timeout: int = 10) -> Screenshot:
    # timeout kept for signature compatibility
    _ = device_id
    _ = timeout

    did = _ensure_virtual_display_started()

    # 虚拟隔离模式优先走 Kotlin VirtualDisplayController（ImageReader 抓帧），避免系统拦截/黑屏。
    if did is not None:
        try:
            from java import jclass

            Vdc = jclass("com.example.autoglm.VirtualDisplayController")
            b64 = str(Vdc.screenshotPngBase64() or "")
            if b64:
                png_bytes = base64.b64decode(b64)
                if png_bytes and len(png_bytes) >= 2048:
                    img = Image.open(BytesIO(png_bytes))
                    if not _is_likely_black_image(img):
                        w, h = img.size
                        jpeg, mime = _encode_jpeg_to_target(img)
                        b64jpg = base64.b64encode(jpeg).decode("utf-8")
                        return Screenshot(base64_data=b64jpg, width=w, height=h, mime=mime, is_sensitive=False)
        except Exception:
            pass

    # shell fallback
    if did is not None:
        png = _exec_bytes(f"screencap -d {int(did)} -p")
    else:
        png = _exec_bytes("screencap -p")
    if not png or len(png) < 2048:
        return _fallback_screenshot(is_sensitive=True)

    try:
        img = Image.open(BytesIO(png))
        if _is_likely_black_image(img):
            return _fallback_screenshot(is_sensitive=True)
        w, h = img.size
        jpeg, mime = _encode_jpeg_to_target(img)
        b64 = base64.b64encode(jpeg).decode("utf-8")
        return Screenshot(base64_data=b64, width=w, height=h, mime=mime, is_sensitive=False)
    except Exception:
        return _fallback_screenshot(is_sensitive=False)


def _fallback_screenshot(is_sensitive: bool) -> Screenshot:
    w, h = 1080, 2400
    img = Image.new("RGB", (w, h), color="black")
    jpeg, mime = _encode_jpeg_to_target(img)
    b64 = base64.b64encode(jpeg).decode("utf-8")
    return Screenshot(base64_data=b64, width=w, height=h, mime=mime, is_sensitive=is_sensitive)


def tap(x: int, y: int, device_id=None, delay: float | None = None) -> None:
    _ = device_id
    did = _ensure_virtual_display_started()
    _ensure_virtual_display_focused_best_effort(did)
    if did is not None:
        try:
            from java import jclass

            Vdc = jclass("com.example.autoglm.VirtualDisplayController")
            Vdc.injectTapBestEffort(int(did), int(x), int(y))
        except Exception:
            _exec_text(f"input -d {int(did)} tap {int(x)} {int(y)}")
    else:
        _exec_text(f"input tap {int(x)} {int(y)}")
    time.sleep(delay if delay is not None else 0.15)


def swipe(start_x: int, start_y: int, end_x: int, end_y: int, duration_ms: int | None = None, device_id=None, delay: float | None = None) -> None:
    _ = device_id
    dur = int(duration_ms) if duration_ms is not None else 600
    did = _ensure_virtual_display_started()
    _ensure_virtual_display_focused_best_effort(did)
    if did is not None:
        try:
            from java import jclass

            Vdc = jclass("com.example.autoglm.VirtualDisplayController")
            Vdc.injectSwipeBestEffort(int(did), int(start_x), int(start_y), int(end_x), int(end_y), int(dur))
        except Exception:
            _exec_text(
                f"input -d {int(did)} swipe {int(start_x)} {int(start_y)} {int(end_x)} {int(end_y)} {dur}"
            )
    else:
        _exec_text(f"input swipe {int(start_x)} {int(start_y)} {int(end_x)} {int(end_y)} {dur}")
    time.sleep(delay if delay is not None else 0.2)


def back(device_id=None, delay: float | None = None) -> None:
    _ = device_id
    did = _ensure_virtual_display_started()
    _ensure_virtual_display_focused_best_effort(did)
    if did is not None:
        try:
            from java import jclass

            Vdc = jclass("com.example.autoglm.VirtualDisplayController")
            Vdc.injectBackBestEffort(int(did))
        except Exception:
            _exec_text(f"input -d {int(did)} keyevent 4")
    else:
        _exec_text("input keyevent 4")
    time.sleep(delay if delay is not None else 0.2)


def home(device_id=None, delay: float | None = None) -> None:
    _ = device_id
    did = _ensure_virtual_display_started()
    _ensure_virtual_display_focused_best_effort(did)
    if did is not None:
        try:
            from java import jclass

            Vdc = jclass("com.example.autoglm.VirtualDisplayController")
            Vdc.injectHomeBestEffort(int(did))
        except Exception:
            _exec_text(f"input -d {int(did)} keyevent 3")
    else:
        _exec_text("input keyevent 3")
    time.sleep(delay if delay is not None else 0.2)


def type_text(text: str, device_id=None) -> None:
    _ = device_id
    did = _ensure_virtual_display_started()
    _ensure_virtual_display_focused_best_effort(did)
    payload = base64.b64encode((text or "").encode("utf-8")).decode("utf-8")
    out = _exec_text(f"am broadcast -a ADB_INPUT_B64 --es msg {payload}")
    if "Broadcast completed" in out:
        return

    safe = (text or "").replace("\n", " ").replace("\r", " ")
    safe = safe.replace("%", "%25").replace(" ", "%s")
    safe = safe.replace("\"", "\\\"")
    _exec_text(f"input text \"{safe}\"")


def clear_text(device_id=None) -> None:
    _ = device_id
    did = _ensure_virtual_display_started()
    _ensure_virtual_display_focused_best_effort(did)
    out = _exec_text("am broadcast -a ADB_CLEAR_TEXT")
    if "Broadcast completed" in out:
        return
    _exec_text("input keyevent --longpress 67")


def detect_and_set_adb_keyboard(device_id=None) -> str:
    _ = device_id
    did = _ensure_virtual_display_started()
    _ensure_virtual_display_focused_best_effort(did)
    current_ime = _exec_text("settings get secure default_input_method").strip()
    if "com.android.adbkeyboard/.AdbIME" not in current_ime:
        _exec_text("ime set com.android.adbkeyboard/.AdbIME")
        type_text("", device_id)
    return current_ime


def restore_keyboard(ime: str, device_id=None) -> None:
    _ = device_id
    did = _ensure_virtual_display_started()
    _ensure_virtual_display_focused_best_effort(did)
    target = (ime or "").strip()
    if not target:
        return
    _exec_text(f"ime set {target}")


def launch_app(app_name: str, device_id=None, delay: float | None = None) -> bool:
    _ = device_id
    pkg = _resolve_package(app_name)
    if not pkg:
        return False

    did = _ensure_virtual_display_started()

    if did is not None:
        # Virtual isolated mode: align with MonitorActivity by preferring explicit component (-n pkg/cls)
        # and activity-reorder-to-front when possible.
        component = ""
        try:
            component = _resolve_launcher_component(pkg)
        except Exception:
            component = ""

        flags = 0x10000000
        candidates: list[str] = []

        if component:
            candidates.extend(
                [
                    f"cmd activity start-activity --user 0 --display {int(did)} --windowingMode 1 --activity-reorder-to-front -n {component} -f {flags}",
                    f"cmd activity start-activity --user 0 --display {int(did)} --windowingMode 1 -n {component} -f {flags}",
                    f"cmd activity start-activity --user 0 --display {int(did)} --activity-reorder-to-front -n {component} -f {flags}",
                    f"cmd activity start-activity --user 0 --display {int(did)} -n {component} -f {flags}",
                    f"am start --user 0 -n {component} --display {int(did)} --activity-reorder-to-front -f {flags}",
                    f"am start --user 0 -n {component} --display {int(did)} -f {flags}",
                ]
            )

        # Fallback to old behavior (package-based start + monkey).
        candidates.extend(
            [
                f"cmd activity start-activity --user 0 --display {int(did)} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p {pkg}",
                f"am start --user 0 --display {int(did)} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p {pkg}",
                f"am start --display {int(did)} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p {pkg}",
            ]
        )

        for c in candidates:
            try:
                _exec_text(c)
            except Exception:
                pass

        try:
            _exec_text(f"monkey --display {int(did)} -p {pkg} -c android.intent.category.LAUNCHER 1")
        except Exception:
            pass

        # Critical: some ROMs ignore --display on start, so we must try moving existing task.
        try:
            _try_move_existing_task_to_display(pkg, int(did))
        except Exception:
            pass

        # Best effort: focus to reduce black frames and input mis-routing.
        try:
            _exec_text(f"cmd input set-focused-display {int(did)}")
        except Exception:
            pass
    else:
        # Prefer am start (more direct); fall back to monkey.
        _exec_text(
            " ".join(
                [
                    "am",
                    "start",
                    "-a",
                    "android.intent.action.MAIN",
                    "-c",
                    "android.intent.category.LAUNCHER",
                    "-p",
                    pkg,
                ]
            )
        )
        _exec_text(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1")
    time.sleep(delay if delay is not None else 0.6)
    return True


def get_current_app(device_id=None) -> str:
    _ = device_id
    out = _exec_text("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' -m 1")
    return out.strip() or "System"


__all__ = [
    "get_screenshot",
    "tap",
    "swipe",
    "back",
    "home",
    "type_text",
    "clear_text",
    "detect_and_set_adb_keyboard",
    "restore_keyboard",
    "launch_app",
    "get_current_app",
    "Screenshot",
]
