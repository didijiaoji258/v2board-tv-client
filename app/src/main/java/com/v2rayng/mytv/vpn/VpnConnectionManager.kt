package com.v2rayng.mytv.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.v2rayng.mytv.data.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnState {
    DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR
}

data class TrafficStats(
    val uploadSpeed: Long = 0,
    val downloadSpeed: Long = 0,
    val totalUpload: Long = 0,
    val totalDownload: Long = 0
)

class VpnConnectionManager(private val context: Context) {

    private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

    private val _trafficStats = MutableStateFlow(TrafficStats())
    val trafficStats: StateFlow<TrafficStats> = _trafficStats.asStateFlow()

    private var currentServer: ServerConfig? = null
    private var connectedAt: Long = 0L
    
    init {
        // ËönÝ¶: DISCONNECTED2bÛ«@¶‹Y	
        _vpnState.value = VpnState.DISCONNECTED
        _trafficStats.value = TrafficStats()
        connectedAt = 0L
        Log.i("VpnConnectionManager", "Initialized with clean state")
    }

    fun needVpnPermission(): Boolean = VpnService.prepare(context) != null

    fun getVpnPermissionIntent(): Intent? = VpnService.prepare(context)

    fun startVpn(server: ServerConfig, proxyMode: String = V2RayConfig.MODE_GLOBAL, userUuid: String = "") {
        val current = _vpnState.value
        if (current == VpnState.CONNECTING || current == VpnState.DISCONNECTING) {
            Log.w("VpnConnectionManager", "Busy ($current), ignoring startVpn request")
            return
        }
        currentServer = server
        _vpnState.value = VpnState.CONNECTING
        connectedAt = System.currentTimeMillis()
        try {
            val config = V2RayConfig.generateConfig(server, proxyMode, userUuid)
            val intent = Intent(context, TvVpnService::class.java).apply {
                action = TvVpnService.ACTION_START
                putExtra(TvVpnService.EXTRA_CONFIG, config)
                putExtra(TvVpnService.EXTRA_SERVER_NAME, server.displayName)
                putExtra(TvVpnService.EXTRA_SERVER_HOST, server.address)
                putExtra(TvVpnService.EXTRA_SERVER_PORT, server.port)
            }
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e("VpnConnectionManager", "Failed to start VPN: ${e.message}", e)
            _vpnState.value = VpnState.ERROR
        }
    }

    fun stopVpn() {
        _vpnState.value = VpnState.DISCONNECTING
        try {
            val intent = Intent(context, TvVpnService::class.java).apply {
                action = TvVpnService.ACTION_STOP
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e("VpnConnectionManager", "Failed to stop VPN: ${e.message}", e)
            _vpnState.value = VpnState.DISCONNECTED
        }
    }

    /**
     * Stop current VPN, then restart with new server/mode after [delayMs].
     * Does NOT block the calling thread.
     */
    fun restartVpn(server: ServerConfig, proxyMode: String, userUuid: String, delayMs: Long = 1200) {
        stopVpn()
        Thread {
            try {
                // Wait for DISCONNECTED (poll up to delayMs, then force-proceed)
                val deadline = System.currentTimeMillis() + delayMs
                while (_vpnState.value != VpnState.DISCONNECTED && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100)
                }
                if (_vpnState.value != VpnState.DISCONNECTED) {
                    // Force state so startVpn guard passes
                    _vpnState.value = VpnState.DISCONNECTED
                }
                startVpn(server, proxyMode, userUuid)
            } catch (e: Exception) {
                Log.e("VpnConnectionManager", "restartVpn thread error: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()
    }

    fun updateState(state: VpnState) {
        _vpnState.value = state
        if (state == VpnState.CONNECTED) connectedAt = System.currentTimeMillis()
        if (state == VpnState.DISCONNECTED) connectedAt = 0L
    }

    fun updateTrafficStats(stats: TrafficStats) { _trafficStats.value = stats }
    fun resetTrafficStats() { _trafficStats.value = TrafficStats() }
    fun getCurrentServer(): ServerConfig? = currentServer

    fun getConnectedSeconds(): Long {
        if (connectedAt == 0L || _vpnState.value != VpnState.CONNECTED) return 0
        return (System.currentTimeMillis() - connectedAt) / 1000
    }

    companion object {
        fun formatSpeed(bytesPerSecond: Long): String = when {
            bytesPerSecond < 1024 -> "${bytesPerSecond} B/s"
            bytesPerSecond < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024f)
            bytesPerSecond < 1024L * 1024 * 1024 -> "%.1f MB/s".format(bytesPerSecond / (1024f * 1024f))
            else -> "%.2f GB/s".format(bytesPerSecond / (1024f * 1024f * 1024f))
        }

        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
            else -> "%.2f GB".format(bytes / (1024f * 1024f * 1024f))
        }
    }
}