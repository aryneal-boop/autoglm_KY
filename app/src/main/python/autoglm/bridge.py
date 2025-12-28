import os
import time
import tempfile
import wave
import struct

import jwt
import requests


def generate_token(apikey: str, exp_seconds: int = 3600):
    try:
        # 智谱 API Key 格式通常是 "id.secret"
        api_key_id, api_key_secret = apikey.split(".")
    except Exception:
        raise ValueError("Invalid Zhipu API Key format. Expected 'id.secret'")

    payload = {
        "api_key": api_key_id,
        "exp": int(time.time() * 1000) + exp_seconds * 1000,
        "timestamp": int(time.time() * 1000),
    }

    # 智谱要求的 Header 格式
    headers = {
        "alg": "HS256",
        "sign_type": "SIGN",
    }

    return jwt.encode(
        payload,
        api_key_secret,
        algorithm="HS256",
        headers=headers,
    )


def speech_to_text_zhipu(audio_path: str, api_key: str):
    token = generate_token(api_key)
    if isinstance(token, bytes):
        token = token.decode("utf-8")

    url = "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions"

    headers = {
        "Authorization": f"Bearer {token}",
    }

    # 严格匹配官方 curl 示例的参数：使用 multipart/form-data (data + files)，不要 JSON。
    data = {
        "model": "glm-asr-2512",
        "stream": "false",
    }

    try:
        with open(audio_path, "rb") as f:
            files = {
                "file": ("input.wav", f, "audio/wav"),
            }

            # 注意：严禁手动设置 Content-Type，requests 会自动生成 boundary。
            resp = requests.post(
                url,
                headers=headers,
                data=data,
                files=files,
                timeout=60,
            )

        if resp.status_code != 200:
            return f"JWT请求失败: {resp.status_code} - {resp.text}"

        try:
            j = resp.json()
            if isinstance(j, dict):
                return j.get("text", "") or ""
            return ""
        except Exception:
            return ""
    except Exception as e:
        return f"系统异常: {str(e)}"


def _maybe_normalize_wav(audio_path: str) -> str:
    enabled = (os.environ.get("AUTOGLM_STT_NORMALIZE") or "").strip().lower() in {"1", "true", "yes", "on"}
    if not enabled:
        return audio_path

    try:
        target_peak = float((os.environ.get("AUTOGLM_STT_TARGET_PEAK") or "0.6").strip())
    except Exception:
        target_peak = 0.6
    target_peak = max(0.1, min(0.95, target_peak))

    try:
        max_gain = float((os.environ.get("AUTOGLM_STT_MAX_GAIN") or "8.0").strip())
    except Exception:
        max_gain = 8.0
    max_gain = max(1.0, min(20.0, max_gain))

    try:
        with wave.open(audio_path, "rb") as wf:
            n_channels = wf.getnchannels()
            sampwidth = wf.getsampwidth()
            framerate = wf.getframerate()
            comptype = wf.getcomptype()
            compname = wf.getcompname()
            frames = wf.readframes(wf.getnframes())

        if n_channels != 1 or sampwidth != 2 or framerate != 16000:
            return audio_path
        if comptype != "NONE":
            return audio_path
        if not frames:
            return audio_path

        if len(frames) % 2 != 0:
            return audio_path

        max_abs = 0
        for (sample,) in struct.iter_unpack("<h", frames):
            v = -sample if sample < 0 else sample
            if v > max_abs:
                max_abs = v

        if max_abs <= 0:
            return audio_path

        desired = int(32767 * target_peak)
        gain = desired / float(max_abs)
        if gain <= 1.05:
            return audio_path
        gain = min(gain, max_gain)

        out = bytearray(len(frames))
        i = 0
        for (sample,) in struct.iter_unpack("<h", frames):
            s = int(sample * gain)
            if s > 32767:
                s = 32767
            elif s < -32768:
                s = -32768
            out[i:i + 2] = struct.pack("<h", s)
            i += 2

        out_frames = bytes(out)
        tmp = tempfile.NamedTemporaryFile(prefix="autoglm_norm_", suffix=".wav", delete=False)
        tmp_path = tmp.name
        tmp.close()

        with wave.open(tmp_path, "wb") as out:
            out.setnchannels(n_channels)
            out.setsampwidth(sampwidth)
            out.setframerate(framerate)
            out.setcomptype(comptype, compname)
            out.writeframes(out_frames)
        return tmp_path
    except Exception:
        return audio_path


def speech_to_text(audio_path: str):
    api_key = os.environ.get("ZHIPU_API_KEY") or os.environ.get("PHONE_AGENT_API_KEY")
    if not api_key:
        return "JWT请求失败: 未配置 ZHIPU_API_KEY"
    norm_path = _maybe_normalize_wav(audio_path)
    return speech_to_text_zhipu(norm_path, api_key)
