package com.rtbishop.look4sat.feature.settings

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProlificUsbDiscoveryTest {
    private fun device(product: Int, audioOnly: Boolean = false): UsbDevice {
        val input = Shadow.newInstanceOf(UsbEndpoint::class.java).apply {
            ReflectionHelpers.setField(this, "mAddress", 0x83)
            ReflectionHelpers.setField(this, "mAttributes", if (audioOnly) 1 else 2)
        }
        val output = Shadow.newInstanceOf(UsbEndpoint::class.java).apply {
            ReflectionHelpers.setField(this, "mAddress", 0x02)
            ReflectionHelpers.setField(this, "mAttributes", if (audioOnly) 1 else 2)
        }
        val intf = Shadow.newInstanceOf(UsbInterface::class.java).apply {
            ReflectionHelpers.setField(this, "mId", 0)
            ReflectionHelpers.setField(this, "mClass", if (audioOnly) 1 else 255)
            ReflectionHelpers.setField(this, "mEndpoints", arrayOf(input, output))
        }
        return Shadow.newInstanceOf(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this, "mVendorId", 0x067B)
            ReflectionHelpers.setField(this, "mProductId", product)
            ReflectionHelpers.setField(this, "mInterfaces", arrayOf(intf))
        }
    }

    @Test fun reportedAdapterAppearsAsSelectableSerialPortForIcomAndYaesu() {
        val port = device(0x23A3).usbSerialPorts().single()
        assertEquals("PL2303", port.driverName)
        assertEquals(0, port.dataInterfaceId)
        assertTrue(port.supportsLineConfiguration(1, 1))
        assertTrue(port.supportsLineConfiguration(1, 2))
    }

    @Test fun unknownProductsAndAudioOnlyInterfacesAreNotInventedAsSerialDevices() {
        assertTrue(device(0x9999).usbSerialPorts().isEmpty())
        assertTrue(device(0x23A3, audioOnly = true).usbSerialPorts().isEmpty())
    }
}
