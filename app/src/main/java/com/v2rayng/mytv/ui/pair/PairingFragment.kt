package com.v2rayng.mytv.ui.pair

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.v2rayng.mytv.R
import com.v2rayng.mytv.TvApplication
import com.v2rayng.mytv.util.NetworkUtil
import com.v2rayng.mytv.util.QrCodeUtil

class PairingFragment : Fragment() {

    private var isExpired = false

    companion object {
        const val PORT = 8866
        fun newInstance(expired: Boolean = false) = PairingFragment().apply {
            arguments = Bundle().apply { putBoolean("expired", expired) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isExpired = arguments?.getBoolean("expired", false) ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_pairing, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as TvApplication

        // 获取所有可用 IP（以太网优先）
        val allIps = NetworkUtil.getAllLocalIpAddresses()
        val primaryIp = allIps.firstOrNull() ?: "0.0.0.0"
        val primaryUrl = "http://$primaryIp:$PORT"

        // 生成二维码（使用主 IP）
        view.findViewById<ImageView>(R.id.ivQrCode)
            .setImageBitmap(QrCodeUtil.generate(primaryUrl, 512))

        // 显示主 IP
        view.findViewById<TextView>(R.id.tvIpAddress).text =
            if (allIps.size > 1) {
                // 显示所有 IP，方便排查
                "主要：$primaryUrl\n备用：${allIps.drop(1).joinToString(", ") { "http://$it:$PORT" }}"
            } else {
                primaryUrl
            }

        val brandName = app.dataStore.brandAppName
        if (brandName.isNotEmpty()) {
            view.findViewById<TextView>(R.id.tvAppName).text = brandName
        }

        if (isExpired) {
            view.findViewById<TextView>(R.id.tvExpiredHint).apply {
                visibility = View.VISIBLE
                text = "数据已过期，请重新扫码同步"
            }
        }
    }
}