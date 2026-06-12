package com.nebula.vpn.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nebula.vpn.MainActivity
import com.nebula.vpn.R
import com.nebula.vpn.core.CoreController

/**
 * The Android VpnService. It:
 *   1. builds a tun interface that routes all traffic into the app,
 *   2. asks the pluggable core ([CoreController]) to proxy that traffic,
 *   3. runs in the foreground with a persistent notification.
 *
 * Server selection is passed via the start intent (the server's raw URI).
 */
class V2RayVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
            else -> {
                val rawUri = intent?.getStringExtra(EXTRA_SERVER_URI)
                val server = rawUri?.let { ServerConfig.parse(it) }
                if (server == null) {
                    VpnManager.setMessage("No server selected")
                    VpnManager.setState(VpnState.ERROR)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startVpn(server)
            }
        }
        return START_STICKY
    }

    private fun startVpn(server: ServerConfig) {
        VpnManager.setState(VpnState.CONNECTING)
        VpnManager.setActiveServer(server)
        startForegroundCompat(server)

        val tun = try {
            buildTun(server)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish tun", e)
            fail("Failed to establish VPN interface: ${e.message}")
            return
        }
        tunInterface = tun

        val configJson = XrayConfigBuilder.build(server)
        val started = try {
            CoreController.start(this, tun, configJson) { status ->
                VpnManager.setMessage(status)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Core failed to start", e)
            runCatching { tun.close() }
            tunInterface = null
            fail("Core failed to start: ${e.message}")
            return
        }

        if (!started) {
            // No native core linked: the tun is up but nothing tunnels traffic,
            // which would blackhole the connection — so tear it down and report.
            runCatching { tun.close() }
            tunInterface = null
            fail("Connected at the Android layer, but the Xray core is not linked. See README.")
            return
        }

        // Bridge the tun device to the core's SOCKS inbound via hev-socks5-tunnel.
        try {
            TProxyService.start(filesDir, tun, XrayConfigBuilder.SOCKS_PORT, MTU)
        } catch (e: Throwable) {
            Log.e(TAG, "tun2socks failed to start", e)
            CoreController.stop()
            runCatching { tun.close() }
            tunInterface = null
            fail("tun2socks failed: ${e.message}")
            return
        }
        VpnManager.setState(VpnState.CONNECTED)
    }

    private fun buildTun(server: ServerConfig): ParcelFileDescriptor {
        // IPv4-only tun: hev-socks5-tunnel is configured for IPv4, so we don't
        // capture IPv6 (avoids a v6 black-hole when the route has no handler).
        val builder = Builder()
            .setSession("Root VPN")
            .setMtu(MTU)
            .addAddress(PRIVATE_VLAN4, 30)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)

        // Don't route this app's own traffic through the tunnel (avoids loops).
        runCatching { builder.addDisallowedApplication(packageName) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        return builder.establish() ?: throw IllegalStateException("establish() returned null")
    }

    private fun stopVpn() {
        TProxyService.stop()
        CoreController.stop()
        runCatching { tunInterface?.close() }
        tunInterface = null
        VpnManager.setState(VpnState.DISCONNECTED)
        VpnManager.setActiveServer(null)
        stopForegroundCompat()
        stopSelf()
    }

    private fun fail(message: String) {
        VpnManager.setMessage(message)
        VpnManager.setState(VpnState.ERROR)
        stopForegroundCompat()
        stopSelf()
    }

    override fun onRevoke() {
        // User toggled the VPN off in system settings, or another VPN took over.
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        TProxyService.stop()
        CoreController.stop()
        runCatching { tunInterface?.close() }
        super.onDestroy()
    }

    // ── foreground notification ────────────────────────────────────────────
    private fun startForegroundCompat(server: ServerConfig) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nebula VPN")
            .setContentText(server.remark)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "com.nebula.vpn.START"
        const val ACTION_STOP = "com.nebula.vpn.STOP"
        const val EXTRA_SERVER_URI = "server_uri"

        private const val TAG = "V2RayVpnService"
        private const val CHANNEL_ID = "nebula_vpn"
        private const val NOTIF_ID = 1
        private const val PRIVATE_VLAN4 = "10.10.10.10"
        private const val MTU = 1500

        fun start(context: Context, server: ServerConfig) {
            val intent = Intent(context, V2RayVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SERVER_URI, server.raw)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, V2RayVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
