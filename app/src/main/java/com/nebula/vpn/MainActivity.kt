package com.nebula.vpn

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.vpn.proxy.CountryFlags
import com.nebula.vpn.proxy.Protocol
import com.nebula.vpn.proxy.ServerConfig
import com.nebula.vpn.proxy.V2RayVpnService
import com.nebula.vpn.proxy.VpnState
import com.nebula.vpn.ui.LocationState
import com.nebula.vpn.ui.MainViewModel
import kotlinx.coroutines.delay
import java.util.Locale

// ── palette ────────────────────────────────────────────────────────────────────
private val Bg = Color(0xFF0A0E1A)
private val Surface1 = Color(0xFF141A2B)
private val Accent = Color(0xFF6C8CFF)
private val Green = Color(0xFF36D399)
private val Amber = Color(0xFFF6C744)
private val Red = Color(0xFFFF6B6B)
private val Muted = Color(0xFF8A93AD)
private val OnBg = Color(0xFFEAEDF7)

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Use the real native Xray core so traffic is actually tunnelled.
        com.nebula.vpn.core.CoreController.factory = { com.nebula.vpn.core.XrayCore() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent {
            NebulaTheme {
                Surface(Modifier.fillMaxSize(), color = Bg) { HomeScreen(vm) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(vm: MainViewModel) {
    val servers by vm.servers.collectAsState()
    val loading by vm.loading.collectAsState()
    val pings by vm.pings.collectAsState()
    val selected by vm.selected.collectAsState()
    val error by vm.error.collectAsState()
    val state by vm.vpnState.collectAsState()
    val message by vm.vpnMessage.collectAsState()
    val location by vm.location.collectAsState()
    val locationLoading by vm.locationLoading.collectAsState()
    val downlink by vm.downlink.collectAsState()
    val uplink by vm.uplink.collectAsState()
    val pingingAll by vm.pingingAll.collectAsState()
    val autoSelecting by vm.autoSelecting.collectAsState()
    val connectedSince by vm.connectedSince.collectAsState()
    val favorites by vm.favorites.collectAsState()

    var query by remember { mutableStateOf("") }
    var sortByPing by remember { mutableStateOf(false) }

    val displayed = remember(servers, query, pings, sortByPing, favorites) {
        val base = if (query.isBlank()) servers
        else servers.filter { it.remark.contains(query, true) || it.address.contains(query, true) }
        val sorted = if (!sortByPing) base
        else base.sortedBy { s -> pings[s.id]?.takeIf { it >= 0 } ?: Long.MAX_VALUE }
        // Starred servers pinned to the top (stable sort preserves the order above).
        sorted.sortedByDescending { it.id in favorites }
    }

    // Ticking session timer (only while connected).
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(state, connectedSince) {
        if (state == VpnState.CONNECTED && connectedSince > 0) {
            while (true) {
                elapsed = System.currentTimeMillis() - connectedSince
                delay(1_000)
            }
        } else elapsed = 0
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selected?.let { V2RayVpnService.start(context, it) }
        }
    }

    fun toggle() {
        if (state == VpnState.CONNECTED || state == VpnState.CONNECTING) {
            V2RayVpnService.stop(context)
        } else {
            val server = selected ?: return
            val prepare = VpnService.prepare(context)
            if (prepare != null) launcher.launch(prepare) else V2RayVpnService.start(context, server)
        }
    }

    Scaffold(containerColor = Bg) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // ── header ──
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Root VPN", color = OnBg, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text("${servers.size} серверов", color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = { vm.refresh() }, enabled = !loading) {
                    Icon(Icons.Filled.Refresh, "Обновить", tint = Accent)
                }
            }

            ConnectHero(state, selected, elapsed, ::toggle)

            StatusCard(state, location, locationLoading, downlink, uplink, message) { vm.refreshLocation() }

            if (loading) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp),
                    color = Accent, trackColor = Surface1
                )
            }
            error?.let {
                Text(it, color = Red, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }

            // ── server controls ──
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Серверы", color = OnBg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionChip("Авто", Icons.Filled.AutoAwesome, autoSelecting,
                        enabled = !autoSelecting && servers.isNotEmpty()) { vm.autoSelectOptimal() }
                    ActionChip(if (pingingAll) "Пинг…" else "Пинг", Icons.Filled.Bolt, pingingAll,
                        enabled = !pingingAll) { vm.pingAll() }
                    IconToggle(Icons.Filled.SwapVert, active = sortByPing) { sortByPing = !sortByPing }
                }
            }

            SearchField(query) { query = it }

            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(displayed, key = { it.id }) { server ->
                    ServerRow(
                        server = server,
                        selected = server.id == selected?.id,
                        favorite = server.id in favorites,
                        ping = pings[server.id],
                        onClick = { vm.select(server) },
                        onFavorite = { vm.toggleFavorite(server) }
                    )
                }
            }
        }
    }
}

