package com.example.autoglm

object TaskStreamClassifier {

    data class Classified(
        val kind: String,
        val text: String,
        val nextLastKind: String = kind,
        val stepIndex: Int? = null,
    )

    private val STEP_RE = Regex("^第\\s*(\\d{1,3})\\s*步[：:]\\s*(.*)$")
    private val ACTION_TAG_RE = Regex("^\\[\\[ACTION(?::([^\\]]+))?]]\\s*(.*)$")

    fun classifyAction(deltaOrFull: String, lastActionStreamKind: String?): Classified {
        var kind: String
        var stepIndex: Int? = null
        val raw = deltaOrFull

        val stepMatch = STEP_RE.find(raw.trim())
        if (stepMatch != null) {
            kind = "STEP"
            stepIndex = try {
                stepMatch.groupValues[1].toInt()
            } catch (_: Exception) {
                null
            }
            // 保留完整“第N步”文本，确保 UI 能明确显示步骤边界。
            return Classified(kind = kind, text = raw, nextLastKind = kind, stepIndex = stepIndex)
        }

        val text = when {
            raw.startsWith("[[STATUS]]") -> {
                kind = "STATUS"
                raw.removePrefix("[[STATUS]]")
            }

            raw.startsWith("[[PLAN]]") -> {
                kind = "PLAN"
                raw.removePrefix("[[PLAN]]")
            }

            ACTION_TAG_RE.containsMatchIn(raw) -> {
                val m = ACTION_TAG_RE.find(raw)
                val tag = m?.groupValues?.getOrNull(1)?.orEmpty()?.trim().orEmpty()
                val rest = m?.groupValues?.getOrNull(2)?.orEmpty().orEmpty()
                val normalized = tag
                    .replace(" ", "_")
                    .replace("-", "_")
                    .uppercase()
                kind = if (normalized.isNotEmpty()) "OP_$normalized" else "OPERATION"
                rest.ifEmpty { raw }
            }

            raw.contains("正在查阅屏幕") -> {
                kind = "SCREEN_READ"
                raw
            }

            raw.contains("正在调用模型") || raw.contains("调用模型") -> {
                kind = "CALL_MODEL"
                raw
            }

            raw.contains("截图显示") || raw.contains("截图") -> {
                kind = "SCREENSHOT"
                raw
            }

            raw.contains("等待") && (raw.contains("后继续") || raw.contains("s", ignoreCase = true)) -> {
                kind = "WAIT"
                raw
            }

            else -> {
                kind = if (isMetricChunk(raw) || lastActionStreamKind == "METRIC") "METRIC" else "ACTION"
                if (kind == "METRIC") formatMetricText(raw) else raw
            }
        }

        return Classified(kind = kind, text = text, nextLastKind = kind, stepIndex = stepIndex)
    }

    fun classifyAssistant(deltaOrFull: String): Classified {
        var kind = "ASSISTANT"
        val text = if (deltaOrFull.startsWith("[[THINK]]")) {
            kind = "THINK"
            deltaOrFull.removePrefix("[[THINK]]")
        } else {
            deltaOrFull
        }
        return Classified(kind = kind, text = text, nextLastKind = kind)
    }

    private fun isMetricChunk(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (t.contains("性能指标")) return true
        if (t.contains("TTFT", ignoreCase = true)) return true
        if (t.contains("首 Token")) return true
        if (t.contains("思考完成延迟")) return true
        if (t.contains("总推理时间")) return true
        if (t.matches(Regex("^[=\\-]{10,}.*$"))) return true
        return false
    }

    private fun formatMetricText(raw: String): String {
        var s = raw
        s = s.replace(Regex("={10,}"), "\n$0\n")
        s = s.replace(Regex("-{10,}"), "\n$0\n")

        val keys = listOf(
            "⏱️",
            "性能指标",
            "首 Token",
            "思考完成延迟",
            "总推理时间",
            "TTFT",
        )
        for (k in keys) {
            s = s.replace(k, "\n$k")
        }

        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }
}
