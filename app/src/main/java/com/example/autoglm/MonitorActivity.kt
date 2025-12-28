package com.example.autoglm
 
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
 
class MonitorActivity : ComponentActivity() {
 
    private var decodeThread: HandlerThread? = null
    private var decodeHandler: Handler? = null
 
    private var screenrecordProcess: Process? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
 
    private var uiHandler: Handler? = null
    private var uiTicker: Runnable? = null
 
    private var tvInfo: TextView? = null
    private var tvStreamingStatus: TextView? = null
    private var tvBytes: TextView? = null
 
    @Volatile
    private var displayId: Int = 0
 
    @Volatile
    private var physicalDisplayId: String = ""
 
    @Volatile
    private var adbExecPath: String = "adb"
 
    @Volatile
    private var frames: Long = 0L
 
    @Volatile
    private var bytesReadTotal: Long = 0L
 
    @Volatile
    private var processAlive: Boolean = false
 
    @Volatile
    private var processExitCode: Int? = null
 
    @Volatile
    private var lastError: String = ""
 
    @Volatile
    private var lastAdbArgv: String = ""
 
    @Volatile
    private var streamStopRequested: Boolean = false
 
    @Volatile
    private var streamThread: StreamThread? = null
 
    @Volatile
    private var lastDumpsysAtMs: Long = 0L
 
    @Volatile
    private var cachedDumpsysDisplay: String = ""

    @Volatile
    private var lastSurfaceFlingerAtMs: Long = 0L

