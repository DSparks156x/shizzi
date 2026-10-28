package dev.shizzi

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject

enum class SessionState { IDLE, STARTING, ACTIVE, ERROR }

class TetherSession(private val context: Context) {

    private val testNetworkApi = TestNetworkApi(context)
    private val inspector = UpstreamInspector()

    private var resources: SessionResources? = null
    private var state = SessionState.IDLE
    private var detail = "not started"
    private var interfaceName: String? = null

    private var activeSince: Long = 0
    private var vpnMode = VpnMode.AUTO
    private var downstreamMode = DownstreamMode.WIFI
    private var watchdog: SessionWatchdog? = null
    private val teardown = SessionTeardown(context)
    private val downstream = DownstreamInspector()
    private var usbReconnect = UsbReconnect()
    private val vpn = VpnUpstream(context) { problem -> tearDownAfter(problem) }

    val isActive: Boolean get() = state == SessionState.ACTIVE

    fun start(mode: VpnMode = VpnMode.AUTO, downstream: DownstreamMode = DownstreamMode.WIFI): String {
        if (isActive) return status()

        vpnMode = mode
        downstreamMode = downstream
        usbReconnect = UsbReconnect(SessionLog::info)
        state = SessionState.STARTING
        SessionLog.info(
            "session start requested (vpn mode ${mode.name.lowercase()}, " +
                "downstream ${downstream.name.lowercase()})",
        )

        SessionLog.info(
            "device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), " +
                "contract ${TetherService.CONTRACT_VERSION}",
        )

        // Like the greyed-out stock toggle: no cable means nothing to share
        // with, so refuse before bringing anything up.
        if (downstream == DownstreamMode.USB && isUsbHostConnected() == false) {
            SessionLog.warn("start refused: no USB cable connected")
            state = SessionState.ERROR
            detail = NO_USB_CABLE
            return status()
        }

        return runCatching { bringUp() }
            .getOrElse { failure ->
                Log.e(TAG, "start failed", failure)
                SessionLog.error(
                    "start failed: ${failure.javaClass.simpleName}: ${failure.message}",
                )
                stop()
                state = SessionState.ERROR
                detail = "${failure.javaClass.simpleName}: ${failure.message}"
                status()
            }
    }

    private fun bringUp(): String {
        val group = SessionResources(testNetworkApi, context.connectivityManager())
        resources = group

        val name = group.acquire(tunAddresses(), TEST_NETWORK_DNS_SERVERS, AVAILABILITY_TIMEOUT_MS)
        interfaceName = name
        SessionLog.info("tun up: $name (mtu $TUN_MTU, $TUN_ADDRESS, $TUN_ADDRESS_V6)")

        group.startDatapath(TUN_MTU)
        SessionLog.info("datapath attached to $name")
        followVpn(group)

        preferTestNetworks()
        restartDownstream()

        verifyUpstream(name)
        SessionLog.info("upstream verified: $name is sole upstream")

        state = SessionState.ACTIVE
        activeSince = System.currentTimeMillis()
        detail = "tethered clients routing through $name"

        teardown.installShutdownHook(downstreamMode.types)
        startWatchdog(name)
        return status()
    }

    private fun followVpn(group: SessionResources) {
        if (vpnMode == VpnMode.NEVER) {
            SessionLog.info("vpn mode never: datapath left unbound")
            return
        }

        check(vpnMode != VpnMode.ALWAYS || vpn.isVpnPresent()) {
            "followVpn: vpn mode always requires an active VPN, none is connected"
        }
        vpn.follow(group)
    }

    private fun startWatchdog(name: String) {
        val guard = SessionWatchdog(name, checkDownstream = ::tendUsbDownstream) { problem ->
            SessionLog.warn("ending session: $problem")
            tearDownAfter(problem)
        }
        watchdog = guard
        guard.start()
    }

    /**
     * Restarts USB tethering when the cable returns. Gives up after a while
     * without one, unless another of the session's downstreams is still up.
     */
    private fun tendUsbDownstream(): DownstreamHealth {
        if (TetheringType.USB !in downstreamMode.types) return DownstreamHealth.Up

        val isTethered = downstream.findTetheredDownstream(listOf(TetheringType.USB)) != null
        val now = System.currentTimeMillis()

        if (usbReconnect.shouldRestart(isTethered, isUsbHostConnected() == true, now)) {
            val (didStart, startDetail) = DownstreamControl(context).startTethering(TetheringType.USB)
            if (!didStart) SessionLog.warn("usb tethering did not restart ($startDetail)")
        }
        if (isTethered) return DownstreamHealth.Up

        val others = downstreamMode.types - TetheringType.USB
        val isOtherUp = others.isNotEmpty() && downstream.findTetheredDownstream(others) != null
        if (usbReconnect.hasGivenUp(now) && !isOtherUp) {
            val minutes = UsbReconnect.GIVE_UP_AFTER_MS / 60_000
            return DownstreamHealth.Lost("no USB cable for $minutes minutes")
        }
        return DownstreamHealth.Awaiting
    }

    /** Null when `dumpsys usb` could not be read. */
    private fun isUsbHostConnected(): Boolean? {
        val observation = inspector.observeUsb()
        if (observation.didTimeout) return null

        return parseUsbHostConnected(observation.rawOutput)
    }

    private fun tearDownAfter(problem: String) {
        stop()

        val teardownProblem = detail.takeIf { state == SessionState.ERROR }
        state = SessionState.ERROR
        detail = when (teardownProblem) {
            null -> problem
            else -> "$problem; teardown incomplete: $teardownProblem"
        }
    }

