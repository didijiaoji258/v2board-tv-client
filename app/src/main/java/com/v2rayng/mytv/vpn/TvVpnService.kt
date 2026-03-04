package com.v2rayng.mytv.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.v2rayng.mytv.MainActivity
import com.v2rayng.mytv.R
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch

class TvVpnService : VpnService(), CoreCallbackHandler {

    companion object {
        private const val TAG = "TvVpnService"
        const val ACTION_START = "com.v2rayng.mytv.START_VPN"
        const val ACTION_STOP  = "com.v2rayng.mytv.STOP_VPN"
        const val EXTRA_CONFIG      = "extra_config"
        const val EXTRA_SERVER_NAME = "extra_server_name"
        const val EXTRA_SERVER_HOST = "extra_server_host"
        const val EXTRA_SERVER_PORT = "extra_server_port"
        private const val SOCKS5_PORT = 10808
        private const val CHANNEL_ID      = "tv_vpn_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var coreController: CoreController? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2Socks: Tun2Socks? = null
    private var trafficTimer: Timer? = null
    private var lastUplink   = 0L
    private var lastDownlink = 0L

    @Volatile private var userStopped = false
    private var shutdownLatch: CountDownLatch? = null

    override fun onCreate() {
        super.onCreate()
        try {
            Seq.setContext(this)
            copyAssetIfNeeded("geoip.dat")
            copyAssetIfNeeded("geosite.dat")
            Libv2ray.initCoreEnv(filesDir.absolutePath, filesDir.absolutePath)
            Log.i(TAG, "xray-core version: ${Libv2ray.checkVersionX()}")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: ${e.message}", e)
        }
    }

    private fun copyAssetIfNeeded(name: String) {
        val dest = File(filesDir, name)
        if (dest.exists()) return
        try {
            assets.open(name).use { inp -> dest.outputStream().use { inp.copyTo(it) } }
            Log.i(TAG, "Copied $name → filesDir")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot copy $name: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config     = intent.getStringExtra(EXTRA_CONFIG)     ?: return START_NOT_STICKY
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "VPN"
                userStopped = false
                
                // ★ 启动前先清理可能残留的资源（防止状态不一致）
                try {
                    cleanupInternal()
                    Log.i(TAG, "Pre-start cleanup completed")
                } catch (e: Exception) {
                    Log.w(TAG, "Pre-start cleanup error: ${e.message}")
                }
                
                startVpn(config, serverName)
            }
            ACTION_STOP -> {
                userStopped = true
                stopVpn()
            }
            else -> {
                Log.w(TAG, "null intent, stopping self")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(config: String, serverName: String) {
        cleanupInternal()
        createNotificationChannel()
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE 仅 API 34(Android 14) 引入；
        // 旧版本和TV盒子直接用2参数即可
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification("正在连接 $serverName..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("正在连接 $serverName..."))
        }

        Thread({
            try {
                // ① 创建 xray-core 控制器
                val ctrl = try {
                    Libv2ray.newCoreController(this@TvVpnService)
                } catch (e: Throwable) {
                    Log.e(TAG, "newCoreController: ${e.message}", e)
                    VpnServiceLocator.connectionManager.updateState(VpnState.ERROR)
                    stopSelfSafe(); return@Thread
                }
                coreController = ctrl

                // ② 启动 xray-core (SOCKS5 代理模式，不传 TUN fd = 0)
                //    config 中包含 SOCKS5 inbound 监听 127.0.0.1:10808
                val latch = CountDownLatch(1)
                shutdownLatch = latch

                Log.i(TAG, "Starting xray-core in SOCKS5 mode...")
                try {
                    ctrl.startLoop(config, 0)
                    Log.i(TAG, "startLoop returned OK")
                } catch (e: Throwable) {
                    Log.e(TAG, "startLoop FAILED: ${e.message}", e)
                    latch.countDown()
                    VpnServiceLocator.connectionManager.updateState(VpnState.ERROR)
                    return@Thread
                }

                // ③ 等 xray-core SOCKS5 端口就绪（give goroutine time to bind port）
                Thread.sleep(300)

                // ④ 建立 TUN 接口
                //    我们的 app 排除在 VPN 路由之外（addDisallowedApplication），
                //    这样 xray-core 的出站连接直接走物理网卡，不进 TUN，避免路由回环。
                val iface = buildTunInterface() ?: run {
                    Log.e(TAG, "TUN establish failed")
                    ctrl.stopLoop()
                    VpnServiceLocator.connectionManager.updateState(VpnState.ERROR)
                    stopSelfSafe(); return@Thread
                }
                vpnInterface = iface
                Log.i(TAG, "TUN established fd=${iface.fd}")

                // ⑤ 启动 Tun2Socks：TUN fd → SOCKS5:10808 → xray-core → 代理服务器
                //    Tun2Socks 在我们的进程中运行（app 已排除于 VPN），
                //    连到 127.0.0.1:10808 走 loopback，不受 VPN 路由影响。
                val t2s = Tun2Socks(this@TvVpnService, iface, SOCKS5_PORT)
                t2s.start()
                tun2Socks = t2s

                // ⑥ 上报已连接
                VpnServiceLocator.connectionManager.updateState(VpnState.CONNECTED)
                updateNotification("已连接 $serverName")
                startTrafficStats()
                Log.i(TAG, "VPN CONNECTED (Tun2Socks → SOCKS5:$SOCKS5_PORT → xray-core)")

                // ⑦ 等待关闭信号
                latch.await()
                Log.i(TAG, "Shutdown signal received")

            } catch (e: Throwable) {
                if (!userStopped) Log.e(TAG, "VPN thread error: ${e.message}", e)
            } finally {
                val wasUser = userStopped
                Log.i(TAG, "VPN thread ending userStopped=$wasUser")
                cleanupInternal()
                val curState = VpnServiceLocator.connectionManager.vpnState.value
                if (curState == VpnState.CONNECTED || curState == VpnState.CONNECTING) {
                    VpnServiceLocator.connectionManager.updateState(
                        if (wasUser) VpnState.DISCONNECTED else VpnState.ERROR
                    )
                }
                VpnServiceLocator.connectionManager.resetTrafficStats()
                stopSelfSafe()
            }
        }, "vpn-core").start()
    }

    private fun buildTunInterface(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setSession("TvVPN")
                .setMtu(1500)
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)          // 全部 IPv4 进 TUN
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .also { b ->
                    // ★ 关键：将本 APP 排除在 VPN 路由之外
                    //   效果：xray-core 的出站连接直接走物理网卡，不进 TUN，
                    //   彻底解决路由回环问题。
                    //   Tun2Socks 通过 fd 直接读写 TUN，不受此设置影响。
                    try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}
                }
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "buildTunInterface: ${e.message}", e)
            null
        }
    }

    private fun cleanupInternal() {
        shutdownLatch?.countDown(); shutdownLatch = null
        try { trafficTimer?.cancel(); trafficTimer = null } catch (_: Exception) {}
        try { tun2Socks?.stop(); tun2Socks = null } catch (_: Exception) {}
        try { coreController?.stopLoop(); coreController = null } catch (_: Exception) {}
        try { vpnInterface?.close(); vpnInterface = null } catch (_: Exception) {}
    }

    private fun stopVpn() {
        Log.i(TAG, "stopVpn")
        cleanupInternal()
        VpnServiceLocator.connectionManager.updateState(VpnState.DISCONNECTED)
        VpnServiceLocator.connectionManager.resetTrafficStats()
        stopSelfSafe()
    }

    private fun stopSelfSafe() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun startTrafficStats() {
        lastUplink = 0L; lastDownlink = 0L
        trafficTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    try {
                        val ctrl = coreController ?: return
                        val up   = ctrl.queryStats("proxy", "uplink")
                        val down = ctrl.queryStats("proxy", "downlink")
                        val upSpeed   = if (lastUplink   > 0) up   - lastUplink   else 0
                        val downSpeed = if (lastDownlink > 0) down - lastDownlink else 0
                        lastUplink = up; lastDownlink = down
                        VpnServiceLocator.connectionManager.updateTrafficStats(
                            TrafficStats(upSpeed, downSpeed, up, down)
                        )
                    } catch (_: Exception) {}
                }
            }, 1000, 1000)
        }
    }

    // ---- CoreCallbackHandler ----
    override fun startup(): Long { Log.d(TAG, "startup()"); return 0 }

    override fun shutdown(): Long {
        Log.w(TAG, "shutdown() callback — xray-core stopped")
        shutdownLatch?.countDown()
        return 0
    }

    override fun onEmitStatus(status: Long, msg: String?): Long {
        Log.d(TAG, "status=$status msg=$msg"); return 0
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "VPN Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TV VPN").setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
        catch (_: Exception) {}
    }

    override fun onDestroy() { Log.i(TAG, "onDestroy"); cleanupInternal(); super.onDestroy() }
}