// ── connect hero ───────────────────────────────────────────────────────────────
@Composable
private fun ConnectHero(state: VpnState, server: ServerConfig?, elapsedMs: Long, onToggle: () -> Unit) {
    val target = when (state) {
        VpnState.CONNECTED -> Green
        VpnState.CONNECTING -> Accent
        VpnState.ERROR -> Red
        VpnState.DISCONNECTED -> Accent
    }
    val color by animateColorAsState(target, tween(400), label = "btnColor")

    val label = when (state) {
        VpnState.CONNECTED -> "Подключено"
        VpnState.CONNECTING -> "Подключение…"
        VpnState.ERROR -> "Ошибка"
        VpnState.DISCONNECTED -> "Не подключено"
    }

    // Pulse only while connected.
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )

    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
            if (state == VpnState.CONNECTED) {
                Box(
                    Modifier.size(196.dp).scale(0.7f + pulse * 0.55f).clip(CircleShape)
                        .background(color.copy(alpha = (1f - pulse) * 0.25f))
                )
            }
            Box(Modifier.size(168.dp).clip(CircleShape).background(color.copy(alpha = 0.10f)))
            Box(Modifier.size(132.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)))
            FilledIconButton(
                onClick = onToggle,
                modifier = Modifier.size(108.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = color)
            ) {
                if (state == VpnState.CONNECTING) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                } else {
                    Icon(
                        if (state == VpnState.CONNECTED) Icons.Filled.PowerSettingsNew else Icons.Filled.Power,
                        contentDescription = label, tint = Color.White, modifier = Modifier.size(46.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)

        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 32.dp)) {
            if (server != null) {
                Text(CountryFlags.flagFor(server), fontSize = 18.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(server?.remark ?: "Сервер не выбран", color = Muted, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        if (state == VpnState.CONNECTED && elapsedMs > 0) {
            Text(formatDuration(elapsedMs), color = Green, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── combined status card (location + speed) ─────────────────────────────────────
@Composable
private fun StatusCard(
    state: VpnState,
    location: LocationState,
    loading: Boolean,
    downlink: Long,
    uplink: Long,
    message: String,
    onRefreshLocation: () -> Unit
) {
    val known = location as? LocationState.Known
    Surface(
        color = Surface1, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(known?.info?.flag?.takeIf { it.isNotEmpty() } ?: "🌐", fontSize = 28.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(known?.info?.country ?: "Определяется…", color = OnBg,
                        fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when {
                        known == null -> "местоположение"
                        known.viaVpn -> "${known.info.ip} • через VPN"
                        else -> "${known.info.ip} • ваш реальный IP"
                    }
                    Text(sub, color = if (known?.viaVpn == true) Green else Muted,
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
                } else {
                    IconButton(onClick = onRefreshLocation) {
                        Icon(Icons.Filled.Public, "Обновить", tint = Accent)
                    }
                }
            }

            if (state == VpnState.CONNECTED) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SpeedTile("↓ Загрузка", downlink, Green, Modifier.weight(1f))
                    SpeedTile("↑ Отдача", uplink, Accent, Modifier.weight(1f))
                }
            } else if (message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(message, color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SpeedTile(label: String, bytesPerSec: Long, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Bg).padding(vertical = 10.dp, horizontal = 14.dp)
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(formatSpeed(bytesPerSec), color = OnBg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── small controls ──────────────────────────────────────────────────────────────
@Composable
private fun ActionChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Accent)
        } else {
            Icon(icon, null, Modifier.size(17.dp), tint = Accent)
        }
        Spacer(Modifier.width(4.dp))
        Text(text, color = Accent, fontSize = 13.sp)
    }
}

@Composable
private fun IconToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    val tint = if (active) Green else Muted
    Box(
        Modifier.padding(start = 2.dp).size(38.dp).clip(CircleShape)
            .background(if (active) Green.copy(alpha = 0.14f) else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick) { Icon(icon, "Сортировка по пингу", tint = tint, modifier = Modifier.size(20.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Поиск сервера", color = Muted) },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = Muted) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = Surface1,
            focusedContainerColor = Surface1,
            unfocusedContainerColor = Surface1,
            focusedTextColor = OnBg,
            unfocusedTextColor = OnBg,
            cursorColor = Accent
        )
    )
}

// ── server row ──────────────────────────────────────────────────────────────────
@Composable
private fun ServerRow(
    server: ServerConfig,
    selected: Boolean,
    favorite: Boolean,
    ping: Long?,
    onClick: () -> Unit,
    onFavorite: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (selected) Accent.copy(alpha = 0.12f) else Surface1,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // Country flag for the server.
            Text(CountryFlags.flagFor(server), fontSize = 26.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PingDot(ping)
                    Spacer(Modifier.width(6.dp))
                    Text(server.remark, color = OnBg, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(2.dp))
                Text("${server.protocol.display} • ${server.address}", color = Muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PingLabel(ping)
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "В избранное",
                    tint = if (favorite) Amber else Muted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun PingDot(ping: Long?) {
    val c = when {
        ping == null -> Muted
        ping < 0 -> Red
        ping < 200 -> Green
        ping < 500 -> Amber
        else -> Red
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(c))
}

@Composable
private fun PingLabel(ping: Long?) {
    val (text, color) = when {
        ping == null -> "—" to Muted
        ping < 0 -> "timeout" to Red
        ping < 200 -> "${ping} ms" to Green
        ping < 500 -> "${ping} ms" to Amber
        else -> "${ping} ms" to Red
    }
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

// ── helpers ──────────────────────────────────────────────────────────────────────
private fun formatSpeed(bps: Long): String {
    if (bps < 1024) return "$bps B/s"
    val kb = bps / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB/s", kb)
    return String.format(Locale.US, "%.1f MB/s", kb / 1024.0)
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

@Composable
private fun NebulaTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Accent, onPrimary = Color.White,
        background = Bg, onBackground = OnBg,
        surface = Surface1, onSurface = OnBg,
        onSurfaceVariant = Muted, error = Red
    )
    MaterialTheme(colorScheme = colors, content = content)
}
