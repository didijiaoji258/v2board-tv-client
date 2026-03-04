package com.v2rayng.mytv.data

/**
 * 手机推送到 TV 的完整数据包
 * 字段类型严格对齐文档 §5.2 协议
 *
 * POST /api/pair 和 POST /api/sync 共用同一格式
 */
data class SyncData(
    val userUuid: String = "",
    val servers: List<ServerConfig> = emptyList(),
    val selectedServerId: Int = 0,        // 手机端 JSON 中为数字类型
    val proxyMode: String = "rule",
    val brand: BrandInfo = BrandInfo(),
    val subscription: SubscriptionInfo = SubscriptionInfo()
)

data class BrandInfo(
    val appName: String = "",
    val subtitle: String = ""
)

data class SubscriptionInfo(
    val planName: String = "",
    val expiredAt: Long = 0,        // Unix timestamp (seconds)
    val transferEnable: Long = 0,   // 总流量 (bytes)
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val resetDay: Int = 1
)