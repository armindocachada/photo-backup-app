package com.photobackup.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class ServerInfo(
    val host: String,
    val port: Int,
    val serverName: String
) {
    val baseUrl: String get() = "http://$host:$port"
}

@Singleton
class ServerDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ServerDiscovery"
        private const val SERVICE_TYPE = "_photobackup._tcp."
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var isDiscovering = false
    private var currentListener: NsdManager.DiscoveryListener? = null

    /**
     * Discover backup server on the network using mDNS.
     * Returns null if no server is found within the timeout.
     */
    suspend fun discoverServer(): ServerInfo? {
        return withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            discoverServerInternal()
        }
    }

    private suspend fun discoverServerInternal(): ServerInfo? {
        return suspendCancellableCoroutine { continuation ->
            var resolved = false

            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Resolve failed: $errorCode")
                    if (!resolved) {
                        resolved = true
                        continuation.resume(null)
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service resolved: ${serviceInfo.serviceName}")
                    val host = serviceInfo.host?.hostAddress
                    val port = serviceInfo.port
                    val serverName = serviceInfo.serviceName

                    if (!resolved && host != null) {
                        resolved = true
                        stopDiscovery()
                        continuation.resume(
                            ServerInfo(
                                host = host,
                                port = port,
                                serverName = serverName
                            )
                        )
                    }
                }
            }

            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery start failed: $errorCode")
                    isDiscovering = false
                    if (!resolved) {
                        resolved = true
                        continuation.resume(null)
                    }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery stop failed: $errorCode")
                    isDiscovering = false
                }

                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "Discovery started for: $serviceType")
                    isDiscovering = true
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "Discovery stopped for: $serviceType")
                    isDiscovering = false
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                    if (serviceInfo.serviceType.contains("_photobackup")) {
                        try {
                            nsdManager.resolveService(serviceInfo, resolveListener)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error resolving service", e)
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                }
            }

            currentListener = discoveryListener

            try {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting discovery", e)
                if (!resolved) {
                    resolved = true
                    continuation.resume(null)
                }
            }

            continuation.invokeOnCancellation {
                stopDiscovery()
            }
        }
    }

    fun stopDiscovery() {
        currentListener?.let { listener ->
            try {
                if (isDiscovering) {
                    nsdManager.stopServiceDiscovery(listener)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
        currentListener = null
        isDiscovering = false
    }

    /**
     * Try to connect to a server at a known address (for manual configuration).
     */
    suspend fun verifyServer(host: String, port: Int): ServerInfo? {
        // This would make an HTTP request to verify the server is reachable
        // For now, just return the server info
        return ServerInfo(
            host = host,
            port = port,
            serverName = "Manual"
        )
    }
}
