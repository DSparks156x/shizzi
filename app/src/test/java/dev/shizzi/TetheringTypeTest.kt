package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Test

class TetheringTypeTest {

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
}
