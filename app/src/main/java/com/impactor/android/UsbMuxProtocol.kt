
package com.impactor.android

/**
 * Apple USBMUX device-side protocol.
 *
 * Implements the initial version negotiation only.
 * Does not yet implement USBMUX2 setup, pseudo-TCP, or Lockdown.
 */
object UsbMuxProtocol {

    private const val PROTOCOL_VERSION = 0
    private const val VERSION_PACKET_SIZE = 20
    private const val HEADER_SIZE = 8
    private const val MAX_PACKET_SIZE = 4096

    data class VersionResult(
        val success: Boolean,
        val message: String,
        val majorVersion: Int? = null,
        val minorVersion: Int? = null,
        val rawHex: String = ""
    )

    /**
     * Sends the initial USBMUX version request and waits for the response.
     *
     * Call this only after AppleUsbTransport.openUsbmux() succeeds.
     */
    @Synchronized
    fun performVersionHandshake(
        transport: AppleUsbTransport,
        timeoutMs: Int = 5000
    ): VersionResult {

        val request = byteArrayOf(
            // Protocol: VERSION (0), big-endian
            0x00, 0x00, 0x00, 0x00,

            // Total packet length: 20 bytes
            0x00, 0x00, 0x00, 0x14,

            // Requested major version: 2
            0x00, 0x00, 0x00, 0x02,

            // Requested minor version: 0
            0x00, 0x00, 0x00, 0x00,

            // Padding
            0x00, 0x00, 0x00, 0x00
        )

        val written = try {
            transport.write(request, timeoutMs)
        } catch (e: Exception) {
            return VersionResult(
                success = false,
                message = "USBMUX version request failed: ${e.message}"
            )
        }

        if (written != request.size) {
            return VersionResult(
                success = false,
                message = "Incomplete USB write: $written/${request.size} bytes"
            )
        }

        val response = readPacket(transport, timeoutMs)
            ?: return VersionResult(
                success = false,
                message = "No USBMUX version response received. Check the USB connection and active interface."
            )

        val hex = response.joinToString(" ") {
            "%02X".format(it.toInt() and 0xFF)
        }

        if (response.size < VERSION_PACKET_SIZE) {
            return VersionResult(
                success = false,
                message = "Response too short: ${response.size} bytes",
                rawHex = hex
            )
        }

        val protocol = readUInt32BE(response, 0)
        val packetLength = readUInt32BE(response, 4)

        if (protocol != PROTOCOL_VERSION) {
            return VersionResult(
                success = false,
                message = "Unexpected protocol: $protocol",
                rawHex = hex
            )
        }

        if (packetLength != response.size) {
            return VersionResult(
                success = false,
                message = "Packet length mismatch: header=$packetLength, received=${response.size}",
                rawHex = hex
            )
        }

        if (packetLength > MAX_PACKET_SIZE) {
            return VersionResult(
                success = false,
                message = "USBMUX response exceeds maximum packet size",
                rawHex = hex
            )
        }

        val major = readUInt32BE(response, HEADER_SIZE)
        val minor = readUInt32BE(response, HEADER_SIZE + 4)

        if (major != 1 && major != 2) {
            return VersionResult(
                success = false,
                message = "Unsupported USBMUX version: $major.$minor",
                majorVersion = major,
                minorVersion = minor,
                rawHex = hex
            )
        }

        return VersionResult(
            success = true,
            message = "USBMUX version negotiation successful: $major.$minor",
            majorVersion = major,
            minorVersion = minor,
            rawHex = hex
        )
    }

    /**
     * Reads one complete USBMUX packet using its length field.
     */
    private fun readPacket(
        transport: AppleUsbTransport,
        timeoutMs: Int
    ): ByteArray? {

        val buffer = ArrayList<Byte>()
        var expectedLength = -1
        var attempts = 0

        while (attempts < 10) {
            attempts++

            val chunk = try {
                transport.read(
                    maxBytes = 4096,
                    timeoutMs = timeoutMs
                )
            } catch (_: Exception) {
                return null
            } ?: return null

            if (chunk.isEmpty()) continue

            for (byte in chunk) {
                buffer.add(byte)
            }

            if (buffer.size >= HEADER_SIZE && expectedLength < 0) {
                val header = buffer.toByteArray()
                expectedLength = readUInt32BE(header, 4)

                if (expectedLength < VERSION_PACKET_SIZE ||
                    expectedLength > MAX_PACKET_SIZE
                ) {
                    return null
                }
            }

            if (expectedLength > 0 && buffer.size >= expectedLength) {
                return buffer.take(expectedLength).toByteArray()
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
}
