package com.nebula.vpn.proxy

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the JSON configuration consumed by the Xray core (libv2ray).
 *
 * Layout:
 *   - a "tun" inbound: the core reads the VPN tun fd (passed to startLoop via the
 *     `xray.tun.fd` env var) and drives the device itself — this is what actually
 *     captures and tunnels device traffic
 *   - a local SOCKS inbound on 127.0.0.1:[socksPort] (handy for testing / apps)
 *   - the selected server as the primary outbound
 *   - freedom/blackhole outbounds + basic routing (bypass LAN, direct bittorrent)
 */
object XrayConfigBuilder {

    const val SOCKS_PORT = 10808

    private val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.168.0.0/16", "::1/128", "fc00::/7", "fe80::/10"
    )

    fun build(server: ServerConfig, socksPort: Int = SOCKS_PORT): String {
        val root = JSONObject()

        root.put("log", JSONObject().put("loglevel", "warning"))

        // Stats + level-8 policy: the core's CoreController grabs the stats
        // manager on startup, so these blocks must be present.
        root.put("stats", JSONObject())
        root.put("policy", JSONObject()
            .put("levels", JSONObject().put("8", JSONObject()
                .put("handshake", 4)
                .put("connIdle", 300)
                .put("uplinkOnly", 1)
                .put("downlinkOnly", 1)))
            .put("system", JSONObject()
                .put("statsOutboundUplink", true)
                .put("statsOutboundDownlink", true)))

        // ── inbound ───────────────────────────────────────────────────────────
        // A single SOCKS inbound. hev-socks5-tunnel (TProxyService) reads the VPN
        // tun device and forwards every packet here; the core proxies it onward.
        val socksInbound = JSONObject()
            .put("tag", "socks-in")
            .put("port", socksPort)
            .put("listen", "127.0.0.1")
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("userLevel", 8))
            .put("sniffing", JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray(listOf("http", "tls", "quic"))))
        root.put("inbounds", JSONArray().put(socksInbound))

        // ── DNS ───────────────────────────────────────────────────────────────
        root.put("dns", JSONObject()
            .put("servers", JSONArray(listOf("1.1.1.1", "8.8.8.8"))))

        // ── outbounds ────────────────────────────────────────────────────────
        val proxyOut = buildOutbound(server)
        val outbounds = JSONArray()
            .put(proxyOut)
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        root.put("outbounds", outbounds)

        // ── routing ──────────────────────────────────────────────────────────
        // Bypass LAN/private destinations with explicit CIDRs instead of
        // "geoip:private": the gomobile core build fails to build geoip-based
        // rules ("failed to build routing configuration"), and explicit ranges
        // need no geoip.dat at all.
        val rules = JSONArray()
            .put(JSONObject()
                .put("type", "field")
                .put("outboundTag", "direct")
                .put("ip", JSONArray(PRIVATE_CIDRS)))
            .put(JSONObject()
                .put("type", "field")
                .put("outboundTag", "direct")
                .put("protocol", JSONArray(listOf("bittorrent"))))
        root.put("routing", JSONObject()
            .put("domainStrategy", "AsIs")
            .put("rules", rules))

        return root.toString(2)
    }

    private fun buildOutbound(s: ServerConfig): JSONObject {
        val out = JSONObject().put("tag", "proxy").put("protocol", protocolName(s))
        out.put("settings", buildSettings(s))
        out.put("streamSettings", buildStream(s))
        return out
    }

    private fun protocolName(s: ServerConfig): String = when (s.protocol) {
        Protocol.VMESS -> "vmess"
        Protocol.VLESS -> "vless"
        Protocol.TROJAN -> "trojan"
        Protocol.SHADOWSOCKS -> "shadowsocks"
    }

    private fun buildSettings(s: ServerConfig): JSONObject = when (s.protocol) {
        Protocol.VMESS -> {
            val user = JSONObject()
                .put("id", s.password)
                .put("alterId", s.alterId)
                .put("security", s.method.ifEmpty { "auto" })
                .put("level", 8)
            val node = JSONObject()
                .put("address", s.address).put("port", s.port)
                .put("users", JSONArray().put(user))
            JSONObject().put("vnext", JSONArray().put(node))
        }

        Protocol.VLESS -> {
            val user = JSONObject()
                .put("id", s.password)
                .put("encryption", "none")
                .put("level", 8)
            if (s.flow.isNotEmpty()) user.put("flow", s.flow)
            val node = JSONObject()
                .put("address", s.address).put("port", s.port)
                .put("users", JSONArray().put(user))
            JSONObject().put("vnext", JSONArray().put(node))
        }

        Protocol.TROJAN -> {
            val node = JSONObject()
                .put("address", s.address).put("port", s.port)
                .put("password", s.password).put("level", 8)
            JSONObject().put("servers", JSONArray().put(node))
        }

        Protocol.SHADOWSOCKS -> {
            val node = JSONObject()
                .put("address", s.address).put("port", s.port)
                .put("method", s.method).put("password", s.password)
                .put("level", 8)
            JSONObject().put("servers", JSONArray().put(node))
        }
    }

    private fun buildStream(s: ServerConfig): JSONObject {
        val stream = JSONObject().put("network", s.network)

        // transport
        when (s.network) {
            "ws" -> stream.put("wsSettings", JSONObject().apply {
                if (s.path.isNotEmpty()) put("path", s.path)
                if (s.host.isNotEmpty()) put("headers", JSONObject().put("Host", s.host))
            })
            "grpc" -> stream.put("grpcSettings", JSONObject().apply {
                put("serviceName", s.path)
                put("multiMode", false)
            })
            "h2", "http" -> stream.put("httpSettings", JSONObject().apply {
                if (s.path.isNotEmpty()) put("path", s.path)
                if (s.host.isNotEmpty()) put("host", JSONArray(s.host.split(",")))
            })
            "kcp" -> stream.put("kcpSettings", JSONObject().put("header",
                JSONObject().put("type", if (s.host.isNotEmpty()) s.host else "none")))
            // "tcp" needs no extra block for the common case
        }

        // security
        when (s.streamSecurity) {
            "tls" -> {
                stream.put("security", "tls")
                stream.put("tlsSettings", JSONObject().apply {
                    val server = s.sni.ifEmpty { s.host.ifEmpty { s.address } }
                    put("serverName", server)
                    // NOTE: "allowInsecure" was removed from current Xray-core's JSON
                    // config parser (it now aborts the build), so we no longer emit it.
                    if (s.alpn.isNotEmpty()) put("alpn", JSONArray(s.alpn.split(",")))
                    if (s.fingerprint.isNotEmpty()) put("fingerprint", s.fingerprint)
                })
            }
            "reality" -> {
                stream.put("security", "reality")
                stream.put("realitySettings", JSONObject().apply {
                    put("serverName", s.sni.ifEmpty { s.address })
                    put("publicKey", s.publicKey)
                    if (s.shortId.isNotEmpty()) put("shortId", s.shortId)
                    put("fingerprint", s.fingerprint.ifEmpty { "chrome" })
                })
            }
            else -> stream.put("security", "none")
        }

        return stream
    }
}
