package com.v2rayng.mytv.ui.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2rayng.mytv.R
import com.v2rayng.mytv.TvApplication
import com.v2rayng.mytv.vpn.V2RayConfig
import com.v2rayng.mytv.vpn.VpnConnectionManager
import com.v2rayng.mytv.vpn.VpnServiceLocator
import com.v2rayng.mytv.vpn.VpnState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainFragment : Fragment() {

    private val connMgr get() = VpnServiceLocator.connectionManager
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    interface NavigationCallback {
        fun onNavigateToServerList()
        fun onNavigateToPairing()
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) connectVpn()
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_main, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as TvApplication
        val ds = app.dataStore

        // 品牌名
        val brandName = ds.brandAppName
        if (brandName.isNotEmpty()) view.findViewById<TextView>(R.id.tvAppName).text = brandName

        // 连接按钮（自定义动画View）
        val btnConnect = view.findViewById<ConnectButtonView>(R.id.btnConnect)
        btnConnect.requestFocus()
        btnConnect.onConnectClick = { toggleVpn() }

        // 节点面板 → 跳转节点列表
        val panelNode = view.findViewById<View>(R.id.panelNode)
        panelNode.setOnClickListener {
            (activity as? NavigationCallback)?.onNavigateToServerList()
        }

        // 模式面板 → 切换模式（直接切换，无弹窗）
        val panelMode = view.findViewById<View>(R.id.panelMode)
        panelMode.setOnClickListener {
            toggleProxyMode()
        }

        // 同步按钮
        view.findViewById<Button>(R.id.btnSync).setOnClickListener {
            (activity as? NavigationCallback)?.onNavigateToPairing()
        }

        // 订阅信息
        val sub = ds.subscription
        if (sub != null) {
            view.findViewById<TextView>(R.id.tvPlan).text = "套餐：${sub.planName}"
            val expDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(sub.expiredAt * 1000))
            view.findViewById<TextView>(R.id.tvExpiry).text = "到期：$expDate"
            val used = VpnConnectionManager.formatBytes(sub.uploadBytes + sub.downloadBytes)
            val total = VpnConnectionManager.formatBytes(sub.transferEnable)
            view.findViewById<TextView>(R.id.tvTraffic).text = "流量：$used / $total"
        }

        // 同步时间
        val syncedAt = ds.syncedAt
        if (syncedAt > 0) {
            val syncDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(syncedAt))
            view.findViewById<TextView>(R.id.tvSyncInfo).text = "上次同步：$syncDate"
        }

        // 当前节点与模式
        updateNodeDisplay(view)
        updateModeDisplay(view)

        // 观察 VPN 状态 — 同时驱动动画按钮和文字状态
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { connMgr.vpnState.collect { state ->
                    btnConnect.vpnState = state   // 驱动动画
                    updateVpnUI(view, state)
                }}
                launch { connMgr.trafficStats.collect { stats ->
                    view.findViewById<TextView>(R.id.tvUpSpeed).text = VpnConnectionManager.formatSpeed(stats.uploadSpeed)
                    view.findViewById<TextView>(R.id.tvDownSpeed).text = VpnConnectionManager.formatSpeed(stats.downloadSpeed)
                }}
                // 手机端切换模式时实时更新 TV UI（无需等待 onResume）
                launch { VpnServiceLocator.proxyMode.collect { mode ->
                    val modeName = when (mode) {
                        "global" -> "全局模式"
                        else -> "规则模式"
                    }
                    view.findViewById<TextView>(R.id.tvModeName)?.text = modeName
                }}
                // 手机端切换节点时实时更新 TV UI
                launch { VpnServiceLocator.selectedServerIndex.collect { idx ->
                    if (idx >= 0) {
                        val ds2 = (requireActivity().application as com.v2rayng.mytv.TvApplication).dataStore
                        val server = ds2.servers.getOrNull(idx)
                        view.findViewById<TextView>(R.id.tvNodeName)?.text = server?.name ?: "未选择节点"
                    }
                }}
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从节点列表返回或同步后刷新显示
        view?.let {
            updateBrandDisplay(it)
            updateNodeDisplay(it)
            updateModeDisplay(it)
        }
    }

    /** 供外部（手机端推送 switch_mode / switch_node）调用刷新 UI */
    fun refresh() {
        view?.let {
            updateNodeDisplay(it)
            updateModeDisplay(it)
        }
    }

    private fun updateBrandDisplay(view: View) {
        val ds = (requireActivity().application as TvApplication).dataStore
        val name = ds.brandAppName
        if (name.isNotEmpty()) view.findViewById<TextView>(R.id.tvAppName).text = name
    }

    private fun updateNodeDisplay(view: View) {
        val ds = (requireActivity().application as TvApplication).dataStore
        val server = ds.selectedServer
        view.findViewById<TextView>(R.id.tvNodeName).text = server?.name ?: "未选择节点"
    }

    private fun updateModeDisplay(view: View) {
        val ds = (requireActivity().application as TvApplication).dataStore
        val mode = ds.proxyMode
        val modeName = when (mode) {
            V2RayConfig.MODE_RULE -> "规则模式"
            V2RayConfig.MODE_GLOBAL -> "全局模式"
            else -> "规则模式"
        }
        view.findViewById<TextView>(R.id.tvModeName)?.text = modeName
    }

    /**
     * 点击切换代理模式（rule ↔ global），无弹窗
     */
    private fun toggleProxyMode() {
        val ds = (requireActivity().application as TvApplication).dataStore
        val currentMode = ds.proxyMode
        val newMode = if (currentMode == V2RayConfig.MODE_RULE) {
            V2RayConfig.MODE_GLOBAL
        } else {
            V2RayConfig.MODE_RULE
        }
        ds.proxyMode = newMode
        updateModeDisplay(requireView())

        val modeName = if (newMode == V2RayConfig.MODE_RULE) "规则模式" else "全局模式"
        Toast.makeText(requireContext(), "已切换为$modeName", Toast.LENGTH_SHORT).show()

        // 如果 VPN 已连接，自动重连以应用新模式
        if (connMgr.vpnState.value == VpnState.CONNECTED) {
            connMgr.stopVpn()
            view?.postDelayed({
                val ds2 = (requireActivity().application as TvApplication).dataStore
                ds2.selectedServer?.let { connMgr.startVpn(it, ds2.proxyMode, ds2.userUuid) }
            }, 800)
        }
    }

    private fun toggleVpn() {
        when (connMgr.vpnState.value) {
            VpnState.DISCONNECTED, VpnState.ERROR -> {
                val ds = (requireActivity().application as TvApplication).dataStore
                if (ds.selectedServer == null) {
                    Toast.makeText(requireContext(), "请先选择一个节点", Toast.LENGTH_SHORT).show()
                    return
                }
                if (connMgr.needVpnPermission()) {
                    connMgr.getVpnPermissionIntent()?.let { vpnPermissionLauncher.launch(it) }
                } else {
                    connectVpn()
                }
            }
            VpnState.CONNECTED -> connMgr.stopVpn()
            else -> {} // CONNECTING/DISCONNECTING 中不操作
        }
    }

    private fun connectVpn() {
        val ds = (requireActivity().application as TvApplication).dataStore
        val server = ds.selectedServer
        if (server == null) {
            Toast.makeText(requireContext(), "请先选择一个节点", Toast.LENGTH_SHORT).show()
            return
        }
        connMgr.startVpn(server, ds.proxyMode, ds.userUuid)
    }

    private fun updateVpnUI(view: View, state: VpnState) {
        val tvStatus = view.findViewById<TextView>(R.id.tvStatus)
        val tvTimer  = view.findViewById<TextView>(R.id.tvTimer)

        when (state) {
            VpnState.DISCONNECTED  -> { tvStatus.text = "未连接";   tvStatus.setTextColor(0xFF5A5D7A.toInt()); stopTimer(tvTimer) }
            VpnState.CONNECTING    -> { tvStatus.text = "正在连接..."; tvStatus.setTextColor(0xFF7B61FF.toInt()) }
            VpnState.CONNECTED     -> { tvStatus.text = "● 已连接";  tvStatus.setTextColor(0xFF00E676.toInt()); startTimer(tvTimer) }
            VpnState.DISCONNECTING -> { tvStatus.text = "正在断开..."; tvStatus.setTextColor(0xFF7B61FF.toInt()); stopTimer(tvTimer) }
            VpnState.ERROR         -> { tvStatus.text = "连接失败，点击重试"; tvStatus.setTextColor(0xFFFF5252.toInt()); stopTimer(tvTimer) }
        }
    }

    private fun startTimer(tv: TextView) {
        stopTimer(tv)
        timerRunnable = object : Runnable {
            override fun run() {
                val secs = connMgr.getConnectedSeconds()
                val h = secs / 3600; val m = (secs % 3600) / 60; val s = secs % 60
                tv.text = "%02d:%02d:%02d".format(h, m, s)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timerRunnable!!)
    }

    private fun stopTimer(tv: TextView) {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
        tv.text = "00:00:00"
    }

    override fun onDestroyView() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroyView()
    }
}