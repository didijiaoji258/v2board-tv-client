package com.v2rayng.mytv.vpn

import android.util.Log
import com.v2rayng.mytv.data.ServerConfig
import org.json.JSONArray
import org.json.JSONObject

object V2RayConfig {

    private const val TAG = "V2RayConfig"

    const val MODE_GLOBAL = "global"
    const val MODE_RULE = "rule"

    fun generateConfig(server: ServerConfig, proxyMode: String = MODE_GLOBAL, userUuid: String = ""): String {
        Log.i(TAG, "generateConfig: protocol=${server.protocol} addr=${server.address}:${server.port} mode=$proxyMode")
        val config = when (server.protocol.lowercase()) {
            "vmess"       -> generateVMessConfig(server, proxyMode, userUuid)
            "vless"       -> generateVLessConfig(server, proxyMode, userUuid)
            "trojan"      -> generateTrojanConfig(server, proxyMode, userUuid)
            "shadowsocks" -> generateShadowsocksConfig(server, proxyMode, userUuid)
            "hysteria2"   -> generateHysteriaConfig(server, proxyMode, userUuid)
            else -> {
                Log.w(TAG, "Unknown protocol '${server.protocol}', fallback vmess")
                generateVMessConfig(server, proxyMode, userUuid)
            }
        }
        Log.d(TAG, "Config (${config.length} bytes): ${config.take(500)}")
        return config
    }

