"""Model client for AI inference using OpenAI-compatible API."""

import json
import os
import time
from dataclasses import dataclass, field
from typing import Any

from openai import OpenAI

from phone_agent.config.i18n import get_message


@dataclass
class ModelConfig:
    """Configuration for the AI model."""

    base_url: str = "http://localhost:8000/v1"
    api_key: str = ""
    model_name: str = "autoglm-phone-9b"
    max_tokens: int = 3000
    temperature: float = 0.0
    top_p: float = 0.85
    frequency_penalty: float = 0.2
    extra_body: dict[str, Any] = field(default_factory=dict)
    lang: str = "cn"  # Language for UI messages: 'cn' or 'en'


@dataclass
class ModelResponse:
    """Response from the AI model."""

    thinking: str
    action: str
    raw_content: str
    # Performance metrics
    time_to_first_token: float | None = None  # Time to first token (seconds)
    time_to_thinking_end: float | None = None  # Time to thinking end (seconds)
    total_time: float | None = None  # Total inference time (seconds)


class ModelClient:
    """
    Client for interacting with OpenAI-compatible vision-language models.

    Args:
        config: Model configuration.
    """

    def __init__(self, config: ModelConfig | None = None):
        self.config = config or ModelConfig()
        self.client = OpenAI(base_url=self.config.base_url, api_key=self.config.api_key)

    def _read_android_config(self) -> tuple[str | None, str | None, str | None]:
        try:
            from com.chaquo.python import Python as ChaquopyPython
            from java import jclass

            app = ChaquopyPython.getPlatform().getApplication()
            ConfigManager = jclass("com.example.autoglm.ConfigManager")
            cm = ConfigManager(app)
            cfg = cm.getConfig()
            base_url = str(cfg.getBaseUrl())
            api_key = str(cfg.getApiKey())
            model_name = str(cfg.getModelName())
            return base_url, api_key, model_name
        except Exception:
            return None, None, None

    def _refresh_config_from_runtime(self) -> None:
        base_url, api_key, model_name = self._read_android_config()

        env_base_url = os.environ.get("PHONE_AGENT_BASE_URL")
        env_api_key = os.environ.get("PHONE_AGENT_API_KEY")
        env_model_name = os.environ.get("PHONE_AGENT_MODEL")

        new_base_url = (base_url or env_base_url or self.config.base_url).strip()
        new_api_key = (api_key or env_api_key or self.config.api_key)
        new_model_name = (model_name or env_model_name or self.config.model_name).strip()

        changed = (
            new_base_url != self.config.base_url
            or new_api_key != self.config.api_key
            or new_model_name != self.config.model_name
        )

        self.config.base_url = new_base_url
        self.config.api_key = new_api_key
        self.config.model_name = new_model_name

        if changed:
            self.client = OpenAI(base_url=self.config.base_url, api_key=self.config.api_key)

    def request(self, messages: list[dict[str, Any]]) -> ModelResponse:
        """
        Send a request to the model.

        Args:
            messages: List of message dictionaries in OpenAI format.

        Returns:
            ModelResponse containing thinking and action.

        Raises:
            ValueError: If the response cannot be parsed.
        """
        self._refresh_config_from_runtime()

        # Start timing
        start_time = time.time()
        time_to_first_token = None
        time_to_thinking_end = None

        stream = self.client.chat.completions.create(
            messages=messages,
            model=self.config.model_name,
            max_tokens=self.config.max_tokens,
            temperature=self.config.temperature,
            top_p=self.config.top_p,
            frequency_penalty=self.config.frequency_penalty,
            extra_body=self.config.extra_body,
            stream=True,
        )

        raw_content = ""
        buffer = ""  # Buffer to hold content that might be part of a marker
        action_markers = ["finish(message=", "do(action="]
        in_action_phase = False  # Track if we've entered the action phase
        first_token_received = False

        for chunk in stream:
            if len(chunk.choices) == 0:
                continue
            if chunk.choices[0].delta.content is not None:
                content = chunk.choices[0].delta.content
                raw_content += content

                # Record time to first token
                if not first_token_received:
                    time_to_first_token = time.time() - start_time
                    first_token_received = True

                if in_action_phase:
                    # Already in action phase, just accumulate content without printing
                    continue

                buffer += content

                # Check if any marker is fully present in buffer
                marker_found = False
                for marker in action_markers:
                    if marker in buffer:
                        # Marker found, print everything before it
                        thinking_part = buffer.split(marker, 1)[0]
                        print(thinking_part, end="", flush=True)
                        print()  # Print newline after thinking is complete
                        in_action_phase = True
                        marker_found = True

                        # Record time to thinking end
                        if time_to_thinking_end is None:
                            time_to_thinking_end = time.time() - start_time

                        break

                if marker_found:
                    continue  # Continue to collect remaining content

                # Check if buffer ends with a prefix of any marker
                # If so, don't print yet (wait for more content)
                is_potential_marker = False
                for marker in action_markers:
                    for i in range(1, len(marker)):
                        if buffer.endswith(marker[:i]):
                            is_potential_marker = True
                            break
                    if is_potential_marker:
                        break

                if not is_potential_marker:
                    # Safe to print the buffer
                    print(buffer, end="", flush=True)
                    buffer = ""

        # Calculate total time
        total_time = time.time() - start_time

        # Parse thinking and action from response
        thinking, action = self._parse_response(raw_content)

        # Print performance metrics
        lang = self.config.lang
        print()
        print("=" * 50)
        print(f"⏱️  {get_message('performance_metrics', lang)}:")
        print("-" * 50)
        if time_to_first_token is not None:
            print(
                f"{get_message('time_to_first_token', lang)}: {time_to_first_token:.3f}s"
            )
        if time_to_thinking_end is not None:
            print(
                f"{get_message('time_to_thinking_end', lang)}:        {time_to_thinking_end:.3f}s"
            )
        print(
            f"{get_message('total_inference_time', lang)}:          {total_time:.3f}s"
        )
        print("=" * 50)

        return ModelResponse(
            thinking=thinking,
            action=action,
            raw_content=raw_content,
            time_to_first_token=time_to_first_token,
            time_to_thinking_end=time_to_thinking_end,
            total_time=total_time,
        )

    def _parse_response(self, content: str) -> tuple[str, str]:
        """
        Parse the model response into thinking and action parts.

        Parsing rules:
        1. If content contains 'finish(message=', everything before is thinking,
           everything from 'finish(message=' onwards is action.
        2. If rule 1 doesn't apply but content contains 'do(action=',
           everything before is thinking, everything from 'do(action=' onwards is action.
        3. Fallback: If content contains '<answer>', use legacy parsing with XML tags.
        4. Otherwise, return empty thinking and full content as action.

        Args:
            content: Raw response content.

        Returns:
            Tuple of (thinking, action).
        """
        # Rule 1: Check for finish(message=
        if "finish(message=" in content:
            parts = content.split("finish(message=", 1)
            thinking = parts[0].strip()
            action = "finish(message=" + parts[1]
            return thinking, action

        # Rule 2: Check for do(action=
        if "do(action=" in content:
            parts = content.split("do(action=", 1)
            thinking = parts[0].strip()
            action = "do(action=" + parts[1]
            return thinking, action

        # Rule 3: Fallback to legacy XML tag parsing
        if "<answer>" in content:
            parts = content.split("<answer>", 1)
            thinking = parts[0].replace("<think>", "").replace("</think>", "").strip()
            action = parts[1].replace("</answer>", "").strip()
            return thinking, action

        # Rule 4: No markers found, return content as action
        return "", content


