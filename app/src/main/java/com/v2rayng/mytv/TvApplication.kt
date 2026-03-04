package com.v2rayng.mytv

import android.app.Application
import android.content.Intent
import android.util.Log
import com.v2rayng.mytv.server.TvHttpServer
import com.v2rayng.mytv.vpn.TvVpnService
import com.v2rayng.mytv.vpn.VpnConnectionManager
import com.v2rayng.mytv.vpn.VpnServiceLocator

class TvApplication : Application() {

    lateinit var dataStore: com.v2rayng.mytv.data.TvDataStore
        private set

    private var _httpServer: TvHttpServer? = null
    val httpServer: TvHttpServer? get() = _httpServer

    override fun onCreate() {
        super.onCreate()
        cleanupZombieVpn()
        dataStore = com.v2rayng.mytv.data.TvDataStore(this)
        VpnServiceLocator.connectionManager = VpnConnectionManager(this)
        startHttpServer()
    }
    
    private fun cleanupZombieVpn() {
        try {
            Log.i("TvApplication", "Cleaning up zombie VPN connections...")
            val intent = Intent(this, TvVpnService::class.java).apply {
                action = TvVpnService.ACTION_STOP
            }
            stopService(intent)
            Log.i("TvApplication", "Zombie VPN cleanup completed")
        } catch (e: Exception) {
            Log.w("TvApplication", "Zombie VPN cleanup error (safe to ignore): ${e.message}")
        }
    }

    private fun startHttpServer() {
        try {
            val server = TvHttpServer(dataStore, 8866)
            server.start()
            _httpServer = server
            Log.i("TvApplication", "HTTP server started on :8866")
        } catch (e: Exception) {
            Log.e("TvApplication", "HTTP server :8866 failed: ${e.message}", e)
            try {
                val server2 = TvHttpServer(dataStore, 8867)
                server2.start()
                _httpServer = server2
                Log.i("TvApplication", "HTTP server started on :8867")
            } catch (e2: Exception) {
                Log.e("TvApplication", "HTTP server fallback failed: ${e2.message}", e2)
            }
        }
    }

    override fun onTerminate() {
        _httpServer?.stop()
        super.onTerminate()
    }
}