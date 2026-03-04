package com.v2rayng.mytv.ui.nodes

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.v2rayng.mytv.R
import com.v2rayng.mytv.data.ServerConfig

class ServerAdapter(
    private val servers: List<ServerConfig>,
    private var selectedIndex: Int,
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<ServerAdapter.VH>() {

    private val countryFlags = mapOf(
        "HK" to "🇭🇰", "JP" to "🇯🇵", "US" to "🇺🇸", "SG" to "🇸🇬",
        "TW" to "🇹🇼", "KR" to "🇰🇷", "GB" to "🇬🇧", "DE" to "🇩🇪",
        "FR" to "🇫🇷", "CA" to "🇨🇦", "AU" to "🇦🇺", "IN" to "🇮🇳",
        "RU" to "🇷🇺", "TR" to "🇹🇷", "NL" to "🇳🇱", "PH" to "🇵🇭"
    )

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvFlag: TextView = view.findViewById(R.id.tvFlag)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvRate: TextView = view.findViewById(R.id.tvRate)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val tvSelected: TextView = view.findViewById(R.id.tvSelected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false)
    )

    override fun getItemCount() = servers.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val server = servers[position]
        holder.tvFlag.text = countryFlags[server.countryCode.uppercase()] ?: "🌐"
        holder.tvName.text = server.displayName
        holder.tvRate.text = "倍率 ${server.rate}x · ${server.protocol.uppercase()}"

        val online = server.isOnline == 1
        holder.tvStatus.text = if (online) "在线" else "离线"
        holder.tvStatus.setTextColor(if (online) Color.parseColor("#00E676") else Color.parseColor("#FF5252"))

        val isSelected = position == selectedIndex
        holder.tvSelected.visibility = if (isSelected) View.VISIBLE else View.GONE

        // 焦点态：由 bg_server_item selector drawable 自动处理颜色
        // 仅做缩放动画反馈
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(150)
                .start()
        }

        holder.itemView.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val old = selectedIndex
            selectedIndex = pos
            notifyItemChanged(old)
            notifyItemChanged(pos)
            onSelect(pos)
        }
    }
}