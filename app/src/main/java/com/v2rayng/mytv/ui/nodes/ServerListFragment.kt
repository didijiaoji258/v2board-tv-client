package com.v2rayng.mytv.ui.nodes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.v2rayng.mytv.R
import com.v2rayng.mytv.TvApplication
import com.v2rayng.mytv.vpn.VpnServiceLocator
import com.v2rayng.mytv.vpn.VpnState

class ServerListFragment : Fragment() {

    interface Callback {
        fun onServerSelected()
        fun onBackFromServerList()
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_server_list, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as TvApplication
        val ds = app.dataStore
        val servers = ds.servers

        // 节点数量
        view.findViewById<TextView>(R.id.tvServerCount).text = "共 ${servers.size} 个节点"

        // 返回按钮
        view.findViewById<Button>(R.id.btnBack).apply {
            requestFocus()
            setOnClickListener { (activity as? Callback)?.onBackFromServerList() }
        }

        // 空列表提示
        if (servers.isEmpty()) {
            Toast.makeText(requireContext(), "节点列表为空，请先从手机同步数据", Toast.LENGTH_LONG).show()
        }

        // 节点列表
        val rv = view.findViewById<RecyclerView>(R.id.rvServers)
        val layoutManager = LinearLayoutManager(requireContext())
        rv.layoutManager = layoutManager
        rv.setHasFixedSize(true)

        val adapter = ServerAdapter(servers, ds.selectedServerIndex) { index ->
            ds.selectedServerIndex = index
            // 如果 VPN 已连接，自动重连新节点
            val mgr = VpnServiceLocator.connectionManager
            if (mgr.vpnState.value == VpnState.CONNECTED) {
                mgr.stopVpn()
                val server = ds.selectedServer
                if (server != null) {
                    rv.postDelayed({ mgr.startVpn(server, ds.proxyMode, ds.userUuid) }, 700)
                }
            }
            Toast.makeText(requireContext(), "已选择：${servers[index].displayName}", Toast.LENGTH_SHORT).show()
            (activity as? Callback)?.onServerSelected()
        }
        rv.adapter = adapter

        // 自动滚动到已选中节点位置
        val selectedIdx = ds.selectedServerIndex
        if (selectedIdx in servers.indices) {
            rv.post { layoutManager.scrollToPositionWithOffset(selectedIdx, 0) }
        }
    }
}