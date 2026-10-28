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
    private var usbTethering = UsbTethering.NEVER
    private var watchdog: SessionWatchdog? = null
    private val teardown = SessionTeardown(context)
    private val downstream = DownstreamInspector()
    private var usbReconnect = UsbReconnect()
    private val vpn = VpnUpstream(context) { problem -> tearDownAfter(problem) }

    val isActive: Boolean get() = state == SessionState.ACTIVE

    fun start(mode: VpnMode = VpnMode.AUTO, usb: UsbTethering = UsbTethering.NEVER): String {
        if (isActive) return status()

        vpnMode = mode
        usbTethering = usb
        usbReconnect = UsbReconnect(SessionLog::info)
        state = SessionState.STARTING
        SessionLog.info(
            "session start requested (vpn mode ${mode.name.lowercase()}, " +
                "usb tethering ${usb.name.lowercase()})",
        )

        SessionLog.info(
            "device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), " +
                "contract ${TetherService.CONTRACT_VERSION}",
        )

        // Like the greyed-out stock toggle: no cable means nothing to share
        // with, so refuse before bringing anything up.
        if (usb == UsbTethering.ALWAYS && isUsbHostConnected() == false) {
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

        teardown.installShutdownHook(usbTethering.types)
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
     * Restarts USB tethering when a cable is connected. With Always, USB is the
     * only downstream, so the session ends after a while without one.
     */
    private fun tendUsbDownstream(): DownstreamHealth {
        if (usbTethering == UsbTethering.NEVER) return DownstreamHealth.Up

        val isTethered = downstream.findTetheredDownstream(listOf(TetheringType.USB)) != null
        val now = System.currentTimeMillis()

        if (usbReconnect.shouldRestart(isTethered, isUsbHostConnected() == true, now)) {
            val (didStart, startDetail) = DownstreamControl(context).startTethering(TetheringType.USB)
            if (!didStart) SessionLog.warn("usb tethering did not restart ($startDetail)")
        }
        if (isTethered || usbTethering == UsbTethering.AUTO) return DownstreamHealth.Up

        if (usbReconnect.hasGivenUp(now)) {
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

    /** Auto leaves USB to the watchdog when no cable is connected yet. */
    private fun typesToStart(): List<TetheringType> = when {
        usbTethering != UsbTethering.AUTO -> usbTethering.types
        isUsbHostConnected() == false -> listOf(TetheringType.WIFI)
        else -> usbTethering.types
    }

    private fun restartDownstream() {
        val control = DownstreamControl(context)
        val types = typesToStart()
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

        val downstreamProblem = teardown.releaseDownstream(usbTethering.types)

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
        put("detail", if (isWaitingForCable) "waiting for the USB cable" else detail)
        put("interface", interfaceName ?: JSONObject.NULL)

        put("isVpnBound", vpn.isBound)
        put("isVpnBypassed", isVpnBypassed())

        val traffic = interfaceName?.let(InterfaceCounters::read) ?: Traffic()
        put("bytesUp", traffic.up)
        put("bytesDown", traffic.down)
        put("clientCount", if (isActive) downstream.countDevices(usbTethering.types) else 0)
    }.toString()

    private val isWaitingForCable: Boolean
        get() = isActive && usbTethering == UsbTethering.ALWAYS && usbReconnect.isWaiting

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
