package com.nebula.vpn.core

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Abstraction over the native proxy engine.
 *
 * The real engine is [XrayCore] (backed by `libv2ray.aar`), registered in
 * `MainActivity.onCreate`:
 *
 *     CoreController.factory = { XrayCore() }
 *
 * [StubCore] remains the default fallback so the app still compiles and runs if
 * the native core is ever absent (see README → "Нативное ядро").
 */
interface V2RayCore {

    /** True once a real engine is linked in; false for [StubCore]. */
    val isAvailable: Boolean

    /**
     * Start proxying.
     * @param service the running VpnService — the core MUST call [VpnService.protect]
     *                on its own sockets so outbound traffic does not loop back into the tun.
     * @param tun     the established tun interface; a tun2socks bridge forwards its packets
     *                to the SOCKS inbound declared in [configJson].
     * @param configJson Xray config produced by XrayConfigBuilder.
     * @param onStatus human-readable status callbacks for the UI/logs.
     */
    fun start(
        service: VpnService,
        tun: ParcelFileDescriptor,
        configJson: String,
        onStatus: (String) -> Unit
    )

    fun stop()
}

/** Holds the active core implementation. Swap [factory] to link a real engine. */
object CoreController {

    @Volatile
    var factory: () -> V2RayCore = { StubCore() }

    @Volatile
    private var current: V2RayCore? = null

    val isCoreAvailable: Boolean get() = factory().isAvailable

    fun start(
        service: VpnService,
        tun: ParcelFileDescriptor,
        configJson: String,
        onStatus: (String) -> Unit
    ): Boolean {
        val core = factory()
        if (!core.isAvailable) {
            onStatus("Native Xray core is not linked — see README (\"Wiring the native core\").")
            return false
        }
        current = core
        core.start(service, tun, configJson, onStatus)
        return true
    }

    fun stop() {
        current?.let { runCatching { it.stop() } }
        current = null
    }
}

/**
 * No-op engine. Lets the full app run end-to-end (parse subscription → pick server →
 * generate config → request VPN permission → establish tun) without bundling the Go core.
 * It deliberately does NOT capture traffic, so your connection keeps working normally
 * while you develop the UI.
 */
class StubCore : V2RayCore {
    override val isAvailable: Boolean = false

    override fun start(
        service: VpnService,
        tun: ParcelFileDescriptor,
        configJson: String,
        onStatus: (String) -> Unit
    ) {
        Log.i(TAG, "StubCore.start — native core absent. Generated config:\n$configJson")
        onStatus("Stub core active: config validated, but no traffic is being tunnelled.")
    }

    override fun stop() {
        Log.i(TAG, "StubCore.stop")
    }

    private companion object { const val TAG = "StubCore" }
}
