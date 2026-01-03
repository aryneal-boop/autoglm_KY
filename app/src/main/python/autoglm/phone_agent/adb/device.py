"""Device control utilities for Android automation."""

import os
import subprocess
import time
from typing import List, Optional, Tuple

from phone_agent.config.apps import APP_PACKAGES
from phone_agent.config.timing import TIMING_CONFIG
from phone_agent.adb.adb_path import adb_prefix, get_display_id


def get_current_app(device_id: str | None = None) -> str:
    """
    Get the currently focused app name.

    Args:
        device_id: Optional ADB device ID for multi-device setups.

    Returns:
        The app name if recognized, otherwise "System Home".
    """
    cmd_prefix = adb_prefix(device_id)

    result = subprocess.run(
        cmd_prefix + ["shell", "dumpsys", "window"], capture_output=True, text=True, encoding="utf-8"
    )
    output = result.stdout
    if not output:
        raise ValueError("No output from dumpsys window")

    # Parse window focus info
    for line in output.split("\n"):
        if "mCurrentFocus" in line or "mFocusedApp" in line:
            for app_name, package in APP_PACKAGES.items():
                if package in line:
                    return app_name

    return "System Home"


def tap(
    x: int, y: int, device_id: str | None = None, delay: float | None = None
) -> None:
    """
    Tap at the specified coordinates.

    Args:
        x: X coordinate.
        y: Y coordinate.
        device_id: Optional ADB device ID.
        delay: Delay in seconds after tap. If None, uses configured default.
    """
    if delay is None:
        delay = TIMING_CONFIG.device.default_tap_delay

    cmd_prefix = adb_prefix(device_id)
    display_id = get_display_id()

    subprocess.run(
        cmd_prefix
        + ["shell", "input"]
        + (["-d", str(display_id)] if display_id else [])
        + ["tap", str(x), str(y)],
        capture_output=True,
    )
    time.sleep(delay)


def double_tap(
    x: int, y: int, device_id: str | None = None, delay: float | None = None
) -> None:
    """
    Double tap at the specified coordinates.

    Args:
        x: X coordinate.
        y: Y coordinate.
        device_id: Optional ADB device ID.
        delay: Delay in seconds after double tap. If None, uses configured default.
    """
    if delay is None:
        delay = TIMING_CONFIG.device.default_double_tap_delay

    adb_prefix = _get_adb_prefix(device_id)
    display_id = get_display_id()

    subprocess.run(
        adb_prefix
        + ["shell", "input"]
        + (["-d", str(display_id)] if display_id else [])
        + ["tap", str(x), str(y)],
        capture_output=True,
    )
    time.sleep(TIMING_CONFIG.device.double_tap_interval)
    subprocess.run(
        adb_prefix
        + ["shell", "input"]
        + (["-d", str(display_id)] if display_id else [])
        + ["tap", str(x), str(y)],
        capture_output=True,
    )
    time.sleep(delay)


def long_press(
    x: int,
    y: int,
    duration_ms: int = 3000,
    device_id: str | None = None,
    delay: float | None = None,
) -> None:
    """
    Long press at the specified coordinates.

    Args:
        x: X coordinate.
        y: Y coordinate.
        duration_ms: Duration of press in milliseconds.
        device_id: Optional ADB device ID.
        delay: Delay in seconds after long press. If None, uses configured default.
    """
    if delay is None:
        delay = TIMING_CONFIG.device.default_long_press_delay

    cmd_prefix = adb_prefix(device_id)
    display_id = get_display_id()

    subprocess.run(
        cmd_prefix
        + ["shell", "input"]
        + (["-d", str(display_id)] if display_id else [])
        + ["swipe", str(x), str(y), str(x), str(y), str(duration_ms)],
        capture_output=True,
    )
    time.sleep(delay)