    private fun generateVMessConfig(server: ServerConfig, proxyMode: String, userUuid: String): String {
        val config = baseConfig(proxyMode)
        val uuid = userUuid.ifEmpty { server.uuid }
        val outbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vmess")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().put(JSONObject().apply {
                    put("address", server.address)
                    put("port", server.port)
                    put("users", JSONArray().put(JSONObject().apply {
                        put("id", uuid)
                        put("alterId", server.alterId)
                        put("security", server.security.ifEmpty { "auto" })
                    }))
                }))
            })
            put("streamSettings", buildStreamSettings(server))
            put("mux", JSONObject().put("enabled", false).put("concurrency", -1))
        }
        config.put("outbounds", buildOutbounds(outbound))
        return config.toString(2)
    }

    private fun generateVLessConfig(server: ServerConfig, proxyMode: String, userUuid: String): String {
        val config = baseConfig(proxyMode)
        val uuid = userUuid.ifEmpty { server.uuid }
        val outbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().put(JSONObject().apply {
                    put("address", server.address)
                    put("port", server.port)
                    put("users", JSONArray().put(JSONObject().apply {
                        put("id", uuid)
                        put("encryption", "none")
                        put("flow", server.flow)
                    }))
                }))
            })
            put("streamSettings", buildStreamSettings(server))
            put("mux", JSONObject().put("enabled", false).put("concurrency", -1))
        }
        config.put("outbounds", buildOutbounds(outbound))
        return config.toString(2)
    }

    private fun generateTrojanConfig(server: ServerConfig, proxyMode: String, userUuid: String): String {
        val config = baseConfig(proxyMode)
        val sni = server.sni.ifEmpty { server.address }
        val outbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "trojan")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().put(JSONObject().apply {
                    put("address", server.address)
                    put("port", server.port)
                    put("password", userUuid.ifEmpty { server.uuid }.ifEmpty { server.password })
                }))
            })
            put("streamSettings", JSONObject().apply {
                put("network", "tcp")
                put("security", "tls")
                put("tlsSettings", JSONObject().apply {
                    put("allowInsecure", server.allowInsecure == 1)
                    put("serverName", sni)
                })
            })
            put("mux", JSONObject().put("enabled", false).put("concurrency", -1))
        }
        config.put("outbounds", buildOutbounds(outbound))
        return config.toString(2)
    }

    private fun generateShadowsocksConfig(server: ServerConfig, proxyMode: String, userUuid: String): String {
        val config = baseConfig(proxyMode)
        val cipher = server.cipher.ifEmpty { "aes-256-gcm" }
        val outbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "shadowsocks")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().put(JSONObject().apply {
                    put("address", server.address)
                    put("port", server.port)
                    put("method", cipher)
                    put("password", server.password.ifEmpty { userUuid })
                }))
            })
            put("streamSettings", JSONObject().apply {
                put("network", "tcp")
                put("security", "none")
            })
            put("mux", JSONObject().put("enabled", false).put("concurrency", -1))
        }
        config.put("outbounds", buildOutbounds(outbound))
        return config.toString(2)
    }

    private fun generateHysteriaConfig(server: ServerConfig, proxyMode: String, userUuid: String): String {
        val config = baseConfig(proxyMode)
        val sni = server.sni.ifEmpty { server.address }
        val outbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "hysteria2")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().put(JSONObject().apply {
                    put("address", server.address)
                    put("port", server.port)
                    put("password", userUuid.ifEmpty { server.uuid })
                }))
            })
            put("streamSettings", JSONObject().apply {
                put("network", "hysteria2")
                put("security", "tls")
                put("tlsSettings", JSONObject().apply {
                    put("serverName", sni)
                    put("allowInsecure", server.insecure == 1 || server.allowInsecure == 1)
                })
            })
        }
        config.put("outbounds", buildOutbounds(outbound))
        return config.toString(2)
    }

    private fun buildStreamSettings(server: ServerConfig): JSONObject {
        val stream = JSONObject()
        val network = server.network.ifEmpty { "tcp" }
        stream.put("network", network)

        when (server.tls.lowercase()) {
            "tls" -> {
                stream.put("security", "tls")
                stream.put("tlsSettings", JSONObject().apply {
                    put("allowInsecure", server.allowInsecure == 1)
                    put("serverName", server.sni.ifEmpty { server.address })
                    if (server.fingerprint.isNotEmpty()) put("fingerprint", server.fingerprint)
                    if (server.alpn.isNotEmpty()) {
                        put("alpn", JSONArray().apply { server.alpn.split(",").forEach { put(it.trim()) } })
                    }
                })
            }
            "reality" -> {
                stream.put("security", "reality")
                stream.put("realitySettings", JSONObject().apply {
                    put("serverName", server.sni.ifEmpty { server.address })
                    put("fingerprint", server.fingerprint.ifEmpty { "chrome" })
                    put("publicKey", server.publicKey)
                    put("shortId", server.shortId)
                    put("spiderX", server.spiderX)
                })
            }
            else -> stream.put("security", "none")
        }

        when (network) {
            "ws" -> stream.put("wsSettings", JSONObject().apply {
                put("path", server.path.ifEmpty { "/" })
                put("headers", JSONObject().put("Host", server.sni.ifEmpty { server.address }))
            })
            "grpc" -> stream.put("grpcSettings", JSONObject().apply {
                put("serviceName", server.path)
                put("multiMode", false)
            })
            "h2", "http" -> stream.put("httpSettings", JSONObject().apply {
                put("path", server.path.ifEmpty { "/" })
                put("host", JSONArray().put(server.sni.ifEmpty { server.address }))
            })
            "tcp" -> stream.put("tcpSettings", JSONObject().apply {
                put("header", JSONObject().put("type", server.headerType.ifEmpty { "none" }))
            })
        }
        return stream
    }

    private fun baseConfig(proxyMode: String): JSONObject {
        return JSONObject().apply {
            put("stats", JSONObject())
            put("log", JSONObject().apply {
                put("loglevel", "warning")  // warning 级别，便于调试
            })
            put("policy", JSONObject().apply {
                put("levels", JSONObject().apply {
                    put("0", JSONObject().apply {
                        put("statsUserUplink", true)
                        put("statsUserDownlink", true)
                    })
                })
                put("system", JSONObject().apply {
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                })
            })
            // SOCKS5 inbound：Tun2Socks 将 TUN 流量转发到此端口，xray-core 代理出去
            put("inbounds", JSONArray().put(JSONObject().apply {
                put("tag", "socks-in")
                put("port", 10808)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", false)
                })
            }))
            // 简单 DNS，避免 geosite 查询引起启动失败
            put("dns", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put("8.8.8.8")
                    put("1.1.1.1")
                })
            })
            put("routing", buildRouting(proxyMode))
        }
    }

    private fun buildRouting(proxyMode: String): JSONObject {
        return JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray().apply {
                // 局域网直连
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("10.0.0.0/8")
                        put("172.16.0.0/12")
                        put("192.168.0.0/16")
                        put("127.0.0.0/8")
                        put("100.64.0.0/10")
                        put("169.254.0.0/16")
                        put("::1/128")
                        put("fc00::/7")
                    })
                })
                if (proxyMode == MODE_RULE) {
                    // rule 模式：中国 IP 和域名直连（使用 geoip/geosite dat 文件）
                    put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "direct")
                        put("ip", JSONArray().put("geoip:cn").put("geoip:private"))
                    })
                    put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "direct")
                        put("domain", JSONArray().put("geosite:cn").put("geosite:private"))
                    })
                }
                // 其余走代理
                put(JSONObject().apply {
                    put("type", "field")
                    put("port", "0-65535")
                    put("outboundTag", "proxy")
                })
            })
        }
    }

    private fun buildOutbounds(proxyOutbound: JSONObject): JSONArray {
        return JSONArray().apply {
            put(proxyOutbound)
            put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
                put("settings", JSONObject())
            })
            put(JSONObject().apply {
                put("tag", "block")
                put("protocol", "blackhole")
                put("settings", JSONObject().apply {
                    put("response", JSONObject().put("type", "http"))
                })
            })
        }
    }
}