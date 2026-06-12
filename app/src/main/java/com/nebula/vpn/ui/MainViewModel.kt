package com.nebula.vpn.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nebula.vpn.proxy.GeoInfo
import com.nebula.vpn.proxy.GeoLocator
import com.nebula.vpn.proxy.ServerConfig
import com.nebula.vpn.proxy.SubscriptionRepository
import com.nebula.vpn.proxy.VpnManager
import com.nebula.vpn.proxy.VpnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Live "where am I" readout. [viaVpn] tells whether it reflects the tunnel exit. */
sealed interface LocationState {
    data object Unknown : LocationState
    data class Known(val info: GeoInfo, val viaVpn: Boolean) : LocationState
}

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

    private val _location = MutableStateFlow<LocationState>(LocationState.Unknown)
    val location: StateFlow<LocationState> = _location.asStateFlow()

    private val _locationLoading = MutableStateFlow(false)
    val locationLoading: StateFlow<Boolean> = _locationLoading.asStateFlow()

    private val _pingingAll = MutableStateFlow(false)
    val pingingAll: StateFlow<Boolean> = _pingingAll.asStateFlow()

    private val _autoSelecting = MutableStateFlow(false)
    val autoSelecting: StateFlow<Boolean> = _autoSelecting.asStateFlow()

    private val _favorites = MutableStateFlow(repo.favorites)
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    val vpnState: StateFlow<VpnState> = VpnManager.state
    val vpnMessage: StateFlow<String> = VpnManager.message
    val downlink: StateFlow<Long> = VpnManager.downlink
    val uplink: StateFlow<Long> = VpnManager.uplink
    val connectedSince: StateFlow<Long> = VpnManager.connectedSince

    var subscriptionUrl: String
        get() = repo.subscriptionUrl
        set(value) { repo.subscriptionUrl = value }

    init {
        val cached = repo.loadCached()
        _servers.value = cached
        // Restore the previously selected server (survives app restarts); fall back to first.
        _selected.value = cached.firstOrNull { it.id == repo.selectedId } ?: cached.firstOrNull()
        if (cached.isEmpty()) refresh()

        // Re-read the location whenever the tunnel goes up or down. Emitting the
        // current state immediately means the initial readout happens here too.
        viewModelScope.launch {
            VpnManager.state.collect { st ->
                when (st) {
                    VpnState.CONNECTED -> refreshLocation(settleDelayMs = 1_200)
                    VpnState.DISCONNECTED -> refreshLocation()
                    else -> {}
                }
            }
        }
    }

    fun refresh(url: String = repo.subscriptionUrl) {
        if (_loading.value) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            runCatching { repo.refresh(url) }
                .onSuccess { list ->
                    _servers.value = list
                    // Keep the current pick if it's still present, else the saved id, else first.
                    val keepId = _selected.value?.id ?: repo.selectedId
                    setSelected(list.firstOrNull { it.id == keepId } ?: list.firstOrNull())
                    if (list.isEmpty()) _error.value = "Subscription returned no usable servers."
                }
                .onFailure { _error.value = it.message ?: "Failed to load subscription." }
            _loading.value = false
        }
    }

    fun select(server: ServerConfig) = setSelected(server)

    fun toggleFavorite(server: ServerConfig) {
        val next = _favorites.value.toMutableSet().apply {
            if (!add(server.id)) remove(server.id)
        }
        _favorites.value = next
        repo.favorites = next
    }

    private fun setSelected(server: ServerConfig?) {
        _selected.value = server
        repo.selectedId = server?.id
    }

    fun ping(server: ServerConfig) {
        viewModelScope.launch {
            val ms = SubscriptionRepository.tcpPing(server)
            _pings.update { it + (server.id to ms) }
        }
    }

    /**
     * Ping a sample of servers and auto-select the one with the lowest latency.
     * Keeps the sample modest so the pick is fast; results also populate the list.
     */
    fun autoSelectOptimal(sampleSize: Int = 50, concurrency: Int = 32) {
        if (_autoSelecting.value) return
        val candidates = servers.value.take(sampleSize)
        if (candidates.isEmpty()) return
        _autoSelecting.value = true
        viewModelScope.launch {
            val gate = Semaphore(concurrency)
            candidates.map { server ->
                launch {
                    gate.withPermit {
                        val ms = SubscriptionRepository.tcpPing(server)
                        _pings.update { it + (server.id to ms) }
                    }
                }
            }.joinAll()
            // Pick the reachable candidate with the lowest latency.
            val best = candidates
                .mapNotNull { s -> _pings.value[s.id]?.takeIf { it >= 0 }?.let { s to it } }
                .minByOrNull { it.second }?.first
            if (best != null) {
                setSelected(best)
            } else {
                _error.value = "Не удалось найти доступный сервер — попробуй «Пинг всех»."
            }
            _autoSelecting.value = false
        }
    }

    /**
     * TCP-ping every server, with bounded concurrency so thousands of nodes don't
     * spawn thousands of simultaneous sockets.
     */
    fun pingAll(concurrency: Int = 32) {
        if (_pingingAll.value) return
        _pingingAll.value = true
        viewModelScope.launch {
            val gate = Semaphore(concurrency)
            val jobs = servers.value.map { server ->
                launch {
                    gate.withPermit {
                        val ms = SubscriptionRepository.tcpPing(server)
                        _pings.update { it + (server.id to ms) }
                    }
                }
            }
            jobs.joinAll()
            _pingingAll.value = false
        }
    }

    /**
     * Re-query the public country/IP. While the tunnel is up the lookup is routed
     * through the core's SOCKS inbound, so it reflects the VPN exit rather than the
     * real (excluded-from-route) device IP.
     */
    fun refreshLocation(settleDelayMs: Long = 0) {
        if (_locationLoading.value) return
        _locationLoading.value = true
        viewModelScope.launch {
            if (settleDelayMs > 0) delay(settleDelayMs)
            val connected = VpnManager.state.value == VpnState.CONNECTED
            val info = GeoLocator.locate(viaProxy = connected)
            if (info != null) _location.value = LocationState.Known(info, connected)
            _locationLoading.value = false
        }
    }
}
