package com.nebula.vpn.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nebula.vpn.proxy.ServerConfig
import com.nebula.vpn.proxy.SubscriptionRepository
import com.nebula.vpn.proxy.VpnManager
import com.nebula.vpn.proxy.VpnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SubscriptionRepository(app)

    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _pings = MutableStateFlow<Map<String, Long>>(emptyMap())
    val pings: StateFlow<Map<String, Long>> = _pings.asStateFlow()

    private val _selected = MutableStateFlow<ServerConfig?>(null)
    val selected: StateFlow<ServerConfig?> = _selected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val vpnState: StateFlow<VpnState> = VpnManager.state
    val vpnMessage: StateFlow<String> = VpnManager.message

    var subscriptionUrl: String
        get() = repo.subscriptionUrl
        set(value) { repo.subscriptionUrl = value }

    init {
        val cached = repo.loadCached()
        _servers.value = cached
        _selected.value = cached.firstOrNull()
        if (cached.isEmpty()) refresh()
    }

    fun refresh(url: String = repo.subscriptionUrl) {
        if (_loading.value) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            runCatching { repo.refresh(url) }
                .onSuccess { list ->
                    _servers.value = list
                    if (_selected.value == null || list.none { it.id == _selected.value?.id }) {
                        _selected.value = list.firstOrNull()
                    }
                    if (list.isEmpty()) _error.value = "Subscription returned no usable servers."
                }
                .onFailure { _error.value = it.message ?: "Failed to load subscription." }
            _loading.value = false
        }
    }

    fun select(server: ServerConfig) { _selected.value = server }

    fun ping(server: ServerConfig) {
        viewModelScope.launch {
            val ms = SubscriptionRepository.tcpPing(server)
            _pings.value = _pings.value.toMutableMap().apply { put(server.id, ms) }
        }
    }

    /** Ping the first [count] servers (used right after a refresh to surface fast nodes). */
    fun pingTop(count: Int = 12) {
        servers.value.take(count).forEach { ping(it) }
    }
}
