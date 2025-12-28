package com.example.autoglm
 
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.autoglm.vdiso.ShizukuVirtualDisplaySurfaceStreamer
import com.example.autoglm.vdiso.ShizukuVirtualDisplayEngine
 
class MonitorActivity : ComponentActivity() {

    private var uiHandler: Handler? = null
    private var uiTicker: Runnable? = null

    private var tvInfo: TextView? = null
    private var tvStreamingStatus: TextView? = null
    private var tvBytes: TextView? = null

    private var rvApps: RecyclerView? = null
    private var appAdapter: AppAdapter? = null

    @Volatile
    private var displayId: Int = 0

    @Volatile
    private var surfaceReady: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)
 
        tvInfo = findViewById(R.id.tvInfo)
        val svPreview = findViewById<SurfaceView>(R.id.svPreview)
        tvStreamingStatus = findViewById(R.id.tvStreamingStatus)
        tvBytes = findViewById(R.id.tvBytes)
        rvApps = findViewById(R.id.rvApps)
        val btnClose = findViewById<Button>(R.id.btnClose)
 
        btnClose.setOnClickListener { finish() }

        val did = try {
            VirtualDisplayController.ensureVirtualDisplayForMonitor(this)
        } catch (_: Exception) {
            null
        }
        displayId = did ?: 0

        try {
            val rv = rvApps
            if (rv != null) {
                rv.layoutManager = LinearLayoutManager(this)
                val adapter = AppAdapter(
                    onClick = { entry ->
                        val id = displayId
                        if (id <= 0) return@AppAdapter
                        threadStartAppOnDisplay(entry, id)
                    }
                )
                appAdapter = adapter
                rv.adapter = adapter
                loadLaunchableAppsAsync()
            }
        } catch (_: Exception) {
        }

        uiHandler = Handler(mainLooper)
        uiTicker = object : Runnable {
            override fun run() {
                try {
                    val started = ShizukuVirtualDisplaySurfaceStreamer.isStarted()
                    tvInfo?.text = "displayId=$displayId surfaceReady=$surfaceReady started=$started"
                } catch (_: Exception) {
                }
 
                try {
                    tvStreamingStatus?.text = if (ShizukuVirtualDisplaySurfaceStreamer.isStarted()) {
                        "VIRTUAL DISPLAY: ACTIVE (ID: $displayId)"
                    } else {
                        "VIRTUAL DISPLAY: INACTIVE (ID: $displayId)"
                    }
                } catch (_: Exception) {
                }
 
                try {
                    val s = ShizukuBridge.pingBinder() && ShizukuBridge.hasPermission()
                    tvBytes?.text = if (s) "SHIZUKU: OK" else "SHIZUKU: NOT READY"
                } catch (_: Exception) {
                }
 
                uiHandler?.postDelayed(this, 500)
            }
        }
        uiHandler?.post(uiTicker!!)
 
        svPreview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                val surface = holder.surface
                val r = ShizukuVirtualDisplaySurfaceStreamer.start(
                    ShizukuVirtualDisplaySurfaceStreamer.Args(
                        name = "AutoGLM-Virtual-Preview",
                        width = 1080,
                        height = 1920,
                        dpi = 440,
                        refreshRate = 0f,
                        rotatesWithContent = false,
                    ),
                    surface,
                )
                if (r.isSuccess) {
                    displayId = r.getOrNull() ?: displayId
                    runCatching { ShizukuVirtualDisplayEngine.ensureFocusedDisplay(displayId) }
                } else {
                    Log.w("MonitorActivity", "start virtual display(surface) failed", r.exceptionOrNull())
                }
            }
 
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            }
 
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                runCatching { ShizukuVirtualDisplaySurfaceStreamer.stop() }
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
        runCatching { ShizukuVirtualDisplaySurfaceStreamer.stop() }
        super.onDestroy()
    }

    private fun loadLaunchableAppsAsync() {
        Thread {
            val list = runCatching { queryLaunchableApps() }.getOrNull().orEmpty()
            runOnUiThread {
                try {
                    appAdapter?.submit(list)
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    private fun queryLaunchableApps(): List<AppEntry> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        val out = ArrayList<AppEntry>(resolved.size)
        for (ri in resolved) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName ?: continue
            val cls = ai.name ?: continue
            val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull().orEmpty().ifBlank { pkg }
            val icon = runCatching { ri.loadIcon(pm) }.getOrNull()
            val component = "$pkg/$cls"
            out.add(AppEntry(label = label, packageName = pkg, component = component, icon = icon))
        }
        out.sortWith(compareBy({ it.label.lowercase() }, { it.packageName }))
        return out
    }

    private fun threadStartAppOnDisplay(entry: AppEntry, displayId: Int) {
        Thread {
            try {
                if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) {
                    return@Thread
                }

                val flags = 0x10000000
                val candidates = listOf(
                    "cmd activity start-activity --user 0 --display $displayId --activity-reorder-to-front -n ${entry.component} -f $flags",
                    "cmd activity start-activity --user 0 --display $displayId -n ${entry.component} -f $flags",
                    "am start --user 0 -n ${entry.component} --display $displayId --activity-reorder-to-front -f $flags",
                    "am start --user 0 -n ${entry.component} --display $displayId -f $flags",
                )

                for (c in candidates) {
                    runCatching { ShizukuBridge.execText(c) }
                }

                runCatching { ShizukuVirtualDisplayEngine.ensureFocusedDisplay(displayId) }
            } catch (_: Exception) {
            }
        }.start()
    }

    data class AppEntry(
        val label: String,
        val packageName: String,
        val component: String,
        val icon: Drawable?,
    )

    private class AppAdapter(
        private val onClick: (AppEntry) -> Unit,
    ) : RecyclerView.Adapter<AppAdapter.VH>() {

        private val items = ArrayList<AppEntry>()

        fun submit(newItems: List<AppEntry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(android.R.layout.activity_list_item, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.label
            holder.icon.setImageDrawable(item.icon)
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(android.R.id.icon)
            val title: TextView = v.findViewById(android.R.id.text1)
        }
    }
}
