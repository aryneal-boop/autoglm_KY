"""Shizuku-based device operations for Android automation.

This module is used when AUTOGM_CONNECT_MODE=SHIZUKU.
It executes shell commands via Kotlin ShizukuBridge (rikka.shizuku).
"""

from __future__ import annotations

import base64
import os
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
    if "." in q:
        return q
    return APP_PACKAGES.get(q, "")


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
    _exec_text(f"input tap {int(x)} {int(y)}")
    time.sleep(delay if delay is not None else 0.15)


def swipe(start_x: int, start_y: int, end_x: int, end_y: int, duration_ms: int | None = None, device_id=None, delay: float | None = None) -> None:
    _ = device_id
    dur = int(duration_ms) if duration_ms is not None else 600
    _exec_text(f"input swipe {int(start_x)} {int(start_y)} {int(end_x)} {int(end_y)} {dur}")
    time.sleep(delay if delay is not None else 0.2)


def back(device_id=None, delay: float | None = None) -> None:
    _ = device_id
    _exec_text("input keyevent 4")
    time.sleep(delay if delay is not None else 0.2)


def home(device_id=None, delay: float | None = None) -> None:
    _ = device_id
    _exec_text("input keyevent 3")
    time.sleep(delay if delay is not None else 0.2)


def type_text(text: str, device_id=None) -> None:
    _ = device_id
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
    out = _exec_text("am broadcast -a ADB_CLEAR_TEXT")
    if "Broadcast completed" in out:
        return
    _exec_text("input keyevent --longpress 67")


def detect_and_set_adb_keyboard(device_id=None) -> str:
    _ = device_id
    current_ime = _exec_text("settings get secure default_input_method").strip()
    if "com.android.adbkeyboard/.AdbIME" not in current_ime:
        _exec_text("ime set com.android.adbkeyboard/.AdbIME")
        type_text("", device_id)
    return current_ime


def restore_keyboard(ime: str, device_id=None) -> None:
    _ = device_id
    target = (ime or "").strip()
    if not target:
        return
    _exec_text(f"ime set {target}")


def launch_app(app_name: str, device_id=None, delay: float | None = None) -> bool:
    _ = device_id
    pkg = _resolve_package(app_name)
    if not pkg:
        return False

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
