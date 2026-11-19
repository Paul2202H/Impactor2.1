
package com.impactor.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable

/**
 * Android USB transport for Apple device diagnostics.
 *
 * Detects candidate usbmux interfaces using USB descriptors.
 * This is not yet a complete Apple usbmux implementation.
 */
class AppleUsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
    val isLikelyUsbmux: Boolean
) : Closeable {

    data class ProbeResult(
        val interfaceNumber: Int,
        val alternateSetting: Int,
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
        alternateSetting = usbInterface.alternateSetting,
        interfaceClass = usbInterface.interfaceClass,
        interfaceSubclass = usbInterface.interfaceSubclass,
        interfaceProtocol = usbInterface.interfaceProtocol,
        inputEndpoint = input.address,
        outputEndpoint = output.address,
        inputMaxPacket = input.maxPacketSize,
        outputMaxPacket = output.maxPacketSize,
        isLikelyUsbmux = isLikelyUsbmux
    )

    companion object {

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

        private fun matchesUsbmux(intf: UsbInterface): Boolean {
            return intf.interfaceClass == APPLE_USB_CLASS &&
                intf.interfaceSubclass == APPLE_USB_SUBCLASS &&
                intf.interfaceProtocol == APPLE_USB_PROTOCOL
        }

        /**
         * Enumerate all USB interfaces and their bulk endpoints.
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
                        isLikelyUsbmux = matchesUsbmux(intf)
                    )
                )
            }

            return result
        }

        /**
         * Find interfaces containing both bulk IN and bulk OUT endpoints.
         * Likely usbmux interfaces are listed first.
         */
        fun candidates(device: UsbDevice): List<Candidate> {
            val result = mutableListOf<Candidate>()

            for (index in 0 until device.interfaceCount) {
                val intf = device.getInterface(index)

                var input: UsbEndpoint? = null
                var output: UsbEndpoint? = null

                for (endpointIndex in 0 until intf.endpointCount) {
                    val endpoint = intf.getEndpoint(endpointIndex)

                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        continue
                    }

                    when (endpoint.direction) {
                        UsbConstants.USB_DIR_IN -> {
                            if (input == null) input = endpoint
                        }

                        UsbConstants.USB_DIR_OUT -> {
                            if (output == null) output = endpoint
                        }
                    }
                }

                if (input != null && output != null) {
                    result.add(
                        Candidate(
                            usbInterface = intf,
                            input = input,
                            output = output,
                            isLikelyUsbmux = matchesUsbmux(intf)
                        )
                    )
                }
            }

            return result.sortedByDescending {
                it.isLikelyUsbmux
            }
        }

        /**
         * Opens a candidate USB interface.
         *
         * Unverified interfaces are rejected unless diagnostic mode
         * is explicitly enabled.
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
                    "No USB interface with both bulk IN and bulk OUT endpoints found"
                )

            check(candidate.isLikelyUsbmux || allowUnverifiedInterface) {
                "Selected interface does not match the expected Apple usbmux " +
                    "descriptor FF/FE/02. Diagnostic mode is required."
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
                connection = connection,
                usbInterface = candidate.usbInterface,
                input = candidate.input,
                output = candidate.output,
                isLikelyUsbmux = candidate.isLikelyUsbmux
            )
        }
    }

    @Synchronized
    fun write(
        data: ByteArray,
        timeoutMs: Int = 3000
    ): Int {
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
}
