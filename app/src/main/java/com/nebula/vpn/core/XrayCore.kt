package com.nebula.vpn.core

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.nebula.vpn.proxy.VpnManager
import go.Seq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real native engine backed by Xray-core (AndroidLibXrayLite / libv2ray.aar).
 *
 * Modern libv2ray builds (v26.x) take the tun file descriptor directly:
 *
 *     controller.startLoop(configJson, tunFd)
 *
 * The core reads the fd from the `xray.tun.fd` env var and drives the tun
 * device itself through the "tun" inbound declared in the config — so there is
 * no separate tun2socks process to manage. The core's own outbound sockets stay
 * outside the tunnel because [V2RayVpnService] excludes this app's UID from the
 * route via `addDisallowedApplication(packageName)`, which is why the callback
 * interface no longer needs a per-socket `protect()`.
 *
 * Wire it up once (see [CoreController.factory]):
 *
 *     CoreController.factory = { XrayCore() }
 */
class XrayCore : V2RayCore {

    override val isAvailable: Boolean = true

    private var controller: CoreController? = null
    @Volatile
    private var statusCallback: ((String) -> Unit)? = null
    private var statsScope: CoroutineScope? = null

    override fun start(
        service: VpnService,
        tun: ParcelFileDescriptor,
        configJson: String,
        onStatus: (String) -> Unit
    ) {
        statusCallback = onStatus
        initEnv(service.applicationContext)

        val handler = object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(code: Long, msg: String?): Long {
                msg?.let { statusCallback?.invoke(it) }
                return 0
            }
        }

        val c = Libv2ray.newCoreController(handler)
        controller = c

        onStatus("Starting Xray core…")
        // Throws on a bad config or startup failure; the caller (V2RayVpnService)
        // catches it, tears down the tun and reports the error.
        c.startLoop(configJson, tun.fd)

        if (c.isRunning) {
            onStatus("Connected")
            Log.i(TAG, "Xray running (${runCatching { Libv2ray.checkVersionX() }.getOrDefault("?")})")
            startStatsPolling(c)
        } else {
            throw IllegalStateException("Xray core did not start")
        }
    }

    override fun stop() {
        statsScope?.cancel()
        statsScope = null
        VpnManager.setSpeed(0, 0)
        runCatching { controller?.stopLoop() }
        controller = null
        statusCallback = null
    }

    /**
     * Poll the proxy outbound's traffic counters once a second. `queryStats`
     * returns the bytes accumulated since the previous call and resets the
     * counter, so the value over a ~1s tick is the live throughput in bytes/sec.
     */
    private fun startStatsPolling(c: CoreController) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        statsScope = scope
        scope.launch {
            while (isActive) {
                delay(1_000)
                val down = runCatching { c.queryStats(PROXY_TAG, "downlink") }.getOrDefault(0L)
                val up = runCatching { c.queryStats(PROXY_TAG, "uplink") }.getOrDefault(0L)
                VpnManager.setSpeed(down.coerceAtLeast(0), up.coerceAtLeast(0))
            }
        }
    }

    /** One-time native env init: asset path (geoip/geosite) + XUDP base key. */
    private fun initEnv(ctx: Context) {
        if (!envInitialized.compareAndSet(false, true)) return
        runCatching {
            // Lets gomobile read geoip.dat / geosite.dat bundled in the .aar assets.
            Seq.setContext(ctx)
            val assetPath = (ctx.getExternalFilesDir("assets") ?: ctx.getDir("assets", 0)).absolutePath
            val key = deriveXudpBaseKey(ctx)
            Libv2ray.initCoreEnv(assetPath, key)
        }.onFailure {
            envInitialized.set(false)
            Log.e(TAG, "initCoreEnv failed", it)
        }
    }

    private fun deriveXudpBaseKey(ctx: Context): String = runCatching {
        val androidId = (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "nebula-vpn-xudp-fallback-key-000").toByteArray(Charsets.UTF_8)
        Base64.encodeToString(androidId.copyOf(32), Base64.NO_PADDING or Base64.URL_SAFE)
    }.getOrDefault("")

    private companion object {
        const val TAG = "XrayCore"
        const val PROXY_TAG = "proxy"
        val envInitialized = AtomicBoolean(false)
    }
}
