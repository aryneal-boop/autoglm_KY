package com.example.autoglm

import android.content.Context
import android.util.Log

class VirtualDisplayManager(
    private val context: Context,
    private val adbExecPath: String,
) {
    private data class DisplayScanResult(
        val displayId: Int,
        val type: String?,
        val name: String?,
        val blockHasOverlayWord: Boolean,
        val hasOverlayUniqueId: Boolean,
        val has720x1280: Boolean,
    )

    private fun runShell(args: List<String>, timeoutMs: Long = 6_000L): LocalAdb.CommandResult {
        return LocalAdb.runCommand(
            context,
            LocalAdb.buildAdbCommand(context, adbExecPath, listOf("shell") + args),
            timeoutMs = timeoutMs,
        )
    }

    private fun parseDisplayIds(dumpsys: String): Set<Int> {
        val ids = LinkedHashSet<Int>()

        val patterns = listOf(
            Regex("mDisplayId=(\\d+)", RegexOption.IGNORE_CASE),
            Regex("displayId\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("\\bDisplay\\s+(\\d+)\\b"),
        )
        for (p in patterns) {
            for (m in p.findAll(dumpsys)) {
                try {
                    ids.add(m.groupValues[1].toInt())
                } catch (_: Exception) {
                }
            }
        }
        return ids
    }

    private fun readDumpsysDisplay(): String {
        return runShell(listOf("dumpsys", "display"), timeoutMs = 8_000L).output
    }

    private fun scanDisplays(dumpsys: String): List<DisplayScanResult> {
        val results = ArrayList<DisplayScanResult>()

        val idMarkers = ArrayList<Pair<Int, Int>>()
        val patterns = listOf(
            Regex("mDisplayId=(\\d+)", RegexOption.IGNORE_CASE),
            Regex("\\bdisplayId\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("\\bDisplay\\s+(\\d+)\\b"),
        )
        for (p in patterns) {
            for (m in p.findAll(dumpsys)) {
                val id = m.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
                idMarkers.add(id to m.range.first)
            }
        }
        if (idMarkers.isEmpty()) return results
        idMarkers.sortBy { it.second }

        fun <T> nearestMatch(block: String, anchor: Int, regex: Regex, groupIndex: Int): T? {
            var best: T? = null
            var bestDist = Int.MAX_VALUE
            for (m in regex.findAll(block)) {
                val dist = kotlin.math.abs(m.range.first - anchor)
                if (dist < bestDist) {
                    bestDist = dist
                    @Suppress("UNCHECKED_CAST")
                    best = m.groupValues[groupIndex] as T
                }
            }
            return best
        }

        fun parseType(block: String, anchor: Int): String? {
            val patterns = listOf(
                Regex("\\btype\\s*=\\s*([A-Z_]+)\\b"),
                Regex("\\bTYPE_([A-Z_]+)\\b"),
            )
            for (p in patterns) {
                val v = nearestMatch<String>(block, anchor, p, 1)
                if (!v.isNullOrBlank()) return v.trim().uppercase()
            }
            return null
        }

        fun parseName(block: String, anchor: Int): String? {
            val patterns = listOf(
                Regex("DisplayInfo\\{\\\"([^\\\"]+)\\\""),
                Regex("DisplayDeviceInfo\\{\\\"([^\\\"]+)\\\""),
                Regex("\\bname\\s*=\\s*\\\"([^\\\"]+)\\\""),
            )
            for (p in patterns) {
                val v = nearestMatch<String>(block, anchor, p, 1)
                if (!v.isNullOrBlank()) return v.trim()
            }
            return null
        }

        fun has720x1280(block: String, anchor: Int): Boolean {
            val m = Regex("\\b(\\d{2,5})\\s*x\\s*(\\d{2,5})\\b").findAll(block)
            var bestDist = Int.MAX_VALUE
            var bestOk = false
            for (mm in m) {
                val w = mm.groupValues[1].toIntOrNull() ?: continue
                val h = mm.groupValues[2].toIntOrNull() ?: continue
                val dist = kotlin.math.abs(mm.range.first - anchor)
                if (dist < bestDist) {
                    bestDist = dist
                    bestOk = (w == 720 && h == 1280)
                }
                if (w == 720 && h == 1280 && dist <= 200) return true
            }
            return bestOk
        }

        fun hasOverlayUniqueId(block: String, anchor: Int): Boolean {
            val r = Regex("uniqueId='overlay:[^']+'", RegexOption.IGNORE_CASE)
            var bestDist = Int.MAX_VALUE
            var found = false
            for (m in r.findAll(block)) {
                val dist = kotlin.math.abs(m.range.first - anchor)
                if (dist < bestDist) {
                    bestDist = dist
                    found = true
                }
                if (dist <= 300) return true
            }
            return found
        }

        for ((idx, marker) in idMarkers.withIndex()) {
            val id = marker.first
            val pos = marker.second
            val start = (pos - 600).coerceAtLeast(0)
            val end = if (idx + 1 < idMarkers.size) {
                idMarkers[idx + 1].second.coerceAtMost(dumpsys.length)
            } else {
                (pos + 1600).coerceAtMost(dumpsys.length)
            }
            if (end <= start) continue
            val block = dumpsys.substring(start, end)
            val anchor = (pos - start).coerceIn(0, block.length)

            val type = parseType(block, anchor)
            val name = parseName(block, anchor)
            results.add(
                DisplayScanResult(
                    displayId = id,
                    type = type,
                    name = name,
                    blockHasOverlayWord = block.contains("Overlay", ignoreCase = true),
                    hasOverlayUniqueId = hasOverlayUniqueId(block, anchor),
                    has720x1280 = has720x1280(block, anchor),
                )
            )
        }

        return results
    }

    private fun findOverlay720pDisplayId(dumpsys: String): Int? {
        val list = scanDisplays(dumpsys)
        fun isOverlayLike(d: DisplayScanResult): Boolean {
            if (d.type.equals("INTERNAL", ignoreCase = true)) return false
            if (d.type.equals("OVERLAY", ignoreCase = true)) return true
            if (d.type.equals("VIRTUAL", ignoreCase = true) && d.hasOverlayUniqueId) return true
            return false
        }
        val matched = list.filter {
            it.displayId != 0 &&
                isOverlayLike(it) &&
                (it.name.orEmpty().contains("Overlay", ignoreCase = true) || it.blockHasOverlayWord || it.hasOverlayUniqueId) &&
                it.has720x1280
        }.map { it.displayId }.sorted()
        return matched.firstOrNull()
    }

    private fun hasAnyOverlayDisplay(dumpsys: String): Boolean {
        val list = scanDisplays(dumpsys)
        return list.any {
            it.displayId != 0 &&
                !it.type.equals("INTERNAL", ignoreCase = true) &&
                (
                    it.type.equals("OVERLAY", ignoreCase = true) ||
                        (it.type.equals("VIRTUAL", ignoreCase = true) && it.hasOverlayUniqueId)
                ) &&
                (it.name.orEmpty().contains("Overlay", ignoreCase = true) || it.blockHasOverlayWord || it.hasOverlayUniqueId)
        }
    }

    fun isOverlay720pDisplayId(displayId: Int, dumpsys: String): Boolean {
        if (displayId == 0) return false
        val list = scanDisplays(dumpsys)
        return list.any {
            it.displayId == displayId &&
                !it.type.equals("INTERNAL", ignoreCase = true) &&
                (
                    it.type.equals("OVERLAY", ignoreCase = true) ||
                        (it.type.equals("VIRTUAL", ignoreCase = true) && it.hasOverlayUniqueId)
                ) &&
                (it.name.orEmpty().contains("Overlay", ignoreCase = true) || it.blockHasOverlayWord || it.hasOverlayUniqueId) &&
                it.has720x1280
        }
    }

    fun enableOverlayDisplay720p(): Int? {
        val before = readDumpsysDisplay()
        val existing = try {
            findOverlay720pDisplayId(before)
        } catch (_: Exception) {
            null
        }
        if (existing != null) {
            Log.i(TAG, "reused overlay displayId=$existing")
            return existing
        }

        try {
            val cur = runShell(listOf("settings", "get", "global", "overlay_display_devices"), timeoutMs = 4_000L)
            Log.i(TAG, "overlay_display_devices(before) exit=${cur.exitCode} value=${cur.output.trim()}")
        } catch (_: Exception) {
        }

        val put = runShell(
            listOf("settings", "put", "global", "overlay_display_devices", "720x1280/320"),
            timeoutMs = 6_000L,
        )
        Log.i(TAG, "overlay_display_devices(put) exit=${put.exitCode} out=${put.output.take(200)}")

        try {
            val afterPut = runShell(listOf("settings", "get", "global", "overlay_display_devices"), timeoutMs = 4_000L)
            Log.i(TAG, "overlay_display_devices(after_put) exit=${afterPut.exitCode} value=${afterPut.output.trim()}")
        } catch (_: Exception) {
        }

        var attempt = 0
        while (attempt < 16) {
            try {
                Thread.sleep(250L)
            } catch (_: Exception) {
            }
            val after = readDumpsysDisplay()
            val id = try {
                findOverlay720pDisplayId(after)
            } catch (_: Exception) {
                null
            }
            if (id != null) {
                Log.i(TAG, "found overlay displayId=$id")
                return id
            }
            if (attempt == 0 || attempt == 5 || attempt == 10 || attempt == 15) {
                val head = if (after.length <= 1200) after else after.take(1200)
                Log.i(TAG, "dumpsys display(head) attempt=$attempt:\n$head")
            }
            attempt++
        }

        return null
    }

    fun cleanup(): Boolean {
        val dumpsys = try {
            readDumpsysDisplay()
        } catch (_: Exception) {
            ""
        }

        val shouldCleanup = try {
            hasAnyOverlayDisplay(dumpsys)
        } catch (_: Exception) {
            false
        }

        if (!shouldCleanup) return true

        val r = runShell(
            listOf("settings", "put", "global", "overlay_display_devices", ""),
            timeoutMs = 6_000L,
        )
        return r.exitCode == 0
    }

    companion object {
        private const val TAG = "VirtualDisplay"
    }
}
