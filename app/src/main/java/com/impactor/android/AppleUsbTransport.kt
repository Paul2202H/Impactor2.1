
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
 * Current capabilities:
 * - USB device inspection
 * - USB configuration and interface reporting
 * - Raw descriptor inspection
 * - Apple usbmux-like interface detection
 * - USB interface claim diagnostics
 * - Bulk endpoint read/write
 *
 * Not implemented yet:
 * - usbmux protocol
 * - Lockdown protocol
 * - Apple pairing
 * - Apple ID / Anisette
 * - Signing and provisioning
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

        private fun hex(value: Int): String {
            return "0x%02X".format(value and 0xFF)
        }

        private fun matchesUsbmux(
            intf: UsbInterface
        ): Boolean {
            return intf.interfaceClass == APPLE_USB_CLASS &&
                intf.interfaceSubclass == APPLE_USB_SUBCLASS &&
                intf.interfaceProtocol == APPLE_USB_PROTOCOL
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

        private fun appendInterfaceReport(
            report: StringBuilder,
            intf: UsbInterface,
            indent: String = ""
        ) {

            report.appendLine("${indent}Interface number: ${intf.id}")
            report.appendLine(
                "${indent}Alternate setting: ${intf.alternateSetting}"
            )
            report.appendLine(
                "${indent}Class/Subclass/Protocol: " +
                    "${hex(intf.interfaceClass)}/" +
                    "${hex(intf.interfaceSubclass)}/" +
                    "${hex(intf.interfaceProtocol)}"
            )
            report.appendLine(
                "${indent}Endpoint count: ${intf.endpointCount}"
            )
            report.appendLine(
                "${indent}Likely usbmux: ${matchesUsbmux(intf)}"
            )

            if (intf.endpointCount == 0) {
                report.appendLine("${indent}No endpoints.")
                return
            }

            for (index in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(index)

                val direction = when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> "IN"
                    UsbConstants.USB_DIR_OUT -> "OUT"
                    else -> "UNKNOWN"
                }

                val type = when (endpoint.type) {
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                    else -> "UNKNOWN"
                }

                report.appendLine(
                    "${indent}  Endpoint $index: " +
                        "${hex(endpoint.address)}"
                )
                report.appendLine(
                    "${indent}    Direction: $direction"
                )
                report.appendLine(
                    "${indent}    Type: $type"
                )
                report.appendLine(
                    "${indent}    Max packet size: ${endpoint.maxPacketSize}"
                )
            }
        }

        /**
         * Reports interfaces exposed through UsbDevice.
         * This is Android's exposed interface list, not a complete
         * enumeration of every possible USB alternate setting.
         */
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

        /**
         * Finds interfaces with bulk IN and bulk OUT endpoints.
         */
        fun candidates(
            device: UsbDevice
        ): List<Candidate> {

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
                        Pair(
                            it.input.address,
                            it.output.address
                        )
                    )
                }
                .sortedByDescending {
                    it.isLikelyUsbmux
                }
        }

        /**
         * Full USB diagnostic report.
         *
         * Does not change the device configuration or claim interfaces.
         */
        fun inspectDevice(
            manager: UsbManager,
            device: UsbDevice
        ): String {

            check(manager.hasPermission(device)) {
                "USB permission is missing."
            }

            val report = StringBuilder()

            report.appendLine("================================")
            report.appendLine("       USB DEVICE DIAGNOSTICS")
            report.appendLine("================================")

            report.appendLine("Device name: ${device.deviceName}")
            report.appendLine("Device ID: ${device.deviceId}")
            report.appendLine("Vendor ID: ${hex(device.vendorId)}")
            report.appendLine("Product ID: ${hex(device.productId)}")
            report.appendLine("Device class: ${hex(device.deviceClass)}")
            report.appendLine("Device subclass: ${hex(device.deviceSubclass)}")
            report.appendLine("Device protocol: ${hex(device.deviceProtocol)}")
            report.appendLine("Exposed interface count: ${device.interfaceCount}")
            report.appendLine("Configuration count: ${device.configurationCount}")

            // Configuration-level interface inspection.
            report.appendLine()
            report.appendLine("================================")
            report.appendLine("     CONFIGURATION DESCRIPTORS")
            report.appendLine("================================")

            if (device.configurationCount == 0) {
                report.appendLine("No configurations exposed.")
            }

            for (configIndex in 0 until device.configurationCount) {
                val config = device.getConfiguration(configIndex)

                report.appendLine()
                report.appendLine("Configuration index: $configIndex")
                report.appendLine("Configuration ID: ${config.id}")
                report.appendLine("Configuration name: ${config.name}")
                report.appendLine("Interface count: ${config.interfaceCount}")
                report.appendLine("Max power: ${config.maxPower}")
                report.appendLine("Self powered: ${config.isSelfPowered}")
                report.appendLine("Remote wakeup: ${config.isRemoteWakeup}")

                if (config.interfaceCount == 0) {
                    report.appendLine("No interfaces in this configuration.")
                }

                for (interfaceIndex in 0 until config.interfaceCount) {
                    val intf = config.getInterface(interfaceIndex)

                    report.appendLine()
                    report.appendLine(
                        "Configuration $configIndex, interface $interfaceIndex:"
                    )

                    appendInterfaceReport(
                        report = report,
                        intf = intf,
                        indent = "  "
                    )
                }
            }

            // Android's exposed UsbDevice interface list.
            report.appendLine()
            report.appendLine("================================")
            report.appendLine("    ANDROID EXPOSED INTERFACES")
            report.appendLine("================================")

            for (index in 0 until device.interfaceCount) {
                report.appendLine()
                report.appendLine("Exposed interface entry: $index")

                appendInterfaceReport(
                    report = report,
                    intf = device.getInterface(index)
                )
            }

            // Matching candidates.
            val matching = candidates(device)
                .filter { it.isLikelyUsbmux }

            report.appendLine()
            report.appendLine("================================")
            report.appendLine("       APPLE USBMUX ANALYSIS")
            report.appendLine("================================")
            report.appendLine("Expected descriptor: FF/FE/02")
            report.appendLine("Matching candidates: ${matching.size}")

            for ((index, candidate) in matching.withIndex()) {
                report.appendLine()
                report.appendLine("Candidate $index:")
                report.appendLine(
                    "Interface: ${candidate.usbInterface.id}"
                )
                report.appendLine(
                    "Alternate: ${candidate.usbInterface.alternateSetting}"
                )
                report.appendLine(
                    "Bulk IN: ${hex(candidate.input.address)}"
                )
                report.appendLine(
                    "Bulk OUT: ${hex(candidate.output.address)}"
                )
                report.appendLine(
                    "IN max packet: ${candidate.input.maxPacketSize}"
                )
                report.appendLine(
                    "OUT max packet: ${candidate.output.maxPacketSize}"
                )
            }

            report.appendLine()
            report.appendLine("================================")
            report.appendLine("       USB CONNECTION TEST")
            report.appendLine("================================")

            val connection = manager.openDevice(device)

            if (connection == null) {
                report.appendLine("openDevice(): FAILED")
                report.appendLine(
                    "Android could not open the USB device."
                )
                return report.toString()
            }

            try {
                report.appendLine("openDevice(): SUCCESS")
                report.appendLine(
                    "File descriptor: ${connection.fileDescriptor}"
                )

                val raw = connection.rawDescriptors

                report.appendLine()
                report.appendLine("================================")
                report.appendLine("        RAW DESCRIPTORS")
                report.appendLine("================================")

                if (raw.isEmpty()) {
                    report.appendLine("No raw descriptors returned.")
                } else {
                    report.appendLine("Raw descriptor length: ${raw.size}")

                    val limited = raw.take(MAX_RAW_DESCRIPTOR_BYTES)

                    report.appendLine(
                        limited.joinToString(" ") {
                            hex(it.toInt())
                        }
                    )

                    if (raw.size > MAX_RAW_DESCRIPTOR_BYTES) {
                        report.appendLine(
                            "Output truncated at $MAX_RAW_DESCRIPTOR_BYTES bytes."
                        )
                    }

                    report.appendLine()
                    report.appendLine("Parsed descriptor summary:")
                    report.appendLine(
                        parseRawDescriptors(limited.toByteArray())
                    )
                }

            } catch (error: Exception) {
                report.appendLine("Diagnostic exception:")
                report.appendLine(
                    "${error.javaClass.simpleName}: ${error.message}"
                )
            } finally {
                connection.close()
            }

            report.appendLine()
            report.appendLine("================================")
            report.appendLine("       DIAGNOSTIC COMPLETE")
            report.appendLine("================================")
            report.appendLine(
                "No USB configuration was changed."
            )
            report.appendLine(
                "No interface was claimed during this inspection."
            )

            return report.toString()
        }

        /**
         * Parses standard configuration, interface and endpoint
         * descriptors from the raw descriptor byte array.
         */
        private fun parseRawDescriptors(
            data: ByteArray
        ): String {

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
                            val totalLength =
                                (data[offset + 2].toInt() and 0xFF) or
                                    ((data[offset + 3].toInt() and 0xFF) shl 8)

                            val interfaceCount =
                                data[offset + 4].toInt() and 0xFF

                            val configurationValue =
                                data[offset + 5].toInt() and 0xFF

                            report.appendLine("Configuration descriptor:")
                            report.appendLine("  Offset: $offset")
                            report.appendLine("  Total length: $totalLength")
                            report.appendLine(
                                "  Interface count: $interfaceCount"
                            )
                            report.appendLine(
                                "  Configuration value: $configurationValue"
                            )
                        }
                    }

                    0x04 -> {
                        if (length >= 9) {
                            val number =
                                data[offset + 2].toInt() and 0xFF

                            val alternate =
                                data[offset + 3].toInt() and 0xFF

                            val endpointCount =
                                data[offset + 4].toInt() and 0xFF

                            val interfaceClass =
                                data[offset + 5].toInt() and 0xFF

                            val subclass =
                                data[offset + 6].toInt() and 0xFF

                            val protocol =
                                data[offset + 7].toInt() and 0xFF

                            report.appendLine("Interface descriptor:")
                            report.appendLine("  Number: $number")
                            report.appendLine("  Alternate: $alternate")
                            report.appendLine(
                                "  Endpoint count: $endpointCount"
                            )
                            report.appendLine(
                                "  Class/Subclass/Protocol: " +
                                    "${hex(interfaceClass)}/" +
                                    "${hex(subclass)}/" +
                                    "${hex(protocol)}"
                            )
                        }
                    }

                    0x05 -> {
                        if (length >= 7) {
                            val address =
                                data[offset + 2].toInt() and 0xFF

                            val attributes =
                                data[offset + 3].toInt() and 0xFF

                            val maxPacket =
                                (data[offset + 4].toInt() and 0xFF) or
                                    ((data[offset + 5].toInt() and 0xFF) shl 8)

                            val endpointType = when (attributes and 0x03) {
                                0 -> "CONTROL"
                                1 -> "ISOCHRONOUS"
                                2 -> "BULK"
                                3 -> "INTERRUPT"
                                else -> "UNKNOWN"
                            }

                            report.appendLine("Endpoint descriptor:")
                            report.appendLine("  Address: ${hex(address)}")
                            report.appendLine("  Type: $endpointType")
                            report.appendLine(
                                "  Max packet size: $maxPacket"
                            )
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
         * No generic-interface fallback is permitted.
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
                "No Apple usbmux-like interface with bulk IN and bulk OUT " +
                    "endpoints was found. Expected FF/FE/02."
            }

            val errors = mutableListOf<String>()

            for ((index, candidate) in matchingCandidates.withIndex()) {
                try {
                    return openCandidate(
                        manager,
                        device,
                        candidate
                    )
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
         * Generic diagnostic opening.
         *
         * This must not be used as a production Apple usbmux connection
         * unless the selected interface has been independently verified.
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
                "Selected interface does not match FF/FE/02."
            }

            return openCandidate(
                manager,
                device,
                candidate
            )
        }

        /**
         * Opens the USB device and tries Android's interface claim.
         *
         * Native ioctl probing is diagnostic only. Its result must not
         * be interpreted as proof of the underlying USB driver state.
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
                    "Bulk IN: ${hex(candidate.input.address)}"
                )
                diagnostic.appendLine(
                    "Bulk OUT: ${hex(candidate.output.address)}"
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

                val claimed = connection.claimInterface(
                    candidate.usbInterface,
                    true
                )

                if (!claimed) {
                    diagnostic.appendLine()
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

                    diagnostic.appendLine(
                        "Native ioctl results are diagnostic only."
                    )

                    throw IllegalStateException(diagnostic.toString())
                }

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
