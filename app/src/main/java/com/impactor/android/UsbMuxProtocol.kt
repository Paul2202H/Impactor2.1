
package com.impactor.android

import java.io.ByteArrayOutputStream

object UsbMuxProtocol {

    private const val VERSION_PROTOCOL = 0
    private const val SETUP_PROTOCOL = 1
    private const val VERSION_PACKET_SIZE = 20
    private const val BASIC_HEADER_SIZE = 8
    private const val MAX_PACKET_SIZE = 4096

    data class VersionResult(
        val success: Boolean,
        val message: String,
        val majorVersion: Int? = null,
        val minorVersion: Int? = null,
        val rawHex: String = ""
    )

    @Synchronized
    fun performVersionHandshake(
        transport: AppleUsbTransport,
        timeoutMs: Int = 5000
    ): VersionResult {

        // Initial USBMUX version request: protocol 0, length 20,
        // requested version 2.0, padding 0.
        val request = byteArrayOf(
            0, 0, 0, 0,
            0, 0, 0, 20,
            0, 0, 0, 2,
            0, 0, 0, 0,
            0, 0, 0, 0
        )

        val written = try {
            transport.write(request, timeoutMs)
        } catch (e: Exception) {
            return failure("Version request write failed: ${e.message}")
        }

        if (written != request.size) {
            return failure(
                "Incomplete version request: $written/${request.size} bytes"
            )
        }

        val response = readPacket(transport, timeoutMs)
            ?: return failure("No USBMUX version response received.")

        val rawHex = response.toHex()

        if (response.size < VERSION_PACKET_SIZE) {
            return failure(
                "Version response too short: ${response.size} bytes",
                rawHex
            )
        }

        val protocol = readUInt32BE(response, 0)
        val packetLength = readUInt32BE(response, 4)

        if (protocol != VERSION_PROTOCOL) {
            return failure("Unexpected response protocol: $protocol", rawHex)
        }

        if (packetLength != response.size) {
            return failure(
                "Packet length mismatch: expected $packetLength, received ${response.size}",
                rawHex
            )
        }

        val major = readUInt32BE(response, 8)
        val minor = readUInt32BE(response, 12)

        if (major != 1 && major != 2) {
            return VersionResult(
                false,
                "Unsupported USBMUX version: $major.$minor",
                major,
                minor,
                rawHex
            )
        }

        if (major == 2) {
            val setupPacket = byteArrayOf(
                // Protocol: SETUP (1)
                0, 0, 0, 1,

                // Total length: 17
                0, 0, 0, 17,

                // USBMUX2 magic: 0xFEEDFACE
                0xFE.toByte(),
                0xED.toByte(),
                0xFA.toByte(),
                0xCE.toByte(),

                // TX sequence: 0
                0, 0,

                // RX sequence: 0xFFFF
                0xFF.toByte(),
                0xFF.toByte(),

                // Setup payload
                0x07
            )

            val setupWritten = try {
                transport.write(setupPacket, timeoutMs)
            } catch (e: Exception) {
                return VersionResult(
                    false,
                    "Version $major.$minor received, but USBMUX2 setup write failed: ${e.message}",
                    major,
                    minor,
                    rawHex
                )
            }

            if (setupWritten != setupPacket.size) {
                return VersionResult(
                    false,
                    "Version $major.$minor received, but setup packet was incomplete: $setupWritten/${setupPacket.size}",
                    major,
                    minor,
                    rawHex
                )
            }

            return VersionResult(
                true,
                "USBMUX version $major.$minor accepted. USBMUX2 setup packet sent; setup response not yet validated.",
                major,
                minor,
                rawHex
            )
        }

        return VersionResult(
            true,
            "USBMUX version $major.$minor accepted. USBMUX1 mode.",
            major,
            minor,
            rawHex
        )
    }

    private fun readPacket(
        transport: AppleUsbTransport,
        timeoutMs: Int
    ): ByteArray? {

        val buffer = ByteArrayOutputStream()
        var expectedLength = -1

        repeat(10) {
            val chunk = try {
                transport.read(4096, timeoutMs)
            } catch (_: Exception) {
                return null
            } ?: return null

            if (chunk.isEmpty()) return@repeat

            buffer.write(chunk)
            val current = buffer.toByteArray()

            if (current.size >= BASIC_HEADER_SIZE && expectedLength < 0) {
                expectedLength = readUInt32BE(current, 4)

                if (expectedLength < VERSION_PACKET_SIZE ||
                    expectedLength > MAX_PACKET_SIZE
                ) {
                    return null
                }
            }

            if (expectedLength > 0 && current.size >= expectedLength) {
                return current.copyOf(expectedLength)
            }
        }

        return null
    }

    private fun readUInt32BE(
        data: ByteArray,
        offset: Int
    ): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") {
            "%02X".format(it.toInt() and 0xFF)
        }

    private fun failure(
        message: String,
        rawHex: String = ""
    ) = VersionResult(
        success = false,
        message = message,
        rawHex = rawHex
    )
}
