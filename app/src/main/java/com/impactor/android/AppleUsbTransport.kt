package com.impactor.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable

/**
 * Low-level Android USB bulk-I/O probe. This intentionally does not claim to be
 * an Apple usbmux implementation: usbmux framing and device-mode negotiation
 * still need protocol-level integration and hardware validation.
 */
class AppleUsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint
) : Closeable {

    data class ProbeResult(
        val interfaceNumber: Int,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int,
        val inputEndpoint: Int,
        val outputEndpoint: Int,
        val inputMaxPacket: Int,
        val outputMaxPacket: Int
    )

    val details = ProbeResult(
        usbInterface.id,
        usbInterface.interfaceClass,
        usbInterface.interfaceSubclass,
        usbInterface.interfaceProtocol,
        input.address,
        output.address,
        input.maxPacketSize,
        output.maxPacketSize
    )

    @Synchronized
    fun write(data: ByteArray, timeoutMs: Int = 3000): Int {
        check(data.isNotEmpty()) { "USB write buffer is empty" }
        return connection.bulkTransfer(output, data, data.size, timeoutMs)
    }

    @Synchronized
    fun read(maxBytes: Int = 64 * 1024, timeoutMs: Int = 3000): ByteArray? {
        require(maxBytes in 1..(1024 * 1024)) { "Invalid USB read size" }
        val buffer = ByteArray(maxBytes)
        val count = connection.bulkTransfer(input, buffer, buffer.size, timeoutMs)
        return if (count > 0) buffer.copyOf(count) else null
    }

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        connection.close()
    }

    companion object {
        data class Candidate(
            val usbInterface: UsbInterface,
            val input: UsbEndpoint,
            val output: UsbEndpoint
        )

        fun candidates(device: UsbDevice): List<Candidate> {
            val result = mutableListOf<Candidate>()
            for (index in 0 until device.interfaceCount) {
                val intf = device.getInterface(index)
                val endpoints = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                val input = endpoints.firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        it.direction == UsbConstants.USB_DIR_IN
                }
                val output = endpoints.firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        it.direction == UsbConstants.USB_DIR_OUT
                }
                if (input != null && output != null) result += Candidate(intf, input, output)
            }
            return result
        }

        fun open(manager: UsbManager, device: UsbDevice, candidateIndex: Int = 0): AppleUsbTransport {
            check(manager.hasPermission(device)) { "Android USB permission has not been granted" }
            val candidate = candidates(device).getOrNull(candidateIndex)
                ?: error("No USB interface with both bulk IN and bulk OUT endpoints was found")
            val connection = manager.openDevice(device)
                ?: error("Android could not open the USB device")
            if (!connection.claimInterface(candidate.usbInterface, true)) {
                connection.close()
                error("Could not claim USB interface ${candidate.usbInterface.id}")
            }
            return AppleUsbTransport(connection, candidate.usbInterface, candidate.input, candidate.output)
        }
    }
}
