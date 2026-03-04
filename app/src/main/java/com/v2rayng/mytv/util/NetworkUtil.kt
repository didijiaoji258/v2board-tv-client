package com.v2rayng.mytv.util

import android.content.Context
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtil {

    private const val TAG = "NetworkUtil"

    fun getLocalIpAddress(context: Context): String =
        getAllLocalIpAddresses().firstOrNull() ?: "0.0.0.0"

    /**
     * 枚举所有有效局域网 IPv4 地址，按接口优先级排序：
     * eth* (以太网) > wlan* (WiFi) > 其余（跳过 VPN/loopback/rmnet/tun）
     *
     * 不过滤未知接口名，确保覆盖各厂商命名差异（ap0、swlan0、ra0 等）
     */
    fun getAllLocalIpAddresses(): List<String> {
        val result = mutableListOf<Pair<Int, String>>() // (priority, ip)

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in interfaces) {
                try {
                    if (!ni.isUp || ni.isLoopback) continue
                    val name = ni.name.lowercase()

                    // 明确跳过 VPN/隧道/移动数据/回环
                    if (name.startsWith("tun") || name.startsWith("tap") ||
                        name.startsWith("ppp") || name.startsWith("lo") ||
                        name.startsWith("rmnet") || name.startsWith("dummy") ||
                        name.startsWith("v4-") || name.startsWith("sit")) continue

                    val priority = when {
                        name.startsWith("eth") -> 0    // 以太网（TV 最常用）
                        name.startsWith("wlan") -> 1   // WiFi
                        name.startsWith("en") -> 2      // 部分设备命名
                        else -> 5                       // 其他接口（ap0、swlan0、ra0 等）
                    }

                    for (addr in ni.inetAddresses) {
                        if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("169.254")) continue // APIPA
                        Log.i(TAG, "Candidate IP: $ip  interface: ${ni.name}  priority: $priority")
                        result.add(priority to ip)
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAllLocalIpAddresses error: ${e.message}", e)
        }

        val sorted = result.sortedBy { it.first }.map { it.second }
        Log.i(TAG, "All IPs sorted: $sorted")
        return sorted
    }
}