package com.example.pingmutest.display

import android.util.Log
import android.view.Surface

object VirtualDisplaySurfaceStreamer {

    private const val TAG = "VdSurfaceStreamer"

    @Volatile
    private var displayId: Int? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var lastArgs: ShizukuVirtualDisplayCreator.Args? = null

    @Volatile
    private var vdCallback: Any? = null

    @Synchronized
    fun start(args: ShizukuVirtualDisplayCreator.Args, surface: Surface): Result<Int> {
        return runCatching {
            val existing = displayId
            if (existing != null && this.surface != null) {
                Log.i(TAG, "start ignored (already started): displayId=$existing")
                return@runCatching existing
            }

            this.surface = surface
            lastArgs = args
            val r = ShizukuVirtualDisplayCreator.createVirtualDisplay(
                args = args,
                surface = surface,
                ownContentOnly = true,
            )
            vdCallback = r.callback
            displayId = r.displayId
            Log.i(TAG, "started: displayId=${r.displayId} size=${args.width}x${args.height}")
            r.displayId
        }
    }

    fun isStarted(): Boolean = displayId != null && surface != null

    fun getDisplayId(): Int? = displayId

    @Synchronized
    fun updateSurface(surface: Surface): Result<Unit> {
        val cb = vdCallback
            ?: return Result.failure(IllegalStateException("No virtual display callback"))
        return ShizukuVirtualDisplayCreator.setVirtualDisplaySurfaceBestEffort(cb, surface)
            .onSuccess {
                this.surface = surface
                Log.i(TAG, "updateSurface ok: displayId=$displayId")
            }
    }

    @Synchronized
    fun destroy(): Result<Unit> {
        val cb = vdCallback
            ?: return Result.failure(IllegalStateException("No virtual display callback"))
        return ShizukuVirtualDisplayCreator.releaseVirtualDisplayBestEffort(cb)
            .onSuccess {
                Log.i(TAG, "destroy ok: displayId=$displayId")
                stop()
            }
    }

    @Synchronized
    fun stop() {
        surface = null
        displayId = null
        lastArgs = null
        vdCallback = null
    }
}
