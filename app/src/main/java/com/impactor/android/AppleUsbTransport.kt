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
 * Does NOT yet implement:
 * - usbmux protocol
 * - Lockdown protocol
 * - Apple pairing
 * - Signing or provisioning
 * - IPA installation
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

        private const val MAX_COMPAT_TRANSFER_SIZE = 16 * 1024
        private const val MAX_RAW_DESCRIPTOR_BYTES = 1024

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

        private fun hex(value: Int): String {
            return "0x%02X".format(value and 0xFF)
        }

        private fun endpointCandidates(
            intf: UsbInterface
        ): List<Candidate> {

            val inputs = mutableListOf<UsbEndpoint>()
            val outputs = mutableListOf<UsbEndpoint>()

            for (index in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(index)

                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue
                }

                when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> inputs.add(endpoint)
                    UsbConstants.USB_DIR_OUT -> outputs.add(endpoint)
                }
            }

            if (inputs.isEmpty() || outputs.isEmpty()) {
                return emptyList()
            }

            return inputs.flatMap { input ->
                outputs.map { output ->
                    Candidate(
                        usbInterface = intf,
                        input = input,
                        output = output,
                        isLikelyUsbmux = matchesUsbmux(intf)
                    )
                }
            }
        }

        fun inspectInterfaces(
            device: UsbDevice
        ): List<InterfaceInfo> {

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

        fun candidates(device: UsbDevice): List<Candidate> {

            val result = mutableListOf<Candidate>()

            for (index in 0 until device.interfaceCount) {
                result.addAll(
                    endpointCandidates(device.getInterface(index))
                )
            }

            return result
                .distinctBy {
                    Triple(
                        it.usbInterface.id,
                        it.usbInterface.alternateSetting,
                        Pair(it.input.address, it.output.address)
                    )
                }
                .sortedByDescending { it.isLikelyUsbmux }
        }

        fun inspectDevice(
            manager: UsbManager,
            device: UsbDevice
        ): String {

            check(manager.hasPermission(device)) {
                "USB permission is missing."
            }

            val report = StringBuilder()

            report.appendLine("=== USB DEVICE DIAGNOSTICS ===")
            report.appendLine("Device name: ${device.deviceName}")
            report.appendLine("Device ID: ${device.deviceId}")
            report.appendLine("Vendor ID: ${hex(device.vendorId)}")
            report.appendLine("Product ID: ${hex(device.productId)}")
            report.appendLine("Device class: ${hex(device.deviceClass)}")
            report.appendLine("Device subclass: ${hex(device.deviceSubclass)}")
            report.appendLine("Device protocol: ${hex(device.deviceProtocol)}")
            report.appendLine("Interface count: ${device.interfaceCount}")
            report.appendLine("Configuration count: ${device.configurationCount}")

            report.appendLine()
            report.appendLine("=== USB CONFIGURATIONS ===")

            for (index in 0 until device.configurationCount) {
                val config = device.getConfiguration(index)

                report.appendLine("Configuration $index:")
                report.appendLine("  ID: ${config.id}")
                report.appendLine("  Name: ${config.name}")
                report.appendLine("  Interfaces: ${config.interfaceCount}")
                report.appendLine("  Max power: ${config.maxPower}")
                report.appendLine("  Self powered: ${config.isSelfPowered}")
                report.appendLine("  Remote wakeup: ${config.isRemoteWakeup}")
            }

            report.appendLine()
            report.appendLine("=== INTERFACES ===")

            for (info in inspectInterfaces(device)) {
                report.appendLine("Interface ${info.interfaceNumber}:")
                report.appendLine("  Alternate setting: ${info.alternateSetting}")
                report.appendLine("  Class: ${hex(info.interfaceClass)}")
                report.appendLine("  Subclass: ${hex(info.interfaceSubclass)}")
                report.appendLine("  Protocol: ${hex(info.interfaceProtocol)}")
                report.appendLine("  Endpoints: ${info.endpointCount}")
                report.appendLine(
                    "  Bulk IN: ${info.bulkInEndpoints.map { hex(it) }}"
                )
                report.appendLine(
                    "  Bulk OUT: ${info.bulkOutEndpoints.map { hex(it) }}"
                )
                report.appendLine("  Likely usbmux: ${info.isLikelyUsbmux}")
            }

            report.appendLine()
            report.appendLine("=== USB CONNECTION ===")

            val connection = manager.openDevice(device)

            if (connection == null) {
                report.appendLine("openDevice(): FAILED")
                report.appendLine("Android could not open the USB device.")
                return report.toString()
            }

            try {
                report.appendLine("openDevice(): SUCCESS")
                report.appendLine("File descriptor: ${connection.fileDescriptor}")

                val raw = connection.rawDescriptors

                report.appendLine()
                report.appendLine("=== RAW USB DESCRIPTORS ===")

                if (raw.isEmpty()) {
                    report.appendLine("No raw descriptors returned.")
                } else {
                    report.appendLine("Raw descriptor length: ${raw.size}")

                    val limited = raw.take(MAX_RAW_DESCRIPTOR_BYTES)

                    report.appendLine(
                        limited.joinToString(" ") { hex(it.toInt()) }
                    )

                    if (raw.size > MAX_RAW_DESCRIPTOR_BYTES) {
                        report.appendLine("Descriptor output truncated.")
                    }

                    report.appendLine()
                    report.appendLine("=== PARSED DESCRIPTORS ===")
                    report.appendLine(
                        parseRawDescriptors(limited.toByteArray())
                    )
                }
            } catch (error: Exception) {
                report.appendLine(
                    "Diagnostic exception: ${error.javaClass.simpleName}"
                )
                report.appendLine("Message: ${error.message}")
            } finally {
                connection.close()
            }

            return report.toString()
        }

        private fun parseRawDescriptors(data: ByteArray): String {

            val report = StringBuilder()
            var offset = 0

            while (offset + 1 < data.size) {
                val length = data[offset].toInt() and 0xFF
                val type = data[offset + 1].toInt() and 0xFF

                if (length < 2 || offset + length > data.size) {
                    report.appendLine(
                        "Invalid or incomplete descriptor at offset $offset"
                    )
                    break
                }

                when (type) {
                    0x02 -> {
                        if (length >= 9) {
                            report.appendLine(
                                "Configuration descriptor at $offset"
                            )
                            report.appendLine(
                                "  Total length: ${data[offset + 2].toInt() and 0xFF}"
                            )
                            report.appendLine(
                                "  Interfaces: ${data[offset + 4].toInt() and 0xFF}"
                            )
                        }
                    }

                    0x04 -> {
                        if (length >= 9) {
                            val number = data[offset + 2].toInt() and 0xFF
                            val alternate = data[offset + 3].toInt() and 0xFF
                            val interfaceClass = data[offset + 5].toInt() and 0xFF
                            val subclass = data[offset + 6].toInt() and 0xFF
                            val protocol = data[offset + 7].toInt() and 0xFF

                            report.appendLine("Interface descriptor:")
                            report.appendLine("  Number: $number")
                            report.appendLine("  Alternate: $alternate")
                            report.appendLine("  Class: ${hex(interfaceClass)}")
                            report.appendLine("  Subclass: ${hex(subclass)}")
                            report.appendLine("  Protocol: ${hex(protocol)}")
                        }
                    }

                    0x05 -> {
                        if (length >= 7) {
                            val address = data[offset + 2].toInt() and 0xFF
                            val attributes = data[offset + 3].toInt() and 0xFF
                            val maxPacket =
                                (data[offset + 4].toInt() and 0xFF) or
                                    ((data[offset + 5].toInt() and 0xFF) shl 8)

                            report.appendLine("Endpoint descriptor:")
                            report.appendLine("  Address: ${hex(address)}")
                            report.appendLine("  Attributes: ${hex(attributes)}")
                            report.appendLine("  Max packet: $maxPacket")
                        }
                    }
                }

                offset += length
            }

            if (report.isEmpty()) {
                report.appendLine(
                    "No complete configuration, interface, or endpoint descriptors parsed."
                )
            }

            return report.toString()
        }

        /**
         * Strict Apple usbmux interface opening.
         * Never falls back to a generic USB interface.
         */
        fun openUsbmux(
            manager: UsbManager,
            device: UsbDevice
        ): AppleUsbTransport {

            check(manager.hasPermission(device)) {
                "USB permission is missing. Request permission and try again."
            }

            val matchingCandidates = candidates(device)
                .filter { it.isLikelyUsbmux }

            check(matchingCandidates.isNotEmpty()) {
                "No Apple usbmux-like interface with bulk IN and OUT endpoints found. " +
                    "Expected descriptor FF/FE/02."
            }

            val errors = mutableListOf<String>()

            for ((index, candidate) in matchingCandidates.withIndex()) {
                try {
                    return openCandidate(manager, device, candidate)
                } catch (error: Exception) {
                    errors.add(
                        "Candidate $index, interface " +
                            "${candidate.usbInterface.id}, alternate " +
                            "${candidate.usbInterface.alternateSetting}:\n" +
                            (error.message ?: "Unknown error")
                    )
                }
            }

            error(
                "Apple usbmux-like interface was detected, but Android " +
                    "could not claim it.\n\n" +
                    errors.joinToString("\n\n") +
                    "\n\nNo generic interface fallback was attempted."
            )
        }

        /**
         * Generic diagnostic open.
         * Generic interfaces must never be treated as Apple usbmux.
         */
        fun open(
            manager: UsbManager,
            device: UsbDevice,
            candidateIndex: Int = 0,
            allowUnverifiedInterface: Boolean = false
        ): AppleUsbTransport {

            check(manager.hasPermission(device)) {
                "Android USB permission has not been granted."
            }

            val candidate = candidates(device).getOrNull(candidateIndex)
                ?: error(
                    "No USB interface with bulk IN and bulk OUT endpoints found."
                )

            check(candidate.isLikelyUsbmux || allowUnverifiedInterface) {
                "Selected interface does not match Apple usbmux-like " +
                    "descriptor FF/FE/02."
            }

            return openCandidate(manager, device, candidate)
        }

        /**
         * Open the device and try Android's claim first.
         *
         * If Android's claim fails, run the native ioctl diagnostic
         * BEFORE closing the original USB connection.
         */
        private fun openCandidate(
            manager: UsbManager,
            device: UsbDevice,
            candidate: Candidate
        ): AppleUsbTransport {

            check(manager.hasPermission(device)) {
                "USB permission is missing."
            }

            val connection = manager.openDevice(device)
                ?: error(
                    "openDevice() failed. Android could not open the USB device."
                )

            try {
                val diagnostic = StringBuilder()

                diagnostic.appendLine("=== CLAIM DIAGNOSTICS ===")
                diagnostic.appendLine("openDevice(): SUCCESS")
                diagnostic.appendLine(
                    "File descriptor: ${connection.fileDescriptor}"
                )
                diagnostic.appendLine(
                    "Interface: ${candidate.usbInterface.id}"
                )
                diagnostic.appendLine(
                    "Alternate setting: ${candidate.usbInterface.alternateSetting}"
                )
                diagnostic.appendLine(
                    "Descriptor: " +
                        "${hex(candidate.usbInterface.interfaceClass)}/" +
                        "${hex(candidate.usbInterface.interfaceSubclass)}/" +
                        "${hex(candidate.usbInterface.interfaceProtocol)}"
                )
                diagnostic.appendLine(
                    "Bulk IN endpoint: ${hex(candidate.input.address)}"
                )
                diagnostic.appendLine(
                    "Bulk OUT endpoint: ${hex(candidate.output.address)}"
                )

                val rawDescriptors = try {
                    connection.rawDescriptors
                } catch (error: Exception) {
                    diagnostic.appendLine(
                        "Raw descriptor access failed: ${error.message}"
                    )
                    null
                }

                if (rawDescriptors != null) {
                    diagnostic.appendLine(
                        "Raw descriptor bytes: ${rawDescriptors.size}"
                    )
                }

                val claimed = try {
                    connection.claimInterface(
                        candidate.usbInterface,
                        true
                    )
                } catch (error: Exception) {
                    diagnostic.appendLine(
                        "claimInterface(): EXCEPTION"
                    )
                    diagnostic.appendLine(
                        "Exception: ${error.javaClass.simpleName}"
                    )
                    diagnostic.appendLine("Message: ${error.message}")

                    throw IllegalStateException(diagnostic.toString(), error)
                }

                diagnostic.appendLine()

                if (!claimed) {
                    diagnostic.appendLine("claimInterface(): FAILED")
                    diagnostic.appendLine(
                        "Android returned false when claiming the interface."
                    )

                    diagnostic.appendLine()
                    diagnostic.appendLine("=== NATIVE USB DIAGNOSTIC ===")

                    val nativeResult = try {
                        NativeUsbDiagnostics.probeClaim(
                            connection.fileDescriptor,
                            candidate.usbInterface.id
                        )
                    } catch (error: Exception) {
                        "Native diagnostic exception: " +
                            "${error.javaClass.simpleName}: ${error.message}"
                    } catch (error: LinkageError) {
                        "Native library unavailable: " +
                            "${error.javaClass.simpleName}: ${error.message}"
                    }

                    diagnostic.appendLine(nativeResult)

                    if (rawDescriptors != null) {
                        diagnostic.appendLine()
                        diagnostic.appendLine("=== RAW DESCRIPTOR PREVIEW ===")

                        diagnostic.appendLine(
                            rawDescriptors
                                .take(MAX_RAW_DESCRIPTOR_BYTES)
                                .joinToString(" ") {
                                    hex(it.toInt())
                                }
                        )
                    }

                    diagnostic.appendLine()
                    diagnostic.appendLine(
                        "No generic interface fallback was attempted."
                    )
                    diagnostic.appendLine(
                        "No fallback to interface 0 was attempted."
                    )

                    throw IllegalStateException(diagnostic.toString())
                }

                diagnostic.appendLine("claimInterface(): SUCCESS")

                return AppleUsbTransport(
                    connection = connection,
                    usbInterface = candidate.usbInterface,
                    input = candidate.input,
                    output = candidate.output,
                    isLikelyUsbmux = candidate.isLikelyUsbmux
                )

            } catch (error: Exception) {
                connection.close()
                throw error
            }
        }
    }

    @Synchronized
    fun write(
        data: ByteArray,
        timeoutMs: Int = 3000
    ): Int {

        check(data.isNotEmpty()) {
            "USB write buffer is empty."
        }

        check(data.size <= MAX_COMPAT_TRANSFER_SIZE) {
            "USB write exceeds the compatibility limit of " +
                "$MAX_COMPAT_TRANSFER_SIZE bytes."
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
        maxBytes: Int = MAX_COMPAT_TRANSFER_SIZE,
        timeoutMs: Int = 3000
    ): ByteArray? {

        require(maxBytes in 1..MAX_COMPAT_TRANSFER_SIZE) {
            "Read size must be between 1 and " +
                "$MAX_COMPAT_TRANSFER_SIZE bytes."
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

    @Synchronized
    override fun close() {
        runCatching {
            connection.releaseInterface(usbInterface)
        }

        runCatching {
            connection.close()
        }
    }
}
