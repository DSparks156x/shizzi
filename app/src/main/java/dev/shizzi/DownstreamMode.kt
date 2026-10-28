package dev.shizzi

/** TetheringManager.TETHERING_* constants for the downstreams Shizzi can bring up. */
enum class TetheringType(val code: Int, val label: String) {
    WIFI(0, "hotspot"),
    USB(1, "usb tethering"),
}

enum class DownstreamMode(val types: List<TetheringType>) {
    WIFI(listOf(TetheringType.WIFI)),
    USB(listOf(TetheringType.USB)),
    BOTH(listOf(TetheringType.WIFI, TetheringType.USB)),
}

fun parseDownstreamMode(stored: String?): DownstreamMode =
    runCatching { DownstreamMode.valueOf(stored.orEmpty()) }.getOrDefault(DownstreamMode.WIFI)

private val USB_INTERFACE_PATTERN = Regex("""^(rndis|ncm|usb)\d*$""")

/** Classifies a tethered interface; anything not USB-shaped counts as the hotspot. */
fun tetheringTypeOf(interfaceName: String): TetheringType = when {
    USB_INTERFACE_PATTERN.matches(interfaceName) -> TetheringType.USB
    else -> TetheringType.WIFI
}
