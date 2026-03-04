package com.v2rayng.mytv.server

import android.util.Log
import com.v2rayng.mytv.data.SyncData
import com.v2rayng.mytv.data.TvDataStore
import com.v2rayng.mytv.util.GsonUtil
import com.v2rayng.mytv.vpn.VpnServiceLocator
import com.v2rayng.mytv.vpn.VpnState
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class TvHttpServer(
    private val dataStore: TvDataStore,
    private val port: Int = 8866
) {
    companion object {
        private const val TAG = "TvHttpServer"
    }

    private val gson = GsonUtil.gson
    private val executor = Executors.newCachedThreadPool()

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    var onPaired: (() -> Unit)? = null
    var onModeChanged: (() -> Unit)? = null
    var onNodeChanged: (() -> Unit)? = null

    // 调试用：保存最后一次接收的原始 body
    @Volatile var lastRawBody: String = ""
    @Volatile var lastPath: String = ""

    fun start() {
        if (running) return
        running = true
        executor.submit {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                Log.i(TAG, "HTTP server listening on :$port")
                while (running) {
                    try {
                        val client = ss.accept()
                        executor.submit { handleClient(client) }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ServerSocket error: ${e.message}", e)
            }
        }
    }

    fun stop() {
        running = false
        serverSocket?.close()
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val input = socket.getInputStream()
            val out = socket.getOutputStream()

            val requestLine = readCrlfLine(input) ?: return
            Log.d(TAG, ">>> $requestLine")

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readCrlfLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?")

            val body = if (method == "POST") readBody(input, headers) else ""
            if (method == "POST") {
                lastRawBody = body
                lastPath = path
            }

            Log.i(TAG, "$method $path  body=${body.length}bytes  headers=${headers.keys}")

            val response = when {
                method == "POST" && path == "/api/pair"    -> handlePair(body)
                method == "POST" && path == "/api/sync"    -> handleSync(body)
                method == "POST" && path == "/api/command" -> handleCommand(body)
                method == "GET"  && path == "/api/status"  -> handleStatus()
                method == "GET"  && path == "/api/debug"   -> handleDebug()
                else -> httpJson(404, """{"error":"Not found","path":"$path"}""")
            }

            out.write(response)
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "handleClient error: ${e.message}", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * 读取 POST body，支持：
     * 1. Content-Length（精确读取N字节）
     * 2. Transfer-Encoding: chunked（解块）
     * 3. 无头信息时读至连接关闭
     */
    private fun readBody(input: java.io.InputStream, headers: Map<String, String>): String {
        val transferEncoding = headers["transfer-encoding"] ?: ""
        val contentLength = headers["content-length"]?.toIntOrNull() ?: -1

        return when {
            transferEncoding.contains("chunked", ignoreCase = true) -> {
                Log.d(TAG, "readBody: chunked mode")
                val sb = StringBuilder()
                while (true) {
                    val sizeLine = readCrlfLine(input) ?: break
                    val chunkSize = sizeLine.trim().toIntOrNull(16) ?: break
                    if (chunkSize == 0) break
                    val chunk = ByteArray(chunkSize)
                    var read = 0
                    while (read < chunkSize) {
                        val n = input.read(chunk, read, chunkSize - read)
                        if (n == -1) break
                        read += n
                    }
                    sb.append(String(chunk, 0, read, Charsets.UTF_8))
                    readCrlfLine(input) // consume trailing CRLF after chunk
                }
                Log.d(TAG, "chunked body len=${sb.length}")
                sb.toString()
            }
            contentLength > 0 -> {
                Log.d(TAG, "readBody: fixed length=$contentLength")
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n == -1) break
                    read += n
                }
                val result = String(buf, 0, read, Charsets.UTF_8)
                Log.d(TAG, "fixed body read=$read bytes")
                result
            }
            else -> {
                // 读到连接关闭为止（兜底）
                Log.d(TAG, "readBody: read until close")
                val bytes = input.readBytes()
                String(bytes, Charsets.UTF_8)
            }
        }
    }

    private fun readCrlfLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (prev == '\r'.code && sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    private fun handlePair(body: String): ByteArray {
        Log.i(TAG, "PAIR body=${body.length}  preview=${body.take(500)}")
        if (body.isEmpty()) return httpJson(400, """{"success":false,"error":"empty body"}""")
        val data: SyncData? = try {
            gson.fromJson(body, SyncData::class.java)
        } catch (e: Throwable) {
            Log.e(TAG, "PAIR parse error: ${e.message}", e)
            null
        }
        if (data == null) return httpJson(400, """{"success":false,"error":"json parse failed"}""")
        Log.i(TAG, "PAIR ok  servers=${data.servers.size}  uuid=${data.userUuid.take(8)}")
        dataStore.saveSyncData(data)
        onPaired?.invoke()
        return httpJson(200, """{"success":true,"serverCount":${data.servers.size},"isPaired":true}""")
    }

    private fun handleSync(body: String): ByteArray {
        Log.i(TAG, "SYNC body=${body.length}")
        if (body.isEmpty()) return httpJson(400, """{"success":false,"error":"empty body"}""")
        val data: SyncData? = try {
            gson.fromJson(body, SyncData::class.java)
        } catch (e: Throwable) {
            Log.e(TAG, "SYNC parse error: ${e.message}", e)
            null
        }
        if (data == null) return httpJson(400, """{"success":false,"error":"json parse failed"}""")
        Log.i(TAG, "SYNC ok  servers=${data.servers.size}")
        dataStore.saveSyncData(data)
        val state = VpnServiceLocator.connectionManager.vpnState.value.name
        return httpJson(200, """{"success":true,"vpnState":"$state","serverCount":${data.servers.size}}""")
    }

    private fun handleCommand(body: String): ByteArray {
        data class Cmd(val action: String = "", val serverIndex: Int? = null, val serverId: Int? = null, val proxyMode: String? = null)
        val cmd = try { gson.fromJson(body, Cmd::class.java) } catch (_: Throwable) { Cmd() }
        val mgr = VpnServiceLocator.connectionManager
        when (cmd.action) {
            "connect" -> {
                dataStore.selectedServer?.let { mgr.startVpn(it, dataStore.proxyMode, dataStore.userUuid) }
                // 等待 VPN 状态稳定（最多5秒），让响应携带真实状态
                val deadline = System.currentTimeMillis() + 5000L
                while (mgr.vpnState.value == VpnState.CONNECTING && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200)
                }
            }
            "disconnect" -> {
                mgr.stopVpn()
                // 等待断开完成（最多3秒）
                val deadline = System.currentTimeMillis() + 3000L
                while (mgr.vpnState.value == VpnState.DISCONNECTING && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200)
                }
            }
            "switch_node" -> {
                // 支持 serverIndex 和 serverId 两种方式
                val servers = dataStore.servers
                val idx = when {
                    cmd.serverIndex != null -> cmd.serverIndex
                    cmd.serverId != null -> servers.indexOfFirst { it.serverId == cmd.serverId }.let { if (it < 0) 0 else it }
                    else -> 0
                }
                dataStore.selectedServerIndex = idx
                VpnServiceLocator.setSelectedServerIndex(idx)  // 实时通知 TV UI
                onNodeChanged?.invoke()
                // 如果 VPN 已连接，用异步 restartVpn（不阻塞 HTTP 线程）
                if (mgr.vpnState.value == VpnState.CONNECTED) {
                    dataStore.selectedServer?.let { mgr.restartVpn(it, dataStore.proxyMode, dataStore.userUuid) }
                }
            }
            "switch_mode" -> cmd.proxyMode?.let { raw ->
                // 归一化：支持 "global"/"global_mode"/"GLOBAL"/"全局" → "global"
                //         "rule"/"rule_mode"/"RULE"/"规则" → "rule"
                val newMode = when {
                    raw.lowercase().contains("global") -> "global"
                    raw.lowercase().contains("rule")   -> "rule"
                    else -> raw.lowercase()
                }
                dataStore.proxyMode = newMode
                VpnServiceLocator.setProxyMode(newMode)  // 实时通知 TV UI
                onModeChanged?.invoke()
                // 如果 VPN 已连接，用异步 restartVpn（不阻塞 HTTP 线程）
                if (mgr.vpnState.value == VpnState.CONNECTED) {
                    dataStore.selectedServer?.let { mgr.restartVpn(it, newMode, dataStore.userUuid) }
                }
            }
        }
        // 立即响应当前状态（重启是异步的，手机端会通过轮询拿到最新状态）
        return httpJson(200, """{"success":true,"vpnState":"${mgr.vpnState.value.name}","proxyMode":"${dataStore.proxyMode}"}""")
    }

    private fun handleStatus(): ByteArray {
        val mgr = VpnServiceLocator.connectionManager
        val s = mgr.trafficStats.value
        val server = mgr.getCurrentServer()
        // 字段名 connectedServerName / connectedServerId 与手机端 TvStatusResponse 保持一致
        return httpJson(200, """{"vpnState":"${mgr.vpnState.value.name}","connectedServerName":"${server?.displayName ?: ""}","connectedServerId":${server?.serverId ?: 0},"connectedSeconds":${mgr.getConnectedSeconds()},"proxyMode":"${dataStore.proxyMode}","uploadSpeed":${s.uploadSpeed},"downloadSpeed":${s.downloadSpeed},"totalUpload":${s.totalUpload},"totalDownload":${s.totalDownload},"isPaired":${dataStore.isPaired},"serverCount":${dataStore.servers.size}}""")
    }

    private fun handleDebug(): ByteArray {
        val servers = dataStore.servers
        val sb = StringBuilder()
        sb.append("""{"serverCount":${servers.size},"isPaired":${dataStore.isPaired},"proxyMode":"${dataStore.proxyMode}","lastPath":"${lastPath}","lastBodyLen":${lastRawBody.length},"lastBodyPreview":""")
        sb.append('"').append(lastRawBody.take(200).replace("\"", "\\\"").replace("\n", "\\n")).append('"')
        sb.append(""","servers":[""")
        servers.take(5).forEachIndexed { i, sv ->
            if (i > 0) sb.append(",")
            sb.append("""{"id":${sv.serverId},"name":"${sv.name}","host":"${sv.address}","type":"${sv.protocol}"}""")
        }
        sb.append("]}")
        return httpJson(200, sb.toString())
    }

    private fun httpJson(code: Int, body: String): ByteArray {
        val status = when (code) {
            200 -> "200 OK"; 400 -> "400 Bad Request"; 404 -> "404 Not Found"
            else -> "500 Internal Server Error"
        }
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
        return header.toByteArray(Charsets.US_ASCII) + bodyBytes
    }
}