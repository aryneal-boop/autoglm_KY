package com.example.autoglm

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

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