    @Volatile
    private var cachedSurfaceFlingerDisplays: String = ""
 
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)
 
        tvInfo = findViewById(R.id.tvInfo)
        val svPreview = findViewById<SurfaceView>(R.id.svPreview)
        tvStreamingStatus = findViewById(R.id.tvStreamingStatus)
        tvBytes = findViewById(R.id.tvBytes)
        val btnClose = findViewById<Button>(R.id.btnClose)
 
        btnClose.setOnClickListener { finish() }
 
        adbExecPath = try {
            LocalAdb.resolveAdbExecPath(applicationContext)
        } catch (_: Exception) {
            "adb"
        }
 
        val did = try {
            VirtualDisplayController.ensureVirtualDisplayForMonitor(this)
        } catch (_: Exception) {
            null
        } ?: try {
            VirtualDisplayManager(applicationContext, adbExecPath).enableOverlayDisplay720p()
        } catch (_: Exception) {
            null
        }
        displayId = did ?: 0
 
        uiHandler = Handler(mainLooper)
        uiTicker = object : Runnable {
            override fun run() {
                val err = lastError
                val errText = if (err.isNotEmpty()) "\n$err" else ""
                val exitText = processExitCode?.toString() ?: "-"
                val pid = physicalDisplayId
                val pidText = if (pid.isNotBlank()) pid else "-"
 
                try {
                    tvInfo?.text = "logicalId=$displayId physicalId=$pidText\nframes=$frames  bytes=$bytesReadTotal  alive=$processAlive  exit=$exitText$errText"
                } catch (_: Exception) {
                }
 
                try {
                    val idText = if (pid.isNotBlank()) pid else displayId.toString()
                    tvStreamingStatus?.text = if (processAlive) {
                        "STREAMING: ACTIVE (ID: $idText)"
                    } else {
                        "STREAMING: INACTIVE (ID: $idText)"
                    }
                } catch (_: Exception) {
                }
 
                try {
                    tvBytes?.text = "BYTES: $bytesReadTotal"
                } catch (_: Exception) {
                }
 
                uiHandler?.postDelayed(this, 500)
            }
        }
        uiHandler?.post(uiTicker!!)
 
        svPreview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                startScreenrecordDecode()
            }
 
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            }
 
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopScreenrecordDecode()
                surface = null
            }
        })
    }
 
    override fun onDestroy() {
        val ticker = uiTicker
        if (ticker != null) {
            try {
                uiHandler?.removeCallbacks(ticker)
            } catch (_: Exception) {
            }
        }
        stopScreenrecordDecode()
        super.onDestroy()
    }
 
    private fun startScreenrecordDecode() {
        if (decodeThread != null) return
        val outSurface = surface ?: return
 
        streamStopRequested = false
        frames = 0L
        bytesReadTotal = 0L
        processExitCode = null
        lastError = ""
 
        decodeThread = HandlerThread("ScreenrecordDecode").apply { start() }
        decodeHandler = Handler(decodeThread!!.looper)
        decodeHandler?.post {
            try {
                runScreenrecordAndDecodeLoop(displayId, outSurface)
            } catch (t: Throwable) {
                lastError = "decode_error:${t.message}"
            }
        }
    }
 
    private fun stopScreenrecordDecode() {
        streamStopRequested = true
        try {
            screenrecordProcess?.destroy()
        } catch (_: Exception) {
        }
        screenrecordProcess = null
 
        try {
            streamThread?.interrupt()
        } catch (_: Exception) {
        }
        try {
            streamThread?.join(300)
        } catch (_: Exception) {
        }
        streamThread = null
 
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
 
        try {
            decodeThread?.quitSafely()
        } catch (_: Exception) {
        }
        decodeThread = null
        decodeHandler = null
 
        processAlive = false
    }

    private fun runScreenrecordAndDecodeLoop(logicalDisplayId: Int, outSurface: Surface) {
        val adbPath = adbExecPath
        val width = 720
        val height = 1280

        while (!streamStopRequested) {
            val candidates = LinkedHashSet<String>(resolveDisplayIdCandidates(logicalDisplayId, adbPath))
            val parseDiag = lastError
            var lastCandidateErr = ""

            var startedOk = false
            for (candidate in candidates) {
                if (streamStopRequested) break
                lastError = ""
                physicalDisplayId = candidate

                val baseArgs = arrayListOf(
                    "exec-out",
                    "screenrecord",
                    "--output-format",
                    "h264",
                    "--size",
                    "${width}x${height}",
                    "--bit-rate",
                    "4000000",
                    "--time-limit",
                    "180",
                )
                val args = ArrayList<String>(baseArgs)
                args.addAll(listOf("--display-id", candidate))
                args.add("-")

                val req = LocalAdb.buildAdbCommand(applicationContext, adbPath, args)
                lastAdbArgv = req.argv.joinToString(" ")
                Log.d(TAG, "adb argv: $lastAdbArgv")

                val pb = ProcessBuilder(req.argv)
                if (req.env.isNotEmpty()) {
                    pb.environment().putAll(req.env)
                }
                pb.redirectErrorStream(false)

                try {
                    val p = pb.start()
                    screenrecordProcess = p
                    processAlive = p.isAlive
                    processExitCode = null

                    val ok = startCodecAndPump(p, outSurface, width, height)
                    if (!ok) {
                        lastError = "screenrecord_start_failed"
                    }
                } catch (e: Exception) {
                    lastError = "screenrecord_exec_error:${e.message}"
                } finally {
                    try {
                        screenrecordProcess?.destroy()
                    } catch (_: Exception) {
                    }
                    screenrecordProcess = null
                    processAlive = false

                    try {
                        streamThread?.interrupt()
                    } catch (_: Exception) {
                    }
                    try {
                        streamThread?.join(300)
                    } catch (_: Exception) {
                    }
                    streamThread = null

                    try {
                        codec?.stop()
                    } catch (_: Exception) {
                    }
                    try {
                        codec?.release()
                    } catch (_: Exception) {
                    }
                    codec = null
                }

                val err = lastError
                if (err.isNotBlank()) lastCandidateErr = err
                if (err.contains("Invalid physical display ID", ignoreCase = true)) {
                    continue
                }

                startedOk = true
                break
            }

            if (!startedOk) {
                val candText = candidates.joinToString(",").take(220)
                val base = if (lastCandidateErr.isNotBlank()) lastCandidateErr else "screenrecord_no_working_display_id"
                lastError = if (parseDiag.isNotBlank()) {
                    "${parseDiag}\n${base}\ncandidates:$candText"
                } else {
                    "${base}\ncandidates:$candText"
                }
            }

            if (streamStopRequested) break
            try {
                Thread.sleep(500L)
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveDisplayIdCandidates(logicalDisplayId: Int, adbPath: String): List<String> {
        val out = LinkedHashSet<String>()

        fun addToken(raw: String?) {
            val t = raw?.trim().orEmpty()
            if (t.isBlank()) return
            out.add(t)

            val afterColon = t.substringAfter(':', missingDelimiterValue = "").trim()
            if (afterColon.isNotBlank() && afterColon != t) {
                out.add(afterColon)
            }

            val normalized = normalizePhysicalId(t)
            if (normalized.isNotBlank()) out.add(normalized)
            val normalizedAfter = normalizePhysicalId(afterColon)
            if (normalizedAfter.isNotBlank()) out.add(normalizedAfter)
        }

        // 1) dumpsys display: 尝试提取 mPhysicalDisplayId / uniqueId 等。
        if (logicalDisplayId > 0) {
            val dumpsys = getCachedDumpsysDisplay(adbPath)
            if (dumpsys.isNotBlank()) {
                val anchorRegexes = listOf(
                    Regex("mDisplayId\\s*=\\s*${logicalDisplayId}\\b", RegexOption.IGNORE_CASE),
                    Regex("\\bDisplay\\s+${logicalDisplayId}\\b"),
                    Regex("\\bdisplayId\\s*=\\s*${logicalDisplayId}\\b", RegexOption.IGNORE_CASE),
                )

                val anchors = ArrayList<Int>()
                for (r in anchorRegexes) {
                    for (m in r.findAll(dumpsys)) {
                        anchors.add(m.range.first)
                    }
                }
                anchors.sort()

                val physicalPatterns = listOf(
                    Regex("mPhysicalDisplayId\\s*=\\s*([0-9]+|0x[0-9a-fA-F]+)", RegexOption.IGNORE_CASE),
                    Regex("physicalDisplayId\\s*=\\s*([0-9]+|0x[0-9a-fA-F]+)", RegexOption.IGNORE_CASE),
                    Regex("\\bphysical\\s+display\\s+id\\s*[:=]\\s*([0-9]+|0x[0-9a-fA-F]+)", RegexOption.IGNORE_CASE),
                )

                val uniqueIdPatterns = listOf(
                    Regex("uniqueId='([^']+)'", RegexOption.IGNORE_CASE),
                    Regex("uniqueId=\\\"([^\\\"]+)\\\"", RegexOption.IGNORE_CASE),
                )

                for (anchor in anchors) {
                    val start = (anchor - 3000).coerceAtLeast(0)
                    val end = (anchor + 8000).coerceAtMost(dumpsys.length)
                    if (end <= start) continue
                    val block = dumpsys.substring(start, end)

                    for (p in physicalPatterns) {
                        val m = p.find(block) ?: continue
                        addToken(m.groupValues.getOrNull(1))
                    }

                    for (p in uniqueIdPatterns) {
                        val m = p.find(block) ?: continue
                        val uid = m.groupValues.getOrNull(1).orEmpty()
                        addToken(uid)
                    }
                }

                if (out.isEmpty()) {
                    val head = dumpsys.take(600)
                    lastError = "no_physical_id_in_dumpsys(head):${head.replace("\n", " ").take(250)}"
                }
            }
        }

        // 2) dumpsys SurfaceFlinger(--displays): 在 MIUI 上经常是 screenrecord 需要的物理 token 来源。
        val sf = getCachedSurfaceFlingerDisplays(adbPath)
        if (sf.isNotBlank()) {
            val hexTokens = Regex("0x[0-9a-fA-F]+")
                .findAll(sf)
                .map { it.value }
                .distinct()
                .take(32)
                .toList()
            for (t in hexTokens) addToken(t)

            if (hexTokens.isEmpty() && out.isEmpty()) {
                val head = sf.take(600)
                val diag = "no_hex_token_in_sf(head):${head.replace("\n", " ").take(250)}"
                lastError = if (lastError.isNotBlank()) {
                    "${lastError}\n${diag}"
                } else {
                    diag
                }
            }
        }

        // 3) 兜底（仍然必须带 --display-id）。
        out.add(logicalDisplayId.coerceAtLeast(0).toString())
        out.add("0")
        out.add("0x0")

        return out.toList()
    }

    private fun getCachedSurfaceFlingerDisplays(adbPath: String): String {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedSurfaceFlingerDisplays
        if (cached.isNotBlank() && now - lastSurfaceFlingerAtMs < 2_000L) {
            return cached
        }

        fun runSf(args: List<String>): String {
            val req = LocalAdb.buildAdbCommand(
                applicationContext,
                adbPath,
                listOf("shell") + args,
            )
            return LocalAdb.runCommand(applicationContext, req, timeoutMs = 8_000L).output
        }

        val outText = runCatching {
            val t = runSf(listOf("dumpsys", "SurfaceFlinger", "--displays"))
            if (t.isNotBlank() && !t.contains("Unknown option", ignoreCase = true)) t else ""
        }.getOrNull().orEmpty().ifBlank {
            runCatching { runSf(listOf("dumpsys", "SurfaceFlinger")) }.getOrNull().orEmpty()
        }

        cachedSurfaceFlingerDisplays = outText
        lastSurfaceFlingerAtMs = now
        return outText
    }
 
    private fun getCachedDumpsysDisplay(adbPath: String): String {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedDumpsysDisplay
        if (cached.isNotBlank() && now - lastDumpsysAtMs < 2_000L) {
            return cached
        }
 
        val dumpsys = runCatching {
            val req = LocalAdb.buildAdbCommand(
                applicationContext,
                adbPath,
                listOf("shell", "dumpsys", "display"),
            )
            LocalAdb.runCommand(applicationContext, req, timeoutMs = 8_000L).output
        }.getOrNull().orEmpty()
 
        cachedDumpsysDisplay = dumpsys
        lastDumpsysAtMs = now
        return dumpsys
    }
 
    private fun normalizePhysicalId(token: String): String {
        val t = token.trim()
        if (t.isBlank()) return ""
        return if (t.startsWith("0x", ignoreCase = true)) {
            val hex = t.substring(2)
            runCatching {
                java.lang.Long.parseUnsignedLong(hex, 16).toString()
            }.getOrNull().orEmpty()
        } else {
            t
        }
    }
 
    private fun startCodecAndPump(
        process: Process,
        outSurface: Surface,
        width: Int,
        height: Int,
    ): Boolean {
        val queue = LinkedBlockingQueue<ByteArray>(128)
        val input = BufferedInputStream(process.inputStream, 256 * 1024)
        val st = StreamThread(input, queue)
        streamThread = st
        try {
            st.start()
        } catch (_: Exception) {
            return false
        }
 
        val errThread = Thread {
            try {
                val eb = ByteArray(4096)
                val sb = StringBuilder()
                while (true) {
                    val n = process.errorStream.read(eb)
                    if (n <= 0) break
                    val part = runCatching { String(eb, 0, n, Charsets.UTF_8) }.getOrNull().orEmpty()
                    if (part.isNotEmpty()) {
                        sb.append(part)
                        val t = sb.toString().trim()
                        if (t.isNotEmpty()) {
                            lastError = t.takeLast(600)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        errThread.isDaemon = true
        errThread.start()
 
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var configured = false
        val stash = ArrayList<ByteArray>(64)
        val collector = ByteArrayOutputStreamLike(512 * 1024)
 
        var firstNonH264Hinted = false
        var dumpedShortStdout = false
 
        while (!streamStopRequested) {
            processAlive = try {
                process.isAlive
            } catch (_: Exception) {
                false
            }
 
            val chunk = try {
                queue.poll(350, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                null
            }
 
            if (chunk == null) {
                if (!processAlive && queue.isEmpty()) break
                continue
            }
 
            if (chunk.isEmpty()) break
 
            bytesReadTotal += chunk.size.toLong()
            collector.write(chunk, 0, chunk.size)
 
            if (!dumpedShortStdout && bytesReadTotal in 1..256) {
                val sampleHex = collector.peekAsHex(limit = 256)
                val sampleText = collector.peekAsText(limit = 256)
                Log.d(TAG, "stdout_sample_hex:$sampleHex")
                if (sampleText.isNotBlank()) {
                    Log.d(TAG, "stdout_sample_text:$sampleText")
                }
                if (!collector.maybeHasStartCode()) {
                    val t = if (sampleText.isNotBlank()) sampleText else sampleHex
                    lastError = "stdout_sample:$t"
                    dumpedShortStdout = true
                }
            }
 
            if (!configured && !firstNonH264Hinted && bytesReadTotal in 1..256) {
                if (!collector.maybeHasStartCode()) {
                    val t = collector.peekAsText(limit = 256)
                    if (t.isNotBlank()) {
                        lastError = "stdout_text:$t"
                        firstNonH264Hinted = true
                    }
                }
            }
 
            while (true) {
                val nal = collector.pollNal() ?: break
                val type = nalType(nal)
                if (type == 7) sps = nal
                if (type == 8) pps = nal
 
                if (!configured) {
                    stash.add(nal)
                    if (sps != null && pps != null) {
                        val c = MediaCodec.createDecoderByType("video/avc")
                        val fmt = MediaFormat.createVideoFormat("video/avc", width, height)
                        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps!!)))
                        fmt.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps!!)))
                        c.configure(fmt, outSurface, null, 0)
                        c.start()
                        codec = c
                        configured = true
                        for (x in stash) {
                            queueNal(c, x)
                        }
                        stash.clear()
                    }
                    continue
                }
 
                val c = codec ?: return false
                if (!queueNal(c, nal)) {
                    return false
                }
 
                drainOutput(c)
                frames++
            }
        }
 
        processExitCode = try {
            if (!process.isAlive) process.exitValue() else null
        } catch (_: Exception) {
            null
        }
 
        if (!configured) return bytesReadTotal > 0
 
        val c = codec
        if (c != null) {
            try {
                drainOutput(c)
            } catch (_: Exception) {
            }
        }
        return true
    }
 
    private class StreamThread(
        private val input: BufferedInputStream,
        private val queue: LinkedBlockingQueue<ByteArray>,
    ) : Thread("StreamThread") {
        override fun run() {
            val buf = ByteArray(64 * 1024)
            try {
                while (!isInterrupted) {
                    val n = try {
                        input.read(buf)
                    } catch (_: Exception) {
                        -1
                    }
                    if (n <= 0) break
                    val chunk = buf.copyOfRange(0, n)
                    queue.put(chunk)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    queue.put(ByteArray(0))
                } catch (_: Exception) {
                }
            }
        }
    }
 
    private fun drainOutput(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = c.dequeueOutputBuffer(info, 0)
            if (outIndex >= 0) {
                c.releaseOutputBuffer(outIndex, true)
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    Log.d(TAG, "MediaCodec output format changed: ${c.outputFormat}")
                } catch (_: Exception) {
                }
            } else {
                return
            }
        }
    }
 
    private fun queueNal(c: MediaCodec, nal: ByteArray): Boolean {
        val idx = c.dequeueInputBuffer(10_000)
        if (idx < 0) return true
        val ib = c.getInputBuffer(idx) ?: return false
        ib.clear()
        val bytes = withStartCode(nal)
        if (bytes.size > ib.remaining()) {
            return false
        }
        ib.put(bytes)
        c.queueInputBuffer(idx, 0, bytes.size, System.nanoTime() / 1000, 0)
        return true
    }
 
    private fun nalType(nal: ByteArray): Int {
        if (nal.isEmpty()) return -1
        return nal[0].toInt() and 0x1F
    }
 
    private fun withStartCode(nal: ByteArray): ByteArray {
        val sc = byteArrayOf(0, 0, 0, 1)
        val out = ByteArray(sc.size + nal.size)
        System.arraycopy(sc, 0, out, 0, sc.size)
        System.arraycopy(nal, 0, out, sc.size, nal.size)
        return out
    }
 
    private class ByteArrayOutputStreamLike(capacity: Int) {
        private var buf = ByteArray(capacity)
        private var len = 0
 
        fun write(src: ByteArray, off: Int, n: Int) {
            ensure(len + n)
            System.arraycopy(src, off, buf, len, n)
            len += n
        }
 
        private fun ensure(target: Int) {
            if (target <= buf.size) return
            var newCap = buf.size
            while (newCap < target) newCap *= 2
            buf = buf.copyOf(newCap)
        }
 
        fun pollNal(): ByteArray? {
            if (len < 4) return null
            val first = findStartCode(0) ?: return null
            val start = first.first
            val scLen = first.second
            val next = findStartCode(start + scLen) ?: return null
            val nextStart = next.first
            val nal = buf.copyOfRange(start + scLen, nextStart)
            compact(nextStart)
            return nal
        }
 
        fun maybeHasStartCode(): Boolean {
            return findStartCode(0) != null
        }
 
        fun peekAsText(limit: Int): String {
            val n = if (len < limit) len else limit
            if (n <= 0) return ""
            return runCatching { String(buf, 0, n, Charsets.UTF_8) }.getOrNull().orEmpty().trim()
        }
 
        fun peekAsHex(limit: Int): String {
            val n = if (len < limit) len else limit
            if (n <= 0) return ""
            val sb = StringBuilder(n * 2)
            var i = 0
            while (i < n) {
                val v = buf[i].toInt() and 0xFF
                val h = v.toString(16).padStart(2, '0')
                sb.append(h)
                i++
            }
            return sb.toString()
        }
 
        private fun findStartCode(from: Int): Pair<Int, Int>? {
            var i = from
            while (i + 3 <= len) {
                if (i + 4 <= len && buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() && buf[i + 2] == 0.toByte() && buf[i + 3] == 1.toByte()) {
                    return Pair(i, 4)
                }
                if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() && buf[i + 2] == 1.toByte()) {
                    return Pair(i, 3)
                }
                i++
            }
            return null
        }
 
        private fun compact(from: Int) {
            val remain = len - from
            if (remain > 0) {
                System.arraycopy(buf, from, buf, 0, remain)
            }
            len = remain
        }
    }
 
    private companion object {
        private const val TAG = "AutoGLM_Stream"
    }
}
