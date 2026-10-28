package dev.shizzi

/**
 * Android drops USB tethering when the cable is pulled and leaves it off when
 * it returns, so a session asks for it again once a host is back.
 *
 * Kept free of Android types so the decisions are directly testable.
 */
class UsbReconnect(private val onEvent: (String) -> Unit = {}) {

    var isWaiting = false
        private set

    private var lastAttemptAt: Long? = null
    private var waitingSince: Long? = null

    /** True once USB has been down for longer than the session should wait. */
    fun hasGivenUp(now: Long): Boolean {
        val since = waitingSince ?: return false
        return now - since >= GIVE_UP_AFTER_MS
    }

    /** True when USB tethering should be requested again right now. */
    fun shouldRestart(isTethered: Boolean, isCableConnected: Boolean, now: Long): Boolean {
        if (isTethered) {
            if (isWaiting) onEvent("usb tethering back up")
            isWaiting = false
            lastAttemptAt = null
            waitingSince = null
            return false
        }

        if (!isWaiting) {
            onEvent("usb tethering down; waiting for a usb host")
            waitingSince = now
        }
        isWaiting = true
        if (!isCableConnected) return false

        val last = lastAttemptAt
        if (last != null && now - last < RETRY_INTERVAL_MS) return false

        lastAttemptAt = now
        onEvent("usb host connected; restarting usb tethering")
        return true
    }

    companion object {
        private const val RETRY_INTERVAL_MS = 15_000L

        /** Matches the stock hotspot's turn-off-when-idle delay. */
        const val GIVE_UP_AFTER_MS = 10 * 60_000L
    }
}

private val DEVICE_CONNECTED_PATTERN = Regex("""^\s*connected=(true|false)\s*$""", RegexOption.MULTILINE)

/** Reads whether a USB host is attached from `dumpsys usb`'s device_manager block. */
fun parseUsbHostConnected(output: String): Boolean? {
    val start = output.indexOf("device_manager={")
    if (start < 0) return null

    val end = output.indexOf("host_manager={", start).takeIf { it > start } ?: output.length
    val block = output.substring(start, end)

    return DEVICE_CONNECTED_PATTERN.find(block)?.groupValues?.get(1)?.toBooleanStrictOrNull()
}
