package com.nebula.vpn.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** Where the device currently looks like it is, as seen from the public internet. */
data class GeoInfo(
    val country: String,
    val countryCode: String,
    val ip: String
) {
    /** ISO-3166 alpha-2 → flag emoji, e.g. "RU" → 🇷🇺. Empty if the code is unusable. */
    val flag: String
        get() {
            if (countryCode.length != 2) return ""
            val sb = StringBuilder()
            for (ch in countryCode.uppercase()) {
                if (ch !in 'A'..'Z') return ""
                sb.appendCodePoint(0x1F1E6 + (ch - 'A'))
            }
            return sb.toString()
        }
}

/**
 * Looks up the current public-facing country / IP.
 *
 * Crucially, this app excludes its own UID from the VPN route (see
 * [V2RayVpnService.buildTun] → `addDisallowedApplication`), so a *direct* request
 * always egresses with the real IP — even while the tunnel is up. To reflect the
 * VPN exit country we therefore route the lookup through the core's local SOCKS
 * inbound on 127.0.0.1:[XrayConfigBuilder.SOCKS_PORT].
 *
 *   - tunnel OFF → [locate] with `viaProxy = false` → your real location.
 *   - tunnel ON  → [locate] with `viaProxy = true`  → the server's location.
 */
object GeoLocator {

    // ip-api.com: free, no key, supports lang=ru ("Россия"), returns the egress IP.
    private const val ENDPOINT =
        "http://ip-api.com/json/?fields=status,country,countryCode,query&lang=ru"

    suspend fun locate(viaProxy: Boolean): GeoInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val proxy = if (viaProxy) {
                Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", XrayConfigBuilder.SOCKS_PORT))
            } else {
                Proxy.NO_PROXY
            }
            val conn = (URL(ENDPOINT).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "NebulaVPN/1.0")
            }
            try {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                if (json.optString("status") != "success") return@runCatching null
                GeoInfo(
                    country = json.optString("country").ifEmpty { json.optString("countryCode") },
                    countryCode = json.optString("countryCode"),
                    ip = json.optString("query")
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
