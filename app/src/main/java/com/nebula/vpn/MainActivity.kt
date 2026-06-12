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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.vpn.proxy.Protocol
import com.nebula.vpn.proxy.ServerConfig
import com.nebula.vpn.proxy.V2RayVpnService
import com.nebula.vpn.proxy.VpnState
import com.nebula.vpn.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // To run a REAL tunnel, link the native core and uncomment:
        // CoreController.factory = { com.nebula.vpn.core.XrayCore() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent {
            NebulaTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen(vm)
                }
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

    var query by remember { mutableStateOf("") }
    val filtered = remember(servers, query) {
        if (query.isBlank()) servers
        else servers.filter {
            it.remark.contains(query, true) || it.address.contains(query, true)
        }
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
            if (prepare != null) launcher.launch(prepare)
            else V2RayVpnService.start(context, server)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Nebula VPN", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${servers.size} серверов",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            ConnectCard(state = state, server = selected, message = message, onToggle = ::toggle)

            if (loading) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск по названию или адресу") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp)
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Серверы",
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                TextButton(onClick = { vm.pingTop() }) {
                    Icon(Icons.Filled.Bolt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Пинг")
                }
            }

            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filtered, key = { it.id }) { server ->
                    ServerRow(
                        server = server,
                        selected = server.id == selected?.id,
                        ping = pings[server.id],
                        onClick = { vm.select(server) },
                        onPing = { vm.ping(server) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectCard(
    state: VpnState,
    server: ServerConfig?,
    message: String,
    onToggle: () -> Unit
) {
    val (label, color) = when (state) {
        VpnState.CONNECTED -> "Отключиться" to Color(0xFF36D399)
        VpnState.CONNECTING -> "Подключение…" to MaterialTheme.colorScheme.primary
        VpnState.ERROR -> "Повторить" to MaterialTheme.colorScheme.error
        VpnState.DISCONNECTED -> "Подключиться" to MaterialTheme.colorScheme.primary
    }

    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .padding(vertical = 12.dp)
                .size(168.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(132.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                FilledIconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(104.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = color)
                ) {
                    if (state == VpnState.CONNECTING) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (state == VpnState.CONNECTED) Icons.Filled.Check else Icons.Filled.Power,
                            contentDescription = label,
                            tint = Color.White,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
            }
        }

        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = color)
        Spacer(Modifier.height(4.dp))
        Text(
            server?.remark ?: "Сервер не выбран",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ServerRow(
    server: ServerConfig,
    selected: Boolean,
    ping: Long?,
    onClick: () -> Unit,
    onPing: () -> Unit
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, border)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    server.remark,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${server.address}:${server.port}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ProtocolBadge(server.protocol)
            Spacer(Modifier.width(8.dp))
            PingChip(ping = ping, onPing = onPing)
        }
    }
}

@Composable
private fun ProtocolBadge(protocol: Protocol) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            protocol.display,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PingChip(ping: Long?, onPing: () -> Unit) {
    val (text, color) = when {
        ping == null -> "ping" to MaterialTheme.colorScheme.onSurfaceVariant
        ping < 0 -> "timeout" to MaterialTheme.colorScheme.error
        ping < 200 -> "${ping}ms" to Color(0xFF36D399)
        ping < 500 -> "${ping}ms" to Color(0xFFF6C744)
        else -> "${ping}ms" to MaterialTheme.colorScheme.error
    }
    TextButton(onClick = onPing, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text(text, color = color, fontSize = 12.sp)
    }
}

// ── theme ────────────────────────────────────────────────────────────────────
@Composable
private fun NebulaTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFF6C8CFF),
        onPrimary = Color.White,
        secondary = Color(0xFF8B7BFF),
        background = Color(0xFF0B1020),
        onBackground = Color(0xFFE6E9F5),
        surface = Color(0xFF141A2E),
        onSurface = Color(0xFFE6E9F5),
        onSurfaceVariant = Color(0xFF9AA3BD),
        error = Color(0xFFFF6B6B)
    )
    MaterialTheme(colorScheme = colors, content = content)
}
