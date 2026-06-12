package com.nebula.vpn.proxy

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Owns the list of servers: fetching a subscription URL, parsing it, caching the
 * raw text to SharedPreferences, and TCP-pinging individual servers.
 *
 * No third-party HTTP/JSON libraries — HttpURLConnection + org.json keep the app tiny.
 */
class SubscriptionRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("nebula", Context.MODE_PRIVATE)

    init {
        // Migrate installs that still hold a superseded default URL: switch to the
        // current default and drop the stale cache/selection so the new list loads.
        val saved = prefs.getString(KEY_URL, null)
        if (saved != null && saved in LEGACY_DEFAULTS) {
            prefs.edit()
                .putString(KEY_URL, DEFAULT_SUBSCRIPTION)
                .remove(KEY_RAW)
                .remove(KEY_SELECTED)
                .apply()
        }
    }

    var subscriptionUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_SUBSCRIPTION) ?: DEFAULT_SUBSCRIPTION
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    /** Id of the last server the user picked, so the choice survives app restarts. */
    var selectedId: String?
        get() = prefs.getString(KEY_SELECTED, null)
        set(value) = prefs.edit().putString(KEY_SELECTED, value).apply()

    /** Ids the user starred. Pinned to the top of the list and persisted. */
    var favorites: Set<String>
        get() = prefs.getStringSet(KEY_FAVS, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FAVS, value).apply()

    /** Load whatever was cached on the last successful fetch. */
    fun loadCached(): List<ServerConfig> {
        val raw = prefs.getString(KEY_RAW, null) ?: return emptyList()
        return parse(raw)
    }

    /** Download the subscription, parse it, and cache the raw text. */
    suspend fun refresh(url: String = subscriptionUrl): List<ServerConfig> =
        withContext(Dispatchers.IO) {
            val raw = httpGet(url)
            prefs.edit().putString(KEY_RAW, raw).putString(KEY_URL, url).apply()
            parse(raw)
        }

    private fun parse(raw: String): List<ServerConfig> {
        // Some subscriptions wrap the whole body in base64; detect and unwrap.
        val text = if (looksLikeUriList(raw)) raw else decodeIfBase64(raw)
        return text.lineSequence()
            .mapNotNull { ServerConfig.parse(it) }
            .toList()
    }

    private fun looksLikeUriList(s: String): Boolean =
        SCHEMES.any { s.contains(it) }

    private fun decodeIfBase64(s: String): String =
        runCatching { ServerConfig.b64Decode(s.replace("\n", "").replace("\r", "")) }
            .getOrDefault(s)

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NebulaVPN/1.0")
        }
        try {
            conn.inputStream.bufferedReader().use { return it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        // The subscription the project ships with. Replace it in-app at any time.
        const val DEFAULT_SUBSCRIPTION =
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt"

        // Older defaults; an install holding one of these is migrated to the current one.
        private val LEGACY_DEFAULTS = setOf(
            "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/all_sub.txt"
        )

        private val SCHEMES = listOf("vmess://", "vless://", "trojan://", "ss://")
        private const val KEY_RAW = "sub_raw"
        private const val KEY_URL = "sub_url"
        private const val KEY_SELECTED = "selected_id"
        private const val KEY_FAVS = "favorite_ids"

        /**
         * Latency in ms of a raw TCP handshake to the server, or -1 on failure.
         * Cheap, dependency-free reachability hint (not a real proxy round-trip).
         */
        suspend fun tcpPing(server: ServerConfig, timeoutMs: Int = 3_000): Long =
            withContext(Dispatchers.IO) {
                val socket = Socket()
                try {
                    val start = System.currentTimeMillis()
                    socket.connect(InetSocketAddress(server.address, server.port), timeoutMs)
                    System.currentTimeMillis() - start
                } catch (e: Exception) {
                    -1L
                } finally {
                    runCatching { socket.close() }
                }
            }
    }
}
