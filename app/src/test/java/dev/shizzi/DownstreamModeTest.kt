package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Test

class DownstreamModeTest {

    @Test
    fun `classifies usb gadget interfaces as usb`() {
        listOf("rndis0", "ncm0", "usb0").forEach { name ->
            assertEquals(name, TetheringType.USB, tetheringTypeOf(name))
        }
    }

    @Test
    fun `classifies hotspot interfaces as wifi`() {
        listOf("wlan0", "wlan1", "ap0", "swlan0").forEach { name ->
            assertEquals(name, TetheringType.WIFI, tetheringTypeOf(name))
        }
    }

    @Test
    fun `both covers wifi and usb`() {
        assertEquals(listOf(TetheringType.WIFI, TetheringType.USB), DownstreamMode.BOTH.types)
    }

    @Test
    fun `tethering types match the framework constants`() {
        assertEquals(0, TetheringType.WIFI.code)
        assertEquals(1, TetheringType.USB.code)
    }
}
