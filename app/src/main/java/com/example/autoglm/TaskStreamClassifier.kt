package com.example.autoglm

object TaskStreamClassifier {

    data class Classified(
        val kind: String,
        val text: String,
        val nextLastKind: String = kind,
    )

    fun classifyAction(deltaOrFull: String, lastActionStreamKind: String?): Classified {
        var kind: String
        val raw = deltaOrFull
        val text = when {
            raw.startsWith("[[STATUS]]") -> {
                kind = "STATUS"
                raw.removePrefix("[[STATUS]]")
            }

            raw.startsWith("[[PLAN]]") -> {
                kind = "PLAN"
                raw.removePrefix("[[PLAN]]")
            }

            raw.startsWith("[[ACTION]]") -> {
                kind = "OPERATION"
                raw.removePrefix("[[ACTION]]")
            }

            else -> {
                kind = if (isMetricChunk(raw) || lastActionStreamKind == "METRIC") "METRIC" else "ACTION"
                if (kind == "METRIC") formatMetricText(raw) else raw
            }
        }

        return Classified(
            kind = kind,
            text = text,
            nextLastKind = kind,
        )
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
