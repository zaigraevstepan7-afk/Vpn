package com.nebula.vpn.proxy

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the JSON configuration consumed by the Xray core (libv2ray).
 *
 * Layout:
 *   - a local SOCKS inbound on 127.0.0.1:[socksPort] that tun2socks feeds into
 *   - the selected server as the primary outbound
 *   - freedom/blackhole outbounds + basic routing (block ads/private, bypass LAN)
 */
object XrayConfigBuilder {

    const val SOCKS_PORT = 10808

    fun build(server: ServerConfig, socksPort: Int = SOCKS_PORT): String {
        val root = JSONObject()

        root.put("log", JSONObject().put("loglevel", "warning"))

        // ── inbound ──────────────────────────────────────────────────────────
        val socksInbound = JSONObject()
            .put("tag", "socks-in")
            .put("port", socksPort)
            .put("listen", "127.0.0.1")
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
            .put("sniffing", JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray(listOf("http", "tls"))))
        root.put("inbounds", JSONArray().put(socksInbound))

        // ── outbounds ────────────────────────────────────────────────────────
        val proxyOut = buildOutbound(server)
        val outbounds = JSONArray()
            .put(proxyOut)
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        root.put("outbounds", outbounds)

        // ── routing ──────────────────────────────────────────────────────────
        val rules = JSONArray()
            .put(JSONObject()
                .put("type", "field")
                .put("ip", JSONArray(listOf("geoip:private")))
                .put("outboundTag", "direct"))
            .put(JSONObject()
                .put("type", "field")
                .put("protocol", JSONArray(listOf("bittorrent")))
                .put("outboundTag", "direct"))
        root.put("routing", JSONObject()
            .put("domainStrategy", "IPIfNonMatch")
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
                    put("allowInsecure", s.allowInsecure)
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
