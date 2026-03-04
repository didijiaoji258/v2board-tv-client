package com.v2rayng.mytv.data

import com.google.gson.annotations.SerializedName

/**
 * 从手机端推送过来的单个节点配置
 * 严格对齐 TvConnectionService.serverToJson() 发出的 JSON 字段名
 */
data class ServerConfig(
    // 手机端用 "id" 字段携带数字 ID (Int)
    @SerializedName("id") val serverId: Int = 0,

    val name: String = "",              // 节点名称

    // 手机端用 "host" 表示服务器地址
    @SerializedName("host") val address: String = "",

    val port: Int = 0,

    @SerializedName("server_port") val serverPort: Int = 0,

    // 手机端用 "type" 表示协议名称
    @SerializedName("type") val protocol: String = "",  // vmess/vless/trojan/shadowsocks/hysteria2

    val network: String = "",           // tcp/ws/grpc/h2

    // tls 在手机端是 Int (0/1)，GsonUtil 宽松适配器会转为 String
    val tls: String = "",

    val rate: String = "1.0",           // 倍率

    @SerializedName("country_code") val countryCode: String = "",

    @SerializedName("is_online") val isOnline: Int = 1,

    // Shadowsocks 加密方式（手机端字段名 "cipher"）
    val cipher: String = "",

    val obfs: String = "",

    // ShadowsocksR 协议（手机端字段名 "protocol"，与类型字段不同）
    // 用 remarks 字段兜底，实际SS-R用得少
    val password: String = "",

    @SerializedName("allow_insecure") val allowInsecure: Int = 0,

    // SNI / TLS server name
    @SerializedName("server_name") val sni: String = "",

    @SerializedName("up_mbps") val upMbps: Int = 0,
    @SerializedName("down_mbps") val downMbps: Int = 0,

    val insecure: Int = 0,

    // networkSettings 可能是 JSON 字符串或对象，GsonUtil 宽松适配器处理
    val networkSettings: String = "",
    val tlsSettings: String = "",
    val obfsSettings: String = "",
    val protocolSettings: String = "",

    // VMess/VLess UUID 走 id 字段在 networkSettings 里，单独存时用以下字段
    val uuid: String = "",

    // Hysteria2 obfs password
    @SerializedName("obfs_password") val obfsPassword: String = "",

    val flow: String = "",              // VLESS flow
    val fingerprint: String = "",       // uTLS fingerprint
    val alpn: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val alterId: Int = 0,
    val security: String = "auto",
    val headerType: String = "none",
    val path: String = ""
) {
    /** 优先显示名，依次取 name / address */
    val displayName: String get() = name.ifEmpty { address }

    val remarks: String get() = displayName
}