"""Screenshot utilities for capturing Android device screen."""

import base64
import os
import subprocess
import tempfile
import uuid
from dataclasses import dataclass
from io import BytesIO
from typing import Tuple

from PIL import Image

from phone_agent.adb.adb_path import adb_prefix, get_display_id


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


@dataclass
class Screenshot:
    """Represents a captured screenshot."""

    base64_data: str
    width: int
    height: int
    mime: str = "image/png"
    is_sensitive: bool = False


def get_screenshot(device_id: str | None = None, timeout: int = 10) -> Screenshot:
    """
    Capture a screenshot from the connected Android device.

    Args:
        device_id: Optional ADB device ID for multi-device setups.
        timeout: Timeout in seconds for screenshot operations.

    Returns:
        Screenshot object containing base64 data and dimensions.

    Note:
        If the screenshot fails (e.g., on sensitive screens like payment pages),
        a black fallback image is returned with is_sensitive=True.
    """
    cmd_prefix = adb_prefix(device_id)
    display_id = get_display_id()

    try:
        java_available = False
        try:
            from java import jclass

            java_available = True

            VirtualDisplayController = jclass("com.example.autoglm.VirtualDisplayController")
            b64 = str(VirtualDisplayController.screenshotPngBase64())
            if b64:
                png_bytes = base64.b64decode(b64)
                if len(png_bytes) < 2048:
                    raise ValueError("virtual display png too small")
                img = Image.open(BytesIO(png_bytes))
                if _is_likely_black_image(img):
                    raise ValueError("virtual display screenshot is black")
                width, height = img.size

                jpeg_bytes, mime = _encode_jpeg_to_target(img, target_bytes=25 * 1024)
                base64_data = base64.b64encode(jpeg_bytes).decode("utf-8")

                return Screenshot(
                    base64_data=base64_data,
                    width=width,
                    height=height,
                    mime=mime,
                    is_sensitive=False,
                )

            AdbBridge = jclass("com.example.autoglm.AdbBridge")
            did = int(display_id) if display_id else None
            b64 = str(AdbBridge.screencapPngBase64(did))
            if b64:
                png_bytes = base64.b64decode(b64)
                if len(png_bytes) < 2048:
                    raise ValueError("adb screencap png too small")
                img = Image.open(BytesIO(png_bytes))
                if _is_likely_black_image(img):
                    raise ValueError("adb screencap screenshot is black")
                width, height = img.size

                jpeg_bytes, mime = _encode_jpeg_to_target(img, target_bytes=25 * 1024)
                base64_data = base64.b64encode(jpeg_bytes).decode("utf-8")

                return Screenshot(
                    base64_data=base64_data,
                    width=width,
                    height=height,
                    mime=mime,
                    is_sensitive=False,
                )
        except Exception:
            pass

        if java_available:
            return _create_fallback_screenshot(is_sensitive=True)

        screencap_args = ["exec-out", "screencap"]
        if display_id:
            screencap_args += ["-d", str(display_id)]
        screencap_args += ["-p"]

        result = subprocess.run(
            cmd_prefix + screencap_args,
            capture_output=True,
            timeout=timeout,
        )

        png_bytes = result.stdout
        if result.returncode != 0 or not png_bytes:
            return _create_fallback_screenshot(is_sensitive=True)

        img = Image.open(BytesIO(png_bytes))
        width, height = img.size

        jpeg_bytes, mime = _encode_jpeg_to_target(img, target_bytes=25 * 1024)
        base64_data = base64.b64encode(jpeg_bytes).decode("utf-8")

        return Screenshot(
            base64_data=base64_data,
            width=width,
            height=height,
            mime=mime,
            is_sensitive=False,
        )

    except Exception as e:
        print(f"Screenshot error: {e}")
        return _create_fallback_screenshot(is_sensitive=False)


def _get_adb_prefix(device_id: str | None) -> list:
    """Backward-compatible wrapper. Prefer using adb_prefix directly."""
    return adb_prefix(device_id)


def _create_fallback_screenshot(is_sensitive: bool) -> Screenshot:
    """Create a black fallback image when screenshot fails."""
    default_width, default_height = 1080, 2400

    black_img = Image.new("RGB", (default_width, default_height), color="black")
    jpeg_bytes, mime = _encode_jpeg_to_target(black_img, target_bytes=25 * 1024)
    base64_data = base64.b64encode(jpeg_bytes).decode("utf-8")

    return Screenshot(
        base64_data=base64_data,
        width=default_width,
        height=default_height,
        mime=mime,
        is_sensitive=is_sensitive,
    )


def _encode_jpeg_to_target(
    img: Image.Image,
    target_bytes: int = 25 * 1024,
    min_quality: int = 25,
    max_quality: int = 85,
    min_scale: float = 0.35,
) -> tuple[bytes, str]:
    def _to_rgb(im: Image.Image) -> Image.Image:
        if im.mode in ("RGB",):
            return im
        if im.mode in ("RGBA", "LA"):
            bg = Image.new("RGB", im.size, (0, 0, 0))
            bg.paste(im, mask=im.split()[-1])
            return bg
        return im.convert("RGB")

    def _encode(im: Image.Image, quality: int) -> bytes:
        buf = BytesIO()
        _to_rgb(im).save(
            buf,
            format="JPEG",
            quality=int(quality),
            optimize=True,
            progressive=True,
        )
        return buf.getvalue()

    working = img
    scale = 1.0

    while True:
        lo, hi = int(min_quality), int(max_quality)
        best = _encode(working, hi)

        if len(best) <= target_bytes:
            while lo <= hi:
                mid = (lo + hi) // 2
                b = _encode(working, mid)
                if len(b) <= target_bytes:
                    best = b
                    lo = mid + 1
                else:
                    hi = mid - 1
            return best, "image/jpeg"

        best = _encode(working, lo)
        if len(best) <= target_bytes:
            lo2, hi2 = lo, int(max_quality)
            while lo2 <= hi2:
                mid = (lo2 + hi2) // 2
                b = _encode(working, mid)
                if len(b) <= target_bytes:
                    best = b
                    lo2 = mid + 1
                else:
                    hi2 = mid - 1
            return best, "image/jpeg"

        if scale <= min_scale:
            return best, "image/jpeg"

        scale *= 0.85
        new_w = max(1, int(img.size[0] * scale))
        new_h = max(1, int(img.size[1] * scale))
        working = img.resize((new_w, new_h), resample=Image.BILINEAR)