def test_api_connection() -> str:
    def _fmt_exc(e: Exception) -> str:
        parts: list[str] = [f"{type(e).__name__}: {e}"]
        for attr in ["status_code", "code", "type", "param"]:
            if hasattr(e, attr):
                try:
                    v = getattr(e, attr)
                    if v is not None:
                        parts.append(f"{attr}={v}")
                except Exception:
                    pass
        return ", ".join(parts)

    base_url = os.environ.get("PHONE_AGENT_BASE_URL")
    api_key = os.environ.get("PHONE_AGENT_API_KEY")
    model_name = os.environ.get("PHONE_AGENT_MODEL")

    # Prefer Android ConfigManager (real-time), fallback to env.
    try:
        from com.chaquo.python import Python as ChaquopyPython
        from java import jclass

        app = ChaquopyPython.getPlatform().getApplication()
        ConfigManager = jclass("com.example.autoglm.ConfigManager")
        cm = ConfigManager(app)
        cfg = cm.getConfig()
        base_url = str(cfg.getBaseUrl()) or base_url
        api_key = str(cfg.getApiKey()) or api_key
        model_name = str(cfg.getModelName()) or model_name
    except Exception:
        pass

    base_url = (base_url or "").strip()
    model_name = (model_name or "").strip()
    api_key = api_key or ""

    if not base_url:
        return "失败：Base URL 为空"
    if not model_name:
        return "失败：Model Name 为空"

    # Only auto-append /v1 for the official OpenAI endpoint.
    # For other providers (e.g., BigModel uses /api/paas/v4), the caller must provide the correct base URL.
    if "api.openai.com" in base_url and not base_url.rstrip("/").endswith("/v1"):
        base_url = base_url.rstrip("/") + "/v1"

    header = f"base_url={base_url}, model={model_name}, api_key_len={len(api_key)}"

    try:
        import requests
    except Exception as e:
        return "失败：缺少 requests 依赖：" + _fmt_exc(e)

    def _snippet(text: str, limit: int = 500) -> str:
        t = (text or "").strip()
        return t if len(t) <= limit else (t[:limit] + "...<truncated>")

    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    # Step 1: GET /models (optional; some providers may not implement it)
    try:
        url = base_url.rstrip("/") + "/models"
        r = requests.get(url, headers=headers, timeout=20)
        if 200 <= r.status_code < 300:
            try:
                data = r.json()
                ids = []
                try:
                    ids = [m.get("id") for m in (data.get("data") or []) if isinstance(m, dict)]
                except Exception:
                    ids = []
                if ids and model_name in ids:
                    return "成功（GET /models）：" + header

                # Some providers return incomplete model list or filter by permission.
                # If the configured model isn't listed, keep testing chat API for final confirmation.
                tip = "模型名未出现在 /models 返回中（可能是列表不完整/权限过滤），继续测试 /chat/completions..."
            except Exception:
                tip = "GET /models 返回非 JSON，继续测试 /chat/completions..."
        else:
            if r.status_code == 404:
                err_models = "HTTP 404: /models not supported"
            else:
                err_models = f"HTTP {r.status_code}: {_snippet(r.text)}"
    except Exception as e:
        err_models = _fmt_exc(e)

    # If /models succeeded but didn't early-return above, attach tip and continue.
    tip = locals().get("tip")

    # Step 2: POST /chat/completions
    try:
        url = base_url.rstrip("/") + "/chat/completions"
        payload = {
            "model": model_name,
            "messages": [{"role": "user", "content": "Ping"}],
            "max_tokens": 4,
            "temperature": 0.0,
            "stream": False,
        }
        r = requests.post(url, headers=headers, json=payload, timeout=20)
        if 200 <= r.status_code < 300:
            try:
                j = r.json()
                content = ""
                try:
                    content = j["choices"][0]["message"]["content"]
                except Exception:
                    content = ""
                prefix = "成功（POST /chat/completions）：" + header
                if tip:
                    prefix += f"\n提示：{tip}"
                return prefix + (f"\n回复={content}" if content else "")
            except Exception:
                prefix = "成功（POST /chat/completions）：" + header
                if tip:
                    prefix += f"\n提示：{tip}"
                return prefix + f"\n响应体：{_snippet(r.text)}"
        else:
            err_chat = f"HTTP {r.status_code}: {_snippet(r.text)}"
    except Exception as e:
        err_chat = _fmt_exc(e)

    msg = "失败：" + header
    if tip:
        msg += f"\n提示：{tip}"
    msg += f"\nGET /models 错误：{err_models}\nPOST /chat/completions 错误：{err_chat}"
    return msg


