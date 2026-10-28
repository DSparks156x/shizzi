package dev.shizzi

class DownstreamInspector(private val inspector: UpstreamInspector = UpstreamInspector()) {

    private var cachedCount = 0
    private var lastReadAt = 0L

    private var loggedCount = 0

    fun findTetheredDownstream(types: Collection<TetheringType> = TetheringType.entries): String? {
        val observation = inspector.observe()
        if (observation.didTimeout) return "dumpsys tethering timed out; downstream state unknown"

        val tethered = tetheredInterfaces(observation.rawOutput)
            .filter { name -> tetheringTypeOf(name) in types }

        return when {
            tethered.isEmpty() -> null
            else -> "downstream still tethered: $tethered"
        }
    }

    fun countDevices(types: Collection<TetheringType> = listOf(TetheringType.WIFI)): Int {
        val now = System.currentTimeMillis()
        if (now - lastReadAt < REFRESH_INTERVAL_MS) return cachedCount

        val wifi = if (TetheringType.WIFI in types) readWifiClients() ?: return cachedCount else 0
        val usb = if (TetheringType.USB in types) readUsbClients() ?: return cachedCount else 0

        lastReadAt = now
        cachedCount = wifi + usb
        logCountChange(cachedCount)
        return cachedCount
    }

    private fun readWifiClients(): Int? {
        val observation = inspector.observeWifi()
        if (observation.didTimeout) return null

        return parseDeviceCount(observation.rawOutput)
    }

    /** Each tethered USB interface is one host on the other end of the cable. */
    private fun readUsbClients(): Int? {
        val observation = inspector.observe()
        if (observation.didTimeout) return null

        return tetheredInterfaces(observation.rawOutput)
            .count { name -> tetheringTypeOf(name) == TetheringType.USB }
    }

    private fun tetheredInterfaces(output: String): List<String> =
        output.lineSequence()
            .mapNotNull { line -> TETHERED_STATE_PATTERN.find(line)?.groupValues?.get(1) }
            .distinct()
            .toList()

    private fun logCountChange(count: Int) {
        if (count == loggedCount) return

        val direction = if (count > loggedCount) "connected" else "disconnected"
        loggedCount = count
        SessionLog.info("client $direction: $count now sharing")
    }

    private fun parseDeviceCount(output: String): Int =
        CONNECTED_CLIENTS_PATTERN.findAll(output)
            .mapNotNull { match -> match.groupValues[1].toIntOrNull() }
            .maxOrNull()
            ?: 0

    private companion object {

        private val TETHERED_STATE_PATTERN =
            Regex("""^\s*(\w+)\s+-\s+TetheredState\s+-""")

        private val CONNECTED_CLIENTS_PATTERN =
            Regex("""getConnectedClientList\(\)\.size\(\):\s*(\d+)""")

        private const val REFRESH_INTERVAL_MS = 10_000L
    }
}
