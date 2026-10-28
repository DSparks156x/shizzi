package dev.shizzi

/**
 * Decides what a session does with USB for its [UsbTethering] setting: whether
 * it may start, what it brings up, and how the watchdog treats a missing cable.
 */
class UsbDownstream(
    private val setting: UsbTethering,
    private val link: UsbLink,
    private val onEvent: (String) -> Unit = {},
) {

    private val reconnect = UsbReconnect(onEvent)

    /** Only Always waits on the cable; Auto keeps sharing over the hotspot. */
    val isWaitingForCable: Boolean
        get() = setting == UsbTethering.ALWAYS && reconnect.isWaiting

    /** Like the greyed-out stock toggle: Always with no cable has nothing to share with. */
    fun startProblem(): String? = when {
        setting != UsbTethering.ALWAYS -> null
        link.isHostConnected() == false -> NO_USB_CABLE
        else -> null
    }

    /** Auto leaves USB to [check] when no cable is connected yet. */
    fun typesToStart(): List<TetheringType> = when {
        setting != UsbTethering.AUTO -> setting.types
        link.isHostConnected() == false -> listOf(TetheringType.WIFI)
        else -> setting.types
    }

    /** Restarts USB tethering when a cable is connected; called on every watchdog check. */
    fun check(now: Long): DownstreamHealth {
        if (setting == UsbTethering.NEVER) return DownstreamHealth.Up

        val isTethered = link.isTethered()
        if (reconnect.shouldRestart(isTethered, link.isHostConnected() == true, now)) {
            val (didStart, startDetail) = link.startTethering()
            if (!didStart) onEvent("usb tethering did not restart ($startDetail)")
        }
        if (isTethered || setting == UsbTethering.AUTO) return DownstreamHealth.Up

        if (reconnect.hasGivenUp(now)) {
            val minutes = UsbReconnect.GIVE_UP_AFTER_MS / 60_000
            return DownstreamHealth.Lost("no USB cable for $minutes minutes")
        }
        return DownstreamHealth.Awaiting
    }

    companion object {
        const val NO_USB_CABLE = "Connect the phone to a computer with a USB cable first"
    }
}
