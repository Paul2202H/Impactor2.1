
package com.impactor.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable

/**
 * Android USB transport for Apple device investigation.
 *
 * Detects candidate usbmux interfaces using their USB descriptors.
 * This is NOT yet a complete usbmux implementation.
 */
class AppleUsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint
) : Closeable {

    companion object {
        // Common Apple USB Multiplexor interface descriptor.
        private const val APPLE_USB_CLASS = 0xFF
        private const val APPLE_USB_SUBCLASS = 0xFE
        private const val APPLE_USB_PROTOCOL = 0x02

        data class InterfaceInfo(
            val interfaceNumber: Int,
            val alternateSetting: Int,
            val interfaceClass: Int,
            val interfaceSubclass: Int,
            val interfaceProtocol: Int,
            val endpointCount: Int,
            val bulkInEndpoints: List<Int>,
            val bulkOutEndpoints: List<Int>,
            val isLikelyUsbmux: Boolean
        )

        data class Candidate(
            val usbInterface: UsbInterface,
            val input: UsbEndpoint,
            val output: UsbEndpoint,
            val isLikelyUsbmux: Boolean
        )

        /**
         * Enumerate every interface, including interfaces
         * that do not have bulk endpoints.
         */
        fun inspectInterfaces(device: UsbDevice): List<InterfaceInfo> {
            val result = mutableListOf<InterfaceInfo>()

            for (index in 0 until device.interfaceCount) {
                val intf = device.getInterface(index)

                val bulkIn = mutableListOf<Int>()
                val bulkOut = mutableListOf<Int>()

                for (endpointIndex in 0 until intf.endpointCount) {
                    val endpoint = intf.getEndpoint(endpointIndex)

                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        continue
                    }

                    when (endpoint.direction) {
                        UsbConstants.USB_DIR_IN ->
                            bulkIn.add(endpoint.address)

                        UsbConstants.USB_DIR_OUT ->
                            bulkOut.add(endpoint.address)
                    }
                }

                val likelyUsbmux =
                    intf.interfaceClass == APPLE_USB_CLASS &&
                    intf.interfaceSubclass == APPLE_USB_SUBCLASS &&
                    intf.interfaceProtocol == APPLE_USB_PROTOCOL

                result.add(
                    InterfaceInfo(
                        interfaceNumber = intf.id,
                        alternateSetting = intf.alternateSetting,
                        interfaceClass = intf.interfaceClass,
                        interfaceSubclass = intf.interfaceSubclass,
                        interfaceProtocol = intf.interfaceProtocol,
                        endpointCount = intf.endpointCount,
                        bulkInEndpoints = bulkIn,
                        bulkOutEndpoints = bulkOut,
                        isLikelyUsbmux = likelyUsbmux
                    )
                )
            }

            return result
        }

        /**
         * Find interfaces with both bulk IN and bulk OUT endpoints.
         * Likely Apple usbmux interfaces are listed first.
         */
        fun candidates(device: UsbDevice): List<Candidate> {
            val result = mutableListOf<Candidate>()

            for (index in 0 until device.interfaceCount) {
                val intf = device.getInterface(index)

                val endpoints = (0 until intf.endpointCount)
                    .map { intf.getEndpoint(it) }

                val input = endpoints.firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    it.direction == UsbConstants.USB_DIR_IN
                }

                val output = endpoints.firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    it.direction == UsbConstants.USB_DIR_OUT
                }

                if (input == null || output == null) {
                    continue
                }

                val likelyUsbmux =
                    intf.interfaceClass == APPLE_USB_CLASS &&
                    intf.interfaceSubclass == APPLE_USB_SUBCLASS &&
                    intf.interfaceProtocol == APPLE_USB_PROTOCOL

                result.add(
                    Candidate(
                        usbInterface = intf,
                        input = input,
                        output = output,
                        isLikelyUsbmux = likelyUsbmux
                    )
                )
            }

            return result.sortedByDescending { it.isLikelyUsbmux }
        }
    }

    data class ProbeResult(
        val interfaceNumber: Int,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int,
        val inputEndpoint: Int,
        val outputEndpoint: Int,
        val inputMaxPacket: Int,
        val outputMaxPacket: Int,
        val isLikelyUsbmux: Boolean
    )

    val details = ProbeResult(
        interfaceNumber = usbInterface.id,
        interfaceClass = usbInterface.interfaceClass,
        interfaceSubclass = usbInterface.interfaceSubclass,
        interfaceProtocol = usbInterface.interfaceProtocol,
        inputEndpoint = input.address,
        outputEndpoint = output.address,
        inputMaxPacket = input.maxPacketSize,
        outputMaxPacket = output.maxPacketSize,
        isLikelyUsbmux =
            usbInterface.interfaceClass == APPLE_USB_CLASS &&
            usbInterface.interfaceSubclass == APPLE_USB_SUBCLASS &&
            usbInterface.interfaceProtocol == APPLE_USB_PROTOCOL
    )

    @Synchronized
    fun write(data: ByteArray, timeoutMs: Int = 3000): Int {
        check(data.isNotEmpty()) {
            "USB write buffer is empty"
        }

        return connection.bulkTransfer(
            output,
            data,
            data.size,
            timeoutMs
        )
    }

    @Synchronized
    fun read(
        maxBytes: Int = 64 * 1024,
        timeoutMs: Int = 3000
    ): ByteArray? {
        require(maxBytes in 1..(1024 * 1024)) {
            "Invalid USB read size"
        }

        val buffer = ByteArray(maxBytes)

        val count = connection.bulkTransfer(
            input,
            buffer,
            buffer.size,
            timeoutMs
        )

        return if (count > 0) {
            buffer.copyOf(count)
        } else {
            null
        }
    }

    override fun close() {
        runCatching {
            connection.releaseInterface(usbInterface)
        }

        connection.close()
    }

    companion objectFactory {
        /**
         * Open a verified-looking Apple usbmux interface.
         *
         * Unverified interfaces are rejected by default.
         * Set allowUnverifiedInterface=true only for diagnostics.
         */
        fun open(
            manager: UsbManager,
            device: UsbDevice,
            candidateIndex: Int = 0,
            allowUnverifiedInterface: Boolean = false
        ): AppleUsbTransport {

            check(manager.hasPermission(device)) {
                "Android USB permission has not been granted"
            }

            val candidate = candidates(device).getOrNull(candidateIndex)
                ?: error(
                    "No USB interface with both bulk IN and bulk OUT endpoints was found"
                )

            check(candidate.isLikelyUsbmux || allowUnverifiedInterface) {
                "Selected interface does not match the expected Apple usbmux descriptor. " +
                "Use diagnostic mode only if you intentionally want to inspect it."
            }

            val connection = manager.openDevice(device)
                ?: error("Android could not open the USB device")

            if (!connection.claimInterface(candidate.usbInterface, true)) {
                connection.close()

                error(
                    "Could not claim USB interface ${candidate.usbInterface.id}"
                )
            }

            return AppleUsbTransport(
                connection,
                candidate.usbInterface,
                candidate.input,
                candidate.output
            )
        }
    }
}
