package com.v2rayng.mytv.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnServiceLocator {
    lateinit var connectionManager: VpnConnectionManager

    private val _proxyMode = MutableStateFlow("rule")
    val proxyMode: StateFlow<String> = _proxyMode.asStateFlow()
    fun setProxyMode(mode: String) { _proxyMode.value = mode }

    private val _selectedServerIndex = MutableStateFlow(-1)
    val selectedServerIndex: StateFlow<Int> = _selectedServerIndex.asStateFlow()
    fun setSelectedServerIndex(index: Int) { _selectedServerIndex.value = index }
}