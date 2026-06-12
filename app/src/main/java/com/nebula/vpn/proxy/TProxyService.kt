package com.nebula.vpn.proxy

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * JNI bridge to hev-socks5-tunnel (libhev-socks5-tunnel.so).
 *
 * This is the tun2socks layer: it reads packets from the VPN `tun` device and
 * forwards them to a local SOCKS5 server (the Xray core's SOCKS inbound on
 * 127.0.0.1:[XrayConfigBuilder.SOCKS_PORT]). This is the same, well-tested path
 * v2rayNG uses by default — far more reliable than feeding the tun fd straight
 * into Xray's experimental "tun" inbound.
 *
 * The native library was built with `-DPKGNAME=com/nebula/vpn/proxy`, so its
 * JNI registration targets exactly this class.
 */
object TProxyService {

    @Volatile
    private var running = false

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int)

    @JvmStatic
    external fun TProxyStopService()

    @JvmStatic
    external fun TProxyGetStats(): LongArray?

    /** Start bridging [tun] → SOCKS5 127.0.0.1:[socksPort]. Returns on start (runs on its own thread). */
    fun start(filesDir: File, tun: ParcelFileDescriptor, socksPort: Int = XrayConfigBuilder.SOCKS_PORT, mtu: Int = 1500) {
        if (running) return
        val yaml = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: $mtu")
            appendLine("  ipv4: 10.10.10.10")
            appendLine("socks5:")
            appendLine("  port: $socksPort")
            appendLine("  address: 127.0.0.1")
            appendLine("  udp: 'udp'")
            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: 300000")
            appendLine("  udp-read-write-timeout: 60000")
            appendLine("  log-level: warn")
        }
        val cfg = File(filesDir, "hev-socks5-tunnel.yaml").apply { writeText(yaml) }
        TProxyStartService(cfg.absolutePath, tun.fd)
        running = true
        Log.i(TAG, "hev-socks5-tunnel started (fd=${tun.fd} -> 127.0.0.1:$socksPort)")
    }

    fun stop() {
        if (!running) return
        runCatching { TProxyStopService() }
        running = false
        Log.i(TAG, "hev-socks5-tunnel stopped")
    }

    private const val TAG = "TProxyService"

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }
}