    private fun preferTestNetworks() {
        val api = TetheringPreferenceApi(context)
        runCatching { api.setPreferTestNetworks(false) }
            .onFailure { SessionLog.warn("could not clear the stale test-network preference: ${it.message}") }
        api.setPreferTestNetworks(true)
    }

    private fun restartDownstream() {
        val control = DownstreamControl(context)
        val types = downstreamMode.types
        control.stopTethering(types)

        val (started, failed) = types
            .associateWith(control::startTethering)
            .entries
            .partition { (_, outcome) -> outcome.first }

        val failures = failed.map { (type, outcome) -> "${type.label} did not start (${outcome.second})" }
        check(started.isNotEmpty()) { "restartDownstream: ${failures.joinToString("; ")}" }

        failures.forEach(SessionLog::warn)
        awaitDownstreamTethered(started.map { it.key })
    }

    private fun awaitDownstreamTethered(types: List<TetheringType>) {
        val deadline = System.currentTimeMillis() + DOWNSTREAM_SETTLE_MS
        val downstream = DownstreamInspector()

        while (System.currentTimeMillis() < deadline) {

            if (downstream.findTetheredDownstream(types) != null) {
                SessionLog.info("downstream tethered")
                return
            }
            Thread.sleep(DOWNSTREAM_POLL_MS)
        }

        val hint = if (TetheringType.USB in types) " (is a USB cable connected to a computer?)" else ""
        SessionLog.warn("downstream not tethered after ${DOWNSTREAM_SETTLE_MS}ms$hint; continuing")
    }

    private fun verifyUpstream(name: String) {
        val deadline = System.currentTimeMillis() + UPSTREAM_SETTLE_MS
        var observed = liveUpstreams(name)

        while (System.currentTimeMillis() < deadline) {
            if (observed.isNotEmpty() && observed.all { it == name }) return
            Thread.sleep(UPSTREAM_POLL_MS)
            observed = liveUpstreams(name)
        }
        error("verifyUpstream: expected only $name, tethering reports $observed")
    }

    private fun liveUpstreams(owned: String): List<String> =
        inspector.observe().liveInterfaceNames(owned)

    fun stop(): String {
        watchdog?.stop()
        watchdog = null
        vpn.stop()
        teardown.removeShutdownHook()

        val summary = if (activeSince == 0L) null else sessionSummary()

        val downstreamProblem = teardown.releaseDownstream(downstreamMode.types)

        teardown.releaseUpstreamSelection(interfaceName)

        val releaseProblems = resources?.release().orEmpty()
        resources = null

        interfaceName = null
        activeSince = 0
        state = if (downstreamProblem == null) SessionState.IDLE else SessionState.ERROR
        detail = downstreamProblem ?: "stopped"

        when (downstreamProblem) {
            null -> SessionLog.info("session stopped; downstream confirmed down")
            else -> SessionLog.error("teardown: $downstreamProblem")
        }
        if (releaseProblems.isNotEmpty()) {
            SessionLog.warn("${releaseProblems.size} resource(s) not released cleanly")
        }
        summary?.let(SessionLog::info)
        return status()
    }

    private fun sessionSummary(): String {
        val traffic = interfaceName?.let(InterfaceCounters::read) ?: Traffic()
        val elapsed = when (activeSince) {
            0L -> 0L
            else -> System.currentTimeMillis() - activeSince
        }

        return "session summary: active ${formatDuration(elapsed)}, " +
            "${Traffic.format(traffic.up)} up, ${Traffic.format(traffic.down)} down"
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun status(): String = JSONObject().apply {
        put("state", state.name)
        put("detail", if (isActive && usbReconnect.isWaiting) "waiting for the USB cable" else detail)
        put("interface", interfaceName ?: JSONObject.NULL)

        put("isVpnBound", vpn.isBound)
        put("isVpnBypassed", isVpnBypassed())

        val traffic = interfaceName?.let(InterfaceCounters::read) ?: Traffic()
        put("bytesUp", traffic.up)
        put("bytesDown", traffic.down)
        put("clientCount", if (isActive) downstream.countDevices(downstreamMode.types) else 0)
    }.toString()

    private fun isVpnBypassed(): Boolean {
        if (vpnMode != VpnMode.NEVER) return false
        if (!isActive) return false

        return vpn.isVpnPresent()
    }

    private fun tunAddresses() = listOf(
        buildLinkAddress(java.net.InetAddress.getByName(TUN_ADDRESS), TUN_PREFIX_LENGTH),
        buildLinkAddress(java.net.InetAddress.getByName(TUN_ADDRESS_V6), TUN_PREFIX_LENGTH_V6),
    )

    private companion object {
        const val TAG = "TetherSession"
        const val TUN_ADDRESS = "192.0.2.2"
        const val TUN_PREFIX_LENGTH = 24

        const val TUN_ADDRESS_V6 = "2001:db8::2"
        const val TUN_PREFIX_LENGTH_V6 = 64

        const val TUN_MTU = 1500
        const val AVAILABILITY_TIMEOUT_MS = 10_000
        const val UPSTREAM_SETTLE_MS = 8_000L
        const val UPSTREAM_POLL_MS = 500L

        const val DOWNSTREAM_SETTLE_MS = 10_000L
        const val DOWNSTREAM_POLL_MS = 500L

        const val NO_USB_CABLE = "Connect the phone to a computer with a USB cable first"
    }
}
