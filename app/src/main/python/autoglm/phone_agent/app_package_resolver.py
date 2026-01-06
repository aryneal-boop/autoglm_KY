from __future__ import annotations

import subprocess
import time
from typing import Iterable

from phone_agent.config.apps import APP_PACKAGES
from phone_agent.device_factory import DeviceType, get_device_factory
from phone_agent.adb.adb_path import adb_prefix


_cached_at: float = 0.0
_cached_packages: set[str] = set()
_cache_ttl_sec: float = 8.0


def _normalize(s: str) -> str:
    return (s or "").strip()


def _list_packages_from_text(text: str) -> set[str]:
    pkgs: set[str] = set()
    for raw in (text or "").splitlines():
        line = (raw or "").strip()
        if not line:
            continue
        # pm list packages 输出格式：package:com.xxx
        if line.startswith("package:"):
            line = line[len("package:") :]
        line = line.strip()
        if line and " " not in line and "/" not in line:
            pkgs.add(line)
    return pkgs


def _get_all_packages(device_id: str | None = None, force_refresh: bool = False) -> set[str]:
    global _cached_at, _cached_packages

    now = time.time()
    if not force_refresh and _cached_packages and (now - _cached_at) <= _cache_ttl_sec:
        return _cached_packages

    df = get_device_factory()
    out = ""
    if df.device_type == DeviceType.SHIZUKU:
        try:
            from java import jclass

            Bridge = jclass("com.example.autoglm.ShizukuBridge")
            out = str(Bridge.execText("pm list packages") or "")
        except Exception:
            out = ""
    else:
        try:
            cmd = adb_prefix(device_id) + ["shell", "pm", "list", "packages"]
            r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
            out = r.stdout or ""
        except Exception:
            out = ""

    pkgs = _list_packages_from_text(out)
    if pkgs:
        _cached_packages = pkgs
        _cached_at = now
    return _cached_packages


def is_package_installed(package_name: str, device_id: str | None = None) -> bool:
    pkg = _normalize(package_name)
    if not pkg:
        return False

    pkgs = _get_all_packages(device_id=device_id)
    return pkg in pkgs


def _iter_mapping_candidates(query: str) -> Iterable[str]:
    q = query.strip()
    if not q:
        return []

    # 1) 完整匹配（包含大小写变化）
    if q in APP_PACKAGES:
        return [APP_PACKAGES.get(q) or ""]

    ql = q.lower()
    for k, v in APP_PACKAGES.items():
        if (k or "").strip().lower() == ql:
            return [v]

    # 2) 模糊包含：优先中文/英文别名里出现关键词
    hits: list[str] = []
    for k, v in APP_PACKAGES.items():
        kl = (k or "").strip().lower()
        if ql in kl:
            hits.append(v)

    if hits:
        # 去重保持顺序
        seen: set[str] = set()
        out: list[str] = []
        for p in hits:
            if p and p not in seen:
                seen.add(p)
                out.append(p)
        return out

    return []


def resolve_package(app_or_pkg: str, device_id: str | None = None) -> str:
    q = _normalize(app_or_pkg)
    if not q:
        return ""

    # 已经像包名
    if "." in q and " " not in q:
        return q

    for p in _iter_mapping_candidates(q):
        if p:
            return p

    # 最后：从已安装包名中按子串匹配
    ql = q.lower()
    pkgs = _get_all_packages(device_id=device_id)
    if not pkgs:
        return ""

    candidates = [p for p in pkgs if ql in p.lower()]
    if not candidates:
        return ""
    candidates.sort(key=lambda s: (len(s), s))
    return candidates[0]