class MessageBuilder:
    """Helper class for building conversation messages."""

    @staticmethod
    def create_system_message(content: str) -> dict[str, Any]:
        """Create a system message."""
        return {"role": "system", "content": content}

    @staticmethod
    def create_user_message(
        text: str, image_base64: str | None = None, image_mime: str | None = None
    ) -> dict[str, Any]:
        """
        Create a user message with optional image.

        Args:
            text: Text content.
            image_base64: Optional base64-encoded image.

        Returns:
            Message dictionary.
        """
        content = []

        if image_base64:
            mime = (image_mime or "image/png").strip() or "image/png"
            content.append(
                {
                    "type": "image_url",
                    "image_url": {"url": f"data:{mime};base64,{image_base64}"},
                }
            )

        content.append({"type": "text", "text": text})

        return {"role": "user", "content": content}

    @staticmethod
    def create_assistant_message(content: str) -> dict[str, Any]:
        """Create an assistant message."""
        return {"role": "assistant", "content": content}

    @staticmethod
    def remove_images_from_message(message: dict[str, Any]) -> dict[str, Any]:
        """
        Remove image content from a message to save context space.

        Args:
            message: Message dictionary.

        Returns:
            Message with images removed.
        """
        if isinstance(message.get("content"), list):
            message["content"] = [
                item for item in message["content"] if item.get("type") == "text"
            ]
        return message

    @staticmethod
    def build_screen_info(current_app: str, **extra_info) -> str:
        """
        Build screen info string for the model.

        Args:
            current_app: Current app name.
            **extra_info: Additional info to include.

        Returns:
            JSON string with screen info.
        """
        info = {"current_app": current_app, **extra_info}
        return json.dumps(info, ensure_ascii=False)