def swipe(
    start_x: int,
    start_y: int,
    end_x: int,
    end_y: int,
    duration_ms: int | None = None,
    device_id: str | None = None,
    delay: float | None = None,
) -> None:
    """
    Swipe from start to end coordinates.

    Args:
        start_x: Starting X coordinate.
        start_y: Starting Y coordinate.
        end_x: Ending X coordinate.
        end_y: Ending Y coordinate.
        duration_ms: Duration of swipe in milliseconds (auto-calculated if None).
        device_id: Optional ADB device ID.
        delay: Delay in seconds after swipe. If None, uses configured default.
    """
    if delay is None:
        delay = TIMING_CONFIG.device.default_swipe_delay

    cmd_prefix = adb_prefix(device_id)
    display_id = get_display_id()

    if duration_ms is None:
        # Calculate duration based on distance
        dist_sq = (start_x - end_x) ** 2 + (start_y - end_y) ** 2
        duration_ms = int(dist_sq / 1000)
        duration_ms = max(1000, min(duration_ms, 2000))  # Clamp between 1000-2000ms

    subprocess.run(
        cmd_prefix
        + ["shell", "input"]
        + (["-d", str(display_id)] if display_id else [])
        + ["swipe", str(start_x), str(start_y), str(end_x), str(end_y), str(duration_ms)],
        capture_output=True,
    )
    time.sleep(delay)


def back(device_id: str | None = None, delay: float | None = None) -> None:
    """
    Press the back button.

    Args:
        device_id: Optional ADB device ID.
        delay: Delay in seconds after pressing back. If None, uses configured default.
    """
    if delay is None:
        delay = TIMING_CONFIG.device.default_back_delay

    cmd_prefix = adb_prefix(device_id)
    display_id = get_display_id()

    subprocess.run(
        cmd_prefix
        + ["shell", "input"]
        + (["-d", str(display_id)] if display_id else [])
        + ["keyevent", "4"],
        capture_output=True,
    )
    time.sleep(delay)


def home(device_id: str | None = None, delay: float | None = None) -> None:
    """
    Press the home button.

    Args:
        device_id: Optional ADB device ID.
        delay: Delay in seconds after pressing home. If None, uses configured default.
    """
    if delay is None:
        delay = TIMING_CONFIG.device.default_home_delay

    cmd_prefix = adb_prefix(device_id)
    display_id = get_display_id()

    subprocess.run(
        cmd_prefix
        + ["shell", "input"]
        + (["-d", str(display_id)] if display_id else [])
        + ["keyevent", "KEYCODE_HOME"],
        capture_output=True,
    )
    time.sleep(delay)


def launch_app(
    app_name: str, device_id: str | None = None, delay: float | None = None
) -> bool:
    """
    Launch an app by name.

    Args:
        app_name: The app name (must be in APP_PACKAGES).
        device_id: Optional ADB device ID.
        delay: Delay in seconds after launching. If None, uses configured default.

    Returns:
        True if app was launched, False if app not found.
    """
    if delay is None:
        delay = TIMING_CONFIG.device.default_launch_delay

    cmd_prefix = adb_prefix(device_id)
    try:
        from phone_agent.app_package_resolver import resolve_package, is_package_installed

        package = resolve_package(app_name, device_id=device_id)
        if not package:
            return False
        try:
            if not is_package_installed(package, device_id=device_id):
                return False
        except Exception:
            pass
    except Exception:
        if app_name not in APP_PACKAGES:
            return False
        package = APP_PACKAGES[app_name]

    display_id = get_display_id()

    if display_id:
        try:
            from java import jclass

            AdbBridge = jclass("com.example.autoglm.AdbBridge")
            ok = bool(AdbBridge.launchAppOnDisplay(str(package), int(display_id)))
            if ok:
                time.sleep(delay)
                return True
        except Exception:
            pass

        subprocess.run(
            cmd_prefix
            + [
                "shell",
                "am",
                "start",
                "--display",
                str(display_id),
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.LAUNCHER",
                "-p",
                package,
            ],
            capture_output=True,
        )
        time.sleep(delay)
        return True

    subprocess.run(
        cmd_prefix
        + [
            "shell",
            "monkey",
            "-p",
            package,
            "-c",
            "android.intent.category.LAUNCHER",
            "1",
        ],
        capture_output=True,
    )
    time.sleep(delay)
    return True


def _get_adb_prefix(device_id: str | None) -> list:
    """Backward-compatible wrapper. Prefer using adb_prefix directly."""
    return adb_prefix(device_id)
