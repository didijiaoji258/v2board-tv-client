package com.v2rayng.mytv.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.reflect.TypeToken
import com.v2rayng.mytv.util.GsonUtil

class TvDataStore(context: Context) {

    companion object {
        private const val PREF_NAME = "tv_vpn_data"
        private const val KEY_USER_UUID = "user_uuid"
        private const val KEY_SERVERS = "servers_json"
        private const val KEY_SELECTED_INDEX = "selected_server_index"
        private const val KEY_PROXY_MODE = "proxy_mode"
        private const val KEY_BRAND_APP_NAME = "brand_app_name"
        private const val KEY_BRAND_SUBTITLE = "brand_subtitle"
        private const val KEY_SUBSCRIPTION = "subscription_json"
        private const val KEY_SYNCED_AT = "synced_at"
        private const val KEY_TOKEN = "token"
        private const val KEY_PAIRED = "is_paired"
        private const val EXPIRE_DAYS = 7
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = GsonUtil.gson

    var isPaired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)
        set(v) = prefs.edit().putBoolean(KEY_PAIRED, v).apply()

    var userUuid: String
        get() = prefs.getString(KEY_USER_UUID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_USER_UUID, v).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TOKEN, v).apply()

    var proxyMode: String
        get() = prefs.getString(KEY_PROXY_MODE, "rule") ?: "rule"
        set(v) = prefs.edit().putString(KEY_PROXY_MODE, v).apply()

    var brandAppName: String
        get() = prefs.getString(KEY_BRAND_APP_NAME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BRAND_APP_NAME, v).apply()

    var brandSubtitle: String
        get() = prefs.getString(KEY_BRAND_SUBTITLE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BRAND_SUBTITLE, v).apply()

    var syncedAt: Long
        get() = prefs.getLong(KEY_SYNCED_AT, 0L)
        set(v) = prefs.edit().putLong(KEY_SYNCED_AT, v).apply()

    val isExpired: Boolean
        get() {
            val sa = syncedAt
            if (sa == 0L) return true
            return System.currentTimeMillis() - sa > EXPIRE_DAYS * 24 * 3600 * 1000L
        }

    // ---- 节点列表 ----

    var servers: List<ServerConfig>
        get() {
            val json = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
            return try {
                gson.fromJson(json, object : TypeToken<List<ServerConfig>>() {}.type)
            } catch (_: Exception) { emptyList() }
        }
        set(v) = prefs.edit().putString(KEY_SERVERS, gson.toJson(v)).apply()

    var selectedServerIndex: Int
        get() = prefs.getInt(KEY_SELECTED_INDEX, 0)
        set(v) = prefs.edit().putInt(KEY_SELECTED_INDEX, v).apply()

    val selectedServer: ServerConfig?
        get() {
            val list = servers
            val idx = selectedServerIndex
            return if (idx in list.indices) list[idx] else list.firstOrNull()
        }

    // ---- 订阅信息 ----

    var subscription: SubscriptionInfo?
        get() {
            val json = prefs.getString(KEY_SUBSCRIPTION, null) ?: return null
            return try { gson.fromJson(json, SubscriptionInfo::class.java) } catch (_: Exception) { null }
        }
        set(v) = prefs.edit().putString(KEY_SUBSCRIPTION, if (v != null) gson.toJson(v) else null).apply()

    // ---- 批量写入 ----

    fun saveSyncData(data: SyncData) {
        prefs.edit().apply {
            putString(KEY_USER_UUID, data.userUuid)
            putString(KEY_SERVERS, gson.toJson(data.servers))
            // 不覆盖 proxyMode：mode 只能由 switch_mode 命令显式修改，
            // 防止每次 sync 把手机端默认值（通常是 rule）重置 TV 已选的模式
            putString(KEY_BRAND_APP_NAME, data.brand.appName)
            putString(KEY_BRAND_SUBTITLE, data.brand.subtitle)
            putString(KEY_SUBSCRIPTION, gson.toJson(data.subscription))
            putLong(KEY_SYNCED_AT, System.currentTimeMillis())
            putBoolean(KEY_PAIRED, true)
            // 找到 selectedServerId 对应的 index（serverId 为数字 ID）
            val idx = data.servers.indexOfFirst { it.serverId == data.selectedServerId }
            putInt(KEY_SELECTED_INDEX, if (idx >= 0) idx else 0)
            apply()
        }
    }

    fun clear() = prefs.edit().clear().apply()
}