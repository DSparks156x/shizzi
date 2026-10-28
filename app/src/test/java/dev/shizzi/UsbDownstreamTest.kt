package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDownstreamTest {

    private class FakeUsbLink(
        var isConnected: Boolean? = false,
        var isUp: Boolean? = false,
    ) : UsbLink {
        var startCount = 0

        override fun isHostConnected(): Boolean? = isConnected

        override fun isTethered(): Boolean? = isUp

        override fun startTethering(): Pair<Boolean, String> {
            startCount++
            return true to "started"
        }
    }

    @Test
    fun `always refuses to start without a cable`() {
        val usb = UsbDownstream(UsbTethering.ALWAYS, FakeUsbLink(isConnected = false))

        assertEquals(UsbDownstream.NO_USB_CABLE, usb.startProblem())
    }

    @Test
    fun `always starts when the cable state cannot be read`() {
        val usb = UsbDownstream(UsbTethering.ALWAYS, FakeUsbLink(isConnected = null))

        assertNull(usb.startProblem())
    }

    @Test
    fun `auto and never start without a cable`() {
        listOf(UsbTethering.AUTO, UsbTethering.NEVER).forEach { setting ->
            assertNull(setting.name, UsbDownstream(setting, FakeUsbLink()).startProblem())
        }
    }

    @Test
    fun `auto starts only the hotspot without a cable`() {
        val usb = UsbDownstream(UsbTethering.AUTO, FakeUsbLink(isConnected = false))

        assertEquals(listOf(TetheringType.WIFI), usb.typesToStart())
    }

    @Test
    fun `auto starts both with a cable`() {
        val usb = UsbDownstream(UsbTethering.AUTO, FakeUsbLink(isConnected = true))

        assertEquals(listOf(TetheringType.WIFI, TetheringType.USB), usb.typesToStart())
    }

    @Test
    fun `always awaits a pulled cable before giving up`() {
        val usb = UsbDownstream(UsbTethering.ALWAYS, FakeUsbLink())

        assertEquals(DownstreamHealth.Awaiting, usb.check(now = 0))
        assertTrue(usb.isWaitingForCable)
        assertTrue(usb.check(now = UsbReconnect.GIVE_UP_AFTER_MS) is DownstreamHealth.Lost)
    }

    @Test
    fun `auto never ends the session over a missing cable`() {
        val usb = UsbDownstream(UsbTethering.AUTO, FakeUsbLink())

        assertEquals(DownstreamHealth.Up, usb.check(now = 0))
        assertEquals(DownstreamHealth.Up, usb.check(now = UsbReconnect.GIVE_UP_AFTER_MS))
        assertFalse(usb.isWaitingForCable)
    }

    @Test
    fun `restarts usb tethering once a cable is connected`() {
        val link = FakeUsbLink()
        val usb = UsbDownstream(UsbTethering.AUTO, link)
        usb.check(now = 0)

        link.isConnected = true
        usb.check(now = 5_000)

        assertEquals(1, link.startCount)
    }

    @Test
    fun `never leaves usb alone`() {
        val link = FakeUsbLink(isConnected = true)

        assertEquals(DownstreamHealth.Up, UsbDownstream(UsbTethering.NEVER, link).check(now = 0))
        assertEquals(0, link.startCount)
    }

    @Test
    fun `an unreadable tethering state neither restarts nor gives up`() {
        val link = FakeUsbLink(isConnected = true, isUp = null)
        val usb = UsbDownstream(UsbTethering.ALWAYS, link)

        assertEquals(DownstreamHealth.Up, usb.check(now = UsbReconnect.GIVE_UP_AFTER_MS))
        assertEquals(0, link.startCount)
    }
}
