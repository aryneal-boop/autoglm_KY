package com.example.autoglm

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * ADB 无线调试端口扫描器（mDNS/NSD）。
 *
 * **用途**
 * - 通过 Android NSD（mDNS）发现局域网广播的无线调试服务端口：
 *   - `_adb-tls-pairing._tcp.`：配对端口（用于 `adb pair`）
 *   - `_adb-tls-connect._tcp.`：连接端口（用于 `adb connect`）
 *
 * **典型用法**
 * - 创建扫描器并在回调中拿到 [Endpoint]：
 *   - `AdbPortScanner(context, SERVICE_TYPE_CONNECT, onEndpoint = { ... }).start()`
 *
 * **引用路径（常见）**
 * - `PairingService`：配对/连接阶段扫描端口并重试。
 * - `AdbAutoConnectManager`：启动时扫描 connect 端口做自动连接。
 *
 * **使用注意事项**
 * - 为提升发现成功率，会尝试获取 `WifiManager.MulticastLock`；请在 `stop()` 中及时释放。
 * - NSD 在部分系统上不稳定：上层需要设计重试/超时策略（本项目已在 `PairingService` 中实现）。
 */
class AdbPortScanner(
    private val context: Context,
    private val serviceType: String = SERVICE_TYPE_CONNECT,
    private val onEndpoint: (endpoint: Endpoint) -> Unit,
    private val onError: (message: String, throwable: Throwable?) -> Unit = { _, _ -> }
) {

    data class Endpoint(
        val host: InetAddress,
        val port: Int,
        val serviceName: String
    ) {
        val hostAddress: String get() = host.hostAddress ?: host.toString()
    }

    private val nsdManager: NsdManager by lazy {
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolving = ConcurrentHashMap.newKeySet<String>()
    private val reported = ConcurrentHashMap.newKeySet<String>()

    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (discoveryListener != null) return

        tryAcquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "NSD 开始发现: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != serviceType) return

                val key = "${serviceInfo.serviceName}|${serviceInfo.serviceType}"
                if (!resolving.add(key)) return

                Log.i(TAG, "发现服务: name=${serviceInfo.serviceName}, type=${serviceInfo.serviceType}")
                resolve(serviceInfo, key)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "服务丢失: name=${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "NSD 停止发现: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
                releaseMulticastLock()
                val msg = "NSD 启动发现失败: type=$serviceType errorCode=$errorCode"
                Log.e(TAG, msg)
                onError(msg, null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                val msg = "NSD 停止发现失败: type=$serviceType errorCode=$errorCode"
                Log.e(TAG, msg)
                onError(msg, null)
            }
        }

        discoveryListener = listener

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            discoveryListener = null
            releaseMulticastLock()
            val msg = "调用 discoverServices 异常"
            Log.e(TAG, msg, t)
            onError(msg, t)
        }
    }

    fun stop() {
        val listener = discoveryListener ?: run {
            releaseMulticastLock()
            return
        }

        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (t: Throwable) {
            val msg = "调用 stopServiceDiscovery 异常"
            Log.e(TAG, msg, t)
            onError(msg, t)
        } finally {
            discoveryListener = null
            resolving.clear()
            reported.clear()
            releaseMulticastLock()
        }
    }

    private fun resolve(serviceInfo: NsdServiceInfo, key: String) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving.remove(key)
                val msg = "NSD 解析失败: name=${serviceInfo.serviceName} errorCode=$errorCode"
                Log.w(TAG, msg)
                onError(msg, null)
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                resolving.remove(key)

                val host = resolved.host
                val port = resolved.port
                if (host == null || port <= 0) {
                    val msg = "NSD 解析结果无效: host=$host port=$port"
                    Log.w(TAG, msg)
                    onError(msg, null)
                    return
                }

                val endpoint = Endpoint(
                    host = host,
                    port = port,
                    serviceName = resolved.serviceName ?: serviceInfo.serviceName
                )

                val reportKey = "${endpoint.hostAddress}:${endpoint.port}"
                if (!reported.add(reportKey)) return

                val kind = when (serviceType) {
                    SERVICE_TYPE_PAIRING -> "Pairing"
                    SERVICE_TYPE_CONNECT -> "Connect"
                    else -> serviceType
                }
                Log.i(TAG, "解析到 ADB TLS $kind 端点: ${endpoint.hostAddress}:${endpoint.port}")
                onEndpoint(endpoint)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nsdManager.resolveService(serviceInfo, context.mainExecutor, resolveListener)
            } else {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(serviceInfo, resolveListener)
            }
        } catch (t: Throwable) {
            resolving.remove(key)
            val msg = "调用 resolveService 异常"
            Log.e(TAG, msg, t)
            onError(msg, t)
        }
    }

    private fun tryAcquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("autoglm_nsd").apply { setReferenceCounted(false) }
            lock.acquire()
            multicastLock = lock
        } catch (t: Throwable) {
            Log.w(TAG, "获取 MulticastLock 失败（可能缺少权限或设备限制）: ${t.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Throwable) {
        } finally {
            multicastLock = null
        }
    }

    companion object {
        private const val TAG = "AutoglmAdb"
        const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp."
        const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp."
    }
}
