package com.nebula.vpn.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** Process-wide source of truth shared between the VpnService and the Compose UI. */
object VpnManager {

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _activeServer = MutableStateFlow<ServerConfig?>(null)
    val activeServer: StateFlow<ServerConfig?> = _activeServer.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    // Live throughput in bytes/second, pushed by the running core.
    private val _downlink = MutableStateFlow(0L)
    val downlink: StateFlow<Long> = _downlink.asStateFlow()

    private val _uplink = MutableStateFlow(0L)
    val uplink: StateFlow<Long> = _uplink.asStateFlow()

    fun setState(s: VpnState) { _state.value = s }
    fun setActiveServer(s: ServerConfig?) { _activeServer.value = s }
    fun setMessage(m: String) { _message.value = m }

    fun setSpeed(downBytesPerSec: Long, upBytesPerSec: Long) {
        _downlink.value = downBytesPerSec
        _uplink.value = upBytesPerSec
    }
}
