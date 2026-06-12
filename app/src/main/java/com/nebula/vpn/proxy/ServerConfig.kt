package com.nebula.vpn.proxy

import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale

/**
 * A single proxy server, normalised across all supported protocols.
 * The fields map directly onto the Xray outbound JSON produced by [XrayConfigBuilder].
 */
data class ServerConfig(
    val protocol: Protocol,
    val remark: String,
    val address: String,
    val port: Int,
    // credentials: uuid for vmess/vless, password for trojan/shadowsocks
    val password: String = "",
    val alterId: Int = 0,
    // vmess "security" (encryption) or shadowsocks cipher method
    val method: String = "auto",
    // stream
    val network: String = "tcp",          // tcp | ws | grpc | h2 | quic | kcp
    val streamSecurity: String = "none",   // none | tls | reality
    val sni: String = "",
    val alpn: String = "",
    val fingerprint: String = "",
    val allowInsecure: Boolean = true,
    val path: String = "",                 // ws/h2 path, or grpc serviceName
    val host: String = "",                 // ws/h2 Host header
    val flow: String = "",                 // vless flow (e.g. xtls-rprx-vision)
    val publicKey: String = "",            // reality
    val shortId: String = "",              // reality
    val raw: String = ""
) {
    /** Stable id so the UI can key list items and remember the selection. */
    val id: String get() = "${protocol.scheme}:$address:$port:${remark.hashCode()}"

    companion object {
        /**
         * Parse a single subscription line into a [ServerConfig].
         * Returns null for blank lines, comments, and protocols Xray cannot proxy (e.g. ssr).
         */
        fun parse(line: String): ServerConfig? {
            val uri = line.trim()
            if (uri.isEmpty() || uri.startsWith("#")) return null
            return runCatching {
                when {
                    uri.startsWith("vmess://") -> parseVmess(uri)
                    uri.startsWith("vless://") -> parseVless(uri)
                    uri.startsWith("trojan://") -> parseTrojan(uri)
                    uri.startsWith("ss://") -> parseShadowsocks(uri)
                    else -> null // ssr:// and anything else Xray does not support
                }
            }.getOrNull()
        }

        // ── vmess://base64(json) ────────────────────────────────────────────
        private fun parseVmess(uri: String): ServerConfig {
            val json = JSONObject(b64Decode(uri.removePrefix("vmess://")))
            val net = json.optStringOrEmpty("net").ifEmpty { "tcp" }
            return ServerConfig(
                protocol = Protocol.VMESS,
                remark = json.optStringOrEmpty("ps").ifEmpty { json.optStringOrEmpty("add") },
                address = json.optStringOrEmpty("add"),
                port = json.optInt("port", 443).coerceIntFrom(json, "port"),
                password = json.optStringOrEmpty("id"),
                alterId = json.optInt("aid", 0).coerceIntFrom(json, "aid"),
                method = json.optStringOrEmpty("scy").ifEmpty { json.optStringOrEmpty("security").ifEmpty { "auto" } },
                network = net,
                streamSecurity = json.optStringOrEmpty("tls").ifEmpty { "none" }.let { if (it == "none") "none" else "tls" },
                sni = json.optStringOrEmpty("sni"),
                alpn = json.optStringOrEmpty("alpn"),
                fingerprint = json.optStringOrEmpty("fp"),
                path = if (net == "grpc") json.optStringOrEmpty("path") else json.optStringOrEmpty("path"),
                host = json.optStringOrEmpty("host"),
                raw = uri
            )
        }

        // ── vless://uuid@host:port?params#remark ────────────────────────────
        private fun parseVless(uri: String): ServerConfig {
            val p = UriParts.of(uri, "vless://")
            val net = p.q("type").ifEmpty { "tcp" }
            val security = p.q("security").ifEmpty { "none" }
            return ServerConfig(
                protocol = Protocol.VLESS,
                remark = p.fragment.ifEmpty { p.host },
                address = p.host,
                port = p.port,
                password = p.userInfo, // uuid
                method = "none",
                network = net,
                streamSecurity = security,
                sni = p.q("sni").ifEmpty { p.q("host") },
                alpn = p.q("alpn"),
                fingerprint = p.q("fp"),
                path = if (net == "grpc") p.q("serviceName") else p.q("path"),
                host = p.q("host"),
                flow = p.q("flow"),
                publicKey = p.q("pbk"),
                shortId = p.q("sid"),
                raw = uri
            )
        }

        // ── trojan://password@host:port?params#remark ───────────────────────
        private fun parseTrojan(uri: String): ServerConfig {
            val p = UriParts.of(uri, "trojan://")
            val net = p.q("type").ifEmpty { "tcp" }
            return ServerConfig(
                protocol = Protocol.TROJAN,
                remark = p.fragment.ifEmpty { p.host },
                address = p.host,
                port = p.port,
                password = p.userInfo,
                network = net,
                streamSecurity = p.q("security").ifEmpty { "tls" },
                sni = p.q("sni").ifEmpty { p.q("peer") },
                alpn = p.q("alpn"),
                fingerprint = p.q("fp"),
                path = if (net == "grpc") p.q("serviceName") else p.q("path"),
                host = p.q("host"),
                allowInsecure = p.q("allowInsecure") == "1",
                raw = uri
            )
        }

        // ── ss://base64(method:password)@host:port#remark  (SIP002 + legacy) ─
        private fun parseShadowsocks(uri: String): ServerConfig {
            var body = uri.removePrefix("ss://")
            val hashIdx = body.indexOf('#')
            val remark = if (hashIdx >= 0) {
                val r = urlDecode(body.substring(hashIdx + 1)); body = body.substring(0, hashIdx); r
            } else ""
            // strip ?plugin=... if present
            val qIdx = body.indexOf('?')
            if (qIdx >= 0) body = body.substring(0, qIdx)

            val method: String
            val password: String
            val host: String
            val port: Int

            val atIdx = body.lastIndexOf('@')
            if (atIdx >= 0) {
                // SIP002: base64(method:password) @ host:port
                val creds = b64Decode(body.substring(0, atIdx))
                val (m, pw) = creds.splitFirst(':')
                method = m; password = pw
                val hostPort = body.substring(atIdx + 1)
                val (h, pr) = hostPort.splitLast(':')
                host = h; port = pr.toIntOrNull() ?: 443
            } else {
                // legacy: base64(method:password@host:port)
                val decoded = b64Decode(body)
                val at2 = decoded.lastIndexOf('@')
                val creds = decoded.substring(0, at2)
                val (m, pw) = creds.splitFirst(':')
                method = m; password = pw
                val (h, pr) = decoded.substring(at2 + 1).splitLast(':')
                host = h; port = pr.toIntOrNull() ?: 443
            }

            return ServerConfig(
                protocol = Protocol.SHADOWSOCKS,
                remark = remark.ifEmpty { host },
                address = host,
                port = port,
                password = password,
                method = method,
                network = "tcp",
                raw = uri
            )
        }

        // ── helpers ─────────────────────────────────────────────────────────

        private fun JSONObject.optStringOrEmpty(key: String): String =
            if (isNull(key)) "" else optString(key, "").trim()

        // vmess port/aid are sometimes strings, sometimes ints
        private fun Int.coerceIntFrom(json: JSONObject, key: String): Int {
            val v = json.opt(key)
            return when (v) {
                is Int -> v
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: this
                else -> this
            }
        }

        private fun String.splitFirst(ch: Char): Pair<String, String> {
            val i = indexOf(ch)
            return if (i < 0) this to "" else substring(0, i) to substring(i + 1)
        }

        private fun String.splitLast(ch: Char): Pair<String, String> {
            val i = lastIndexOf(ch)
            return if (i < 0) this to "" else substring(0, i) to substring(i + 1)
        }

        internal fun b64Decode(input: String): String {
            var s = input.trim().replace('-', '+').replace('_', '/')
            when (s.length % 4) { 2 -> s += "=="; 3 -> s += "="; }
            val bytes = Base64.decode(s, Base64.DEFAULT)
            return String(bytes, Charsets.UTF_8)
        }

        /** Tiny URI splitter that tolerates emoji/space-laden remarks (which Uri.parse chokes on). */
        private class UriParts(
            val userInfo: String,
            val host: String,
            val port: Int,
            val fragment: String,
            private val query: Map<String, String>
        ) {
            fun q(key: String): String = query[key].orEmpty()

            companion object {
                fun of(uri: String, scheme: String): UriParts {
                    var rest = uri.removePrefix(scheme)
                    val hashIdx = rest.indexOf('#')
                    val fragment = if (hashIdx >= 0) {
                        val f = urlDecode(rest.substring(hashIdx + 1)); rest = rest.substring(0, hashIdx); f
                    } else ""
                    val qIdx = rest.indexOf('?')
                    val query = if (qIdx >= 0) {
                        val qs = rest.substring(qIdx + 1); rest = rest.substring(0, qIdx); parseQuery(qs)
                    } else emptyMap()

                    val atIdx = rest.lastIndexOf('@')
                    val userInfo = if (atIdx >= 0) urlDecode(rest.substring(0, atIdx)) else ""
                    val hostPort = if (atIdx >= 0) rest.substring(atIdx + 1) else rest

                    // handle bracketed IPv6 [::1]:443
                    val (host, port) = if (hostPort.startsWith("[")) {
                        val close = hostPort.indexOf(']')
                        val h = hostPort.substring(1, close)
                        val pr = hostPort.substring(close + 1).removePrefix(":").toIntOrNull() ?: 443
                        h to pr
                    } else {
                        val i = hostPort.lastIndexOf(':')
                        if (i < 0) hostPort to 443
                        else hostPort.substring(0, i) to (hostPort.substring(i + 1).toIntOrNull() ?: 443)
                    }
                    return UriParts(userInfo, host, port, fragment, query)
                }

                private fun parseQuery(qs: String): Map<String, String> =
                    qs.split('&').mapNotNull {
                        val eq = it.indexOf('='); if (eq < 0) null
                        else urlDecode(it.substring(0, eq)).lowercase(Locale.ROOT) to urlDecode(it.substring(eq + 1))
                    }.toMap()
            }
        }
    }
}

enum class Protocol(val scheme: String, val display: String) {
    VMESS("vmess", "VMess"),
    VLESS("vless", "VLESS"),
    TROJAN("trojan", "Trojan"),
    SHADOWSOCKS("ss", "Shadowsocks")
}

private fun urlDecode(s: String): String =
    runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
