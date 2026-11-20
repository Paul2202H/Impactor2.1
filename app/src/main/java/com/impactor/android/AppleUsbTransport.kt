
package com.impactor.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable

/**
 * Apple USB diagnostic transport.
 *
 * Supports:
 * - USB configuration inspection
 * - Interface discovery across all configurations
 * - Strict configuration 3 usbmux selection
 * - Explicit configuration activation
 * - Interface claim diagnostics
 * - Bulk endpoint read/write
 *
 * Not yet implemented:
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
    val configurationId: Int,
    val isLikelyUsbmux: Boolean
) : Closeable {

    companion object {
        private const val APPLE_USB_CLASS = 0xFF
        private const val APPLE_USB_SUBCLASS = 0xFE
        private const val APPLE_USB_PROTOCOL = 0x02

        private const val TARGET_CONFIGURATION_ID = 3
        private const val EXPECTED_INPUT_ENDPOINT = 0x85
        private const val EXPECTED_OUTPUT_ENDPOINT = 0x04

        private const val MAX_TRANSFER_SIZE = 16 * 1024
        private const val MAX_RAW_DESCRIPTOR_BYTES = 1024

        data class InterfaceInfo(
            val configurationId: Int,
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
            val configurationId: Int,
            val usbInterface: UsbInterface,
            val input: UsbEndpoint,
            val output: UsbEndpoint,
            val isLikelyUsbmux: Boolean
        )

        private fun hex(value: Int): String =
            "0x%02X".format(value and 0xFF)

        private fun matchesUsbmux(intf: UsbInterface): Boolean {
            return intf.interfaceClass == APPLE_USB_CLASS &&
                intf.interfaceSubclass == APPLE_USB_SUBCLASS &&
                intf.interfaceProtocol == APPLE_USB_PROTOCOL
        }

        private fun endpointCandidates(
            configurationId: Int,
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
                        configurationId = configurationId,
                        usbInterface = intf,
                        input = input,
                        output = output,
                        isLikelyUsbmux = matchesUsbmux(intf)
                    )
                }
            }
        }

        fun inspectInterfaces(device: UsbDevice): List<InterfaceInfo> {
            val result = mutableListOf<InterfaceInfo>()

            for (configIndex in 0 until device.configurationCount) {
                val config = device.getConfiguration(configIndex)

                for (interfaceIndex in 0 until config.interfaceCount) {
                    val intf = config.getInterface(interfaceIndex)

                    val bulkIn = mutableListOf<Int>()
                    val bulkOut = mutableListOf<Int>()

                    for (endpointIndex in 0 until intf.endpointCount) {
                        val endpoint = intf.getEndpoint(endpointIndex)

                        if (endpoint.type !=
                            UsbConstants.USB_ENDPOINT_XFER_BULK
                        ) {
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
                            configurationId = config.id,
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
            }

            return result
        }

        fun candidates(device: UsbDevice): List<Candidate> {
            val result = mutableListOf<Candidate>()

            for (configIndex in 0 until device.configurationCount) {
                val config = device.getConfiguration(configIndex)

                for (interfaceIndex in 0 until config.interfaceCount) {
                    val intf = config.getInterface(interfaceIndex)

                    result.addAll(
                        endpointCandidates(config.id, intf)
                    )
                }
            }

            return result
                .distinctBy {
                    listOf(
                        it.configurationId,
                        it.usbInterface.id,
                        it.usbInterface.alternateSetting,
                        it.input.address,
                        it.output.address
                    )
                }
                .sortedWith(
                    compareByDescending<Candidate> {
                        it.isLikelyUsbmux
                    }.thenBy {
                        it.configurationId
                    }
                )
        }

        private fun appendInterfaceReport(
            report: StringBuilder,
            configId: Int,
            intf: UsbInterface
        ) {
            report.appendLine("Configuration: $configId")
            report.appendLine("Interface: ${intf.id}")
            report.appendLine("Alternate: ${intf.alternateSetting}")

            report.appendLine(
                "Class/Subclass/Protocol: " +
                    "${hex(intf.interfaceClass)}/" +
                    "${hex(intf.interfaceSubclass)}/" +
                    "${hex(intf.interfaceProtocol)}"
            )

            report.appendLine("Endpoint count: ${intf.endpointCount}")
            report.appendLine("Likely usbmux: ${matchesUsbmux(intf)}")

            for (index in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(index)

                val direction = if (
                    endpoint.direction == UsbConstants.USB_DIR_IN
                ) "IN" else "OUT"

                val type = when (endpoint.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                    else -> "UNKNOWN"
                }

                report.appendLine(
                    "  Endpoint: ${hex(endpoint.address)} " +
                        "$direction $type " +
                        "packet=${endpoint.maxPacketSize}"
                )
            }
        }

        fun inspectDevice(
            manager: UsbManager,
            device: UsbDevice
        ): String {
            check(manager.hasPermission(device)) {
                "USB permission is missing."
            }

            val report = StringBuilder()

            report.appendLine("=== APPLE USB DIAGNOSTICS ===")
            report.appendLine("Device: ${device.deviceName}")
            report.appendLine("VID: ${hex(device.vendorId)}")
            report.appendLine("PID: ${hex(device.productId)}")
            report.appendLine(
                "Configuration count: ${device.configurationCount}"
            )

            for (index in 0 until device.configurationCount) {
                val config = device.getConfiguration(index)

                report.appendLine()
                report.appendLine("=== CONFIGURATION ${config.id} ===")
                report.appendLine("Interface count: ${config.interfaceCount}")
                report.appendLine("Max power: ${config.maxPower}")

                for (interfaceIndex in 0 until config.interfaceCount) {
                    report.appendLine()
                    appendInterfaceReport(
                        report,
                        config.id,
                        config.getInterface(interfaceIndex)
                    )
                }
            }

            report.appendLine()
            report.appendLine("=== USBMUX CANDIDATES ===")

            val matching = candidates(device).filter {
                it.isLikelyUsbmux
            }

            report.appendLine("Matching candidates: ${matching.size}")

            for (candidate in matching) {
                report.appendLine(
                    "Configuration ${candidate.configurationId}, " +
                        "interface ${candidate.usbInterface.id}"
                )
                report.appendLine(
                    "IN=${hex(candidate.input.address)}, " +
                        "OUT=${hex(candidate.output.address)}"
                )
            }

            report.appendLine()
            report.appendLine("=== USB CONNECTION ===")

            val connection = manager.openDevice(device)

            if (connection == null) {
                report.appendLine("openDevice(): FAILED")
                return report.toString()
            }

            try {
                report.appendLine("openDevice(): SUCCESS")
                report.appendLine(
                    "File descriptor: ${connection.fileDescriptor}"
                )

                val raw = connection.rawDescriptors

                report.appendLine("Raw descriptor length: ${raw.size}")
                report.appendLine(
                    raw.take(MAX_RAW_DESCRIPTOR_BYTES)
                        .joinToString(" ") { hex(it.toInt()) }
                )
            } catch (error: Exception) {
                report.appendLine("Diagnostic error: ${error.message}")
            } finally {
                connection.close()
            }

            report.appendLine()
            report.appendLine("Inspection did not claim an interface.")
            return report.toString()
        }

        fun openUsbmux(
            manager: UsbManager,
            device: UsbDevice
        ): AppleUsbTransport {
            check(manager.hasPermission(device)) {
                "USB permission is missing."
            }

            check(device.vendorId == 0x05AC) {
                "Selected device is not an Apple USB device."
            }

            val allCandidates = candidates(device)

            val matching = allCandidates.filter { candidate ->
                candidate.configurationId == TARGET_CONFIGURATION_ID &&
                    candidate.isLikelyUsbmux &&
                    candidate.usbInterface.id == 1 &&
                    candidate.usbInterface.alternateSetting == 0 &&
                    candidate.input.address == EXPECTED_INPUT_ENDPOINT &&
                    candidate.output.address == EXPECTED_OUTPUT_ENDPOINT
            }

            check(matching.isNotEmpty()) {
                buildString {
                    appendLine("The expected usbmux interface was not found.")
                    appendLine(
                        "Expected configuration: $TARGET_CONFIGURATION_ID"
                    )
                    appendLine("Expected interface: 1")
                    appendLine("Expected descriptor: FF/FE/02")
                    appendLine(
                        "Expected bulk IN: ${hex(EXPECTED_INPUT_ENDPOINT)}"
                    )
                    appendLine(
                        "Expected bulk OUT: ${hex(EXPECTED_OUTPUT_ENDPOINT)}"
                    )
                    appendLine()
                    appendLine("Candidates discovered:")

                    for (candidate in allCandidates) {
                        appendLine(
                            "Config=${candidate.configurationId}, " +
                                "Interface=${candidate.usbInterface.id}, " +
                                "Descriptor=" +
                                "${hex(candidate.usbInterface.interfaceClass)}/" +
                                "${hex(candidate.usbInterface.interfaceSubclass)}/" +
                                "${hex(candidate.usbInterface.interfaceProtocol)}, " +
                                "IN=${hex(candidate.input.address)}, " +
                                "OUT=${hex(candidate.output.address)}"
                        )
                    }
                }
            }

            val errors = mutableListOf<String>()

            for (candidate in matching) {
                try {
                    return openCandidate(manager, device, candidate)
                } catch (error: Exception) {
                    errors.add(error.message ?: error.toString())
                }
            }

            error(
                "The expected usbmux interface was detected, " +
                    "but opening or claiming it failed.\n\n" +
                    errors.joinToString("\n\n")
            )
        }

        fun open(
            manager: UsbManager,
            device: UsbDevice,
            candidateIndex: Int = 0,
            allowUnverifiedInterface: Boolean = false
        ): AppleUsbTransport {
            check(manager.hasPermission(device)) {
                "USB permission is missing."
            }

            val candidate = candidates(device).getOrNull(candidateIndex)
                ?: error("No bulk IN/OUT candidate found.")

            check(candidate.isLikelyUsbmux || allowUnverifiedInterface) {
                "Selected interface is not FF/FE/02."
            }

            return openCandidate(manager, device, candidate)
        }

        private fun openCandidate(
            manager: UsbManager,
            device: UsbDevice,
            candidate: Candidate
        ): AppleUsbTransport {
            check(manager.hasPermission(device)) {
                "USB permission is missing."
            }

            val connection = manager.openDevice(device)
                ?: error("Android openDevice() failed.")

            try {
                val intf = candidate.usbInterface

                val configuration = (0 until device.configurationCount)
                    .map { device.getConfiguration(it) }
                    .firstOrNull { it.id == candidate.configurationId }
                    ?: error(
                        "Configuration ${candidate.configurationId} not found."
                    )

                val diagnostic = StringBuilder()

                diagnostic.appendLine("=== USBMUX CLAIM TEST ===")
                diagnostic.appendLine("openDevice(): SUCCESS")
                diagnostic.appendLine(
                    "Target configuration: ${candidate.configurationId}"
                )

                diagnostic.appendLine()
                diagnostic.appendLine("Activating USB configuration...")

                val configured = connection.setConfiguration(configuration)

                diagnostic.appendLine(
                    "setConfiguration(${configuration.id}): $configured"
                )

                check(configured) {
                    diagnostic.appendLine(
                        "Configuration activation failed."
                    )
                    diagnostic.toString()
                }

                diagnostic.appendLine(
                    "Configuration activated successfully."
                )
                diagnostic.appendLine("Interface: ${intf.id}")
                diagnostic.appendLine(
                    "Alternate setting: ${intf.alternateSetting}"
                )

                diagnostic.appendLine(
                    "Class/Subclass/Protocol: " +
                        "${hex(intf.interfaceClass)}/" +
                        "${hex(intf.interfaceSubclass)}/" +
                        "${hex(intf.interfaceProtocol)}"
                )

                diagnostic.appendLine(
                    "Bulk IN: ${hex(candidate.input.address)}"
                )
                diagnostic.appendLine(
                    "Bulk OUT: ${hex(candidate.output.address)}"
                )

                diagnostic.appendLine()
                diagnostic.appendLine(
                    "Attempting Android claimInterface()..."
                )

                val claimed = connection.claimInterface(intf, true)

                if (!claimed) {
                    diagnostic.appendLine("claimInterface(): FAILED")
                    diagnostic.appendLine("Android returned false.")

                    diagnostic.appendLine()
                    diagnostic.appendLine("=== NATIVE USB DIAGNOSTICS ===")

                    val nativeResult = try {
                        NativeUsbDiagnostics.probeClaim(
                            connection.fileDescriptor,
                            intf.id
                        )
                    } catch (error: Exception) {
                        "Native exception: " +
                            "${error.javaClass.simpleName}: " +
                            "${error.message}"
                    } catch (error: LinkageError) {
                        "Native library unavailable: ${error.message}"
                    }

                    diagnostic.appendLine(nativeResult)
                    diagnostic.appendLine()
                    diagnostic.appendLine(
                        "No fallback interface was attempted."
                    )

                    error(diagnostic.toString())
                }

                diagnostic.appendLine("claimInterface(): SUCCESS")

                return AppleUsbTransport(
                    connection = connection,
                    usbInterface = intf,
                    input = candidate.input,
                    output = candidate.output,
                    configurationId = candidate.configurationId,
                    isLikelyUsbmux = candidate.isLikelyUsbmux
                )
            } catch (error: Exception) {
                connection.close()
                throw error
            }
        }
    }

    data class ProbeResult(
        val configurationId: Int,
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
        configurationId = configurationId,
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

    @Synchronized
    fun write(
        data: ByteArray,
        timeoutMs: Int = 3000
    ): Int {
        check(data.isNotEmpty()) {
            "USB write buffer is empty."
        }

        require(data.size <= MAX_TRANSFER_SIZE) {
            "USB write exceeds $MAX_TRANSFER_SIZE bytes."
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
        maxBytes: Int = MAX_TRANSFER_SIZE,
        timeoutMs: Int = 3000
    ): ByteArray? {
        require(maxBytes in 1..MAX_TRANSFER_SIZE) {
            "Read size must be between 1 and $MAX_TRANSFER_SIZE."
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
