
package com.impactor.android

import android.os.SystemClock

/**
 * USBMUX2 pseudo-TCP connection.
 *
 * Implements:
 * - USBMUX2 extended packet headers
 * - Directional USBMUX2 magic values
 * - USBMUX sequence tracking
 * - TCP SYN / SYN-ACK / ACK validation
 * - Raw USB response diagnostics
 *
 * Not yet implemented:
 * - TCP data transfer
 * - Flow control and retransmission
 * - Lockdown protocol and pairing
 */
class UsbMux2TcpConnection(
    private val transport: AppleUsbTransport,
    private val remotePort: Int = 62078,
    private val localPort: Int = 49152
) {

    data class ConnectResult(
        val success: Boolean,
        val message: String,
        val localPort: Int,
        val remotePort: Int,
        val rawResponse: String = ""
    )

    companion object {
        private const val PROTOCOL_TCP = 6L

        private const val MUX_HEADER_SIZE = 16
        private const val TCP_HEADER_SIZE = 20

        private const val MIN_PACKET_SIZE =
            MUX_HEADER_SIZE + TCP_HEADER_SIZE

        private const val MUX_MAGIC_OUT = 0xFEEDFACEL
        private const val MUX_MAGIC_IN = 0xFACEFACEL

        private const val TCP_FIN = 0x01
        private const val TCP_SYN = 0x02
        private const val TCP_RST = 0x04
        private const val TCP_PSH = 0x08
        private const val TCP_ACK = 0x10

        private const val TCP_WINDOW = 512

        private const val MAX_PACKET_SIZE = 16 * 1024
        private const val MAX_DIAGNOSTIC_BYTES = 256
    }

    private var muxTxSequence = 1
    private var muxRxSequence = 0xFFFF

    private var tcpTxSequence = 0L
    private var tcpRxAcknowledgement = 0L

    private var receiveBuffer = ByteArray(0)

    private var lastReceivedPacket = ByteArray(0)
    private var lastReceivedBytes = ByteArray(0)

    @Volatile
    var isConnected = false
        private set

    @Synchronized
    fun connect(timeoutMs: Int = 7000): ConnectResult {

        if (isConnected) {
            return result(
                true,
                "TCP connection is already established."
            )
        }

        require(remotePort in 1..65535)
        require(localPort in 1..65535)
        require(timeoutMs > 0)

        isConnected = false

        receiveBuffer = ByteArray(0)
        lastReceivedPacket = ByteArray(0)
        lastReceivedBytes = ByteArray(0)

        muxTxSequence = 1
        muxRxSequence = 0xFFFF

        tcpTxSequence = 0L
        tcpRxAcknowledgement = 0L

        try {
            // Step 1: Send TCP SYN.
            sendTcpPacket(
                flags = TCP_SYN,
                sequence = tcpTxSequence,
                acknowledgement = tcpRxAcknowledgement
            )

            val deadline =
                SystemClock.elapsedRealtime() + timeoutMs

            while (SystemClock.elapsedRealtime() < deadline) {

                val packet = readMuxPacket(deadline)
                    ?: continue

                lastReceivedPacket = packet.copyOf()

                if (packet.size < MIN_PACKET_SIZE) {
                    continue
                }

                val protocol = readUInt32BE(packet, 0)

                if (protocol != PROTOCOL_TCP) {
                    continue
                }

                validateMuxHeader(packet)

                // Incoming USBMUX2 sequence.
                muxRxSequence = readUInt16BE(packet, 14)

                val tcpOffset = MUX_HEADER_SIZE

                val sourcePort =
                    readUInt16BE(packet, tcpOffset)

                val destinationPort =
                    readUInt16BE(packet, tcpOffset + 2)

                // Ignore packets for other TCP connections.
                if (
                    sourcePort != remotePort ||
                    destinationPort != localPort
                ) {
                    continue
                }

                val remoteSequence =
                    readUInt32BE(packet, tcpOffset + 4)

                val remoteAcknowledgement =
                    readUInt32BE(packet, tcpOffset + 8)

                val dataOffsetByte =
                    packet[tcpOffset + 12].toInt() and 0xFF

                val dataOffset =
                    ((dataOffsetByte ushr 4) and 0x0F) * 4

                val flags =
                    packet[tcpOffset + 13].toInt() and 0xFF

                // Step 2: Validate the TCP header.
                // A standard TCP header must be at least 20 bytes.
                if (
                    dataOffset < TCP_HEADER_SIZE ||
                    dataOffset > 60 ||
                    packet.size < tcpOffset + dataOffset
                ) {
                    isConnected = false

                    return result(
                        false,
                        "DEBUG: Invalid TCP header received. " +
                            "Updated parser is running. " +
                            "Raw data-offset byte=0x" +
                            dataOffsetByte.toString(16) +
                            ", decoded size=$dataOffset bytes. " +
                            "Flags=0x${flags.toString(16)}. " +
                            "Expected TCP header size: " +
                            "20 to 60 bytes.",
                        packet.toHex()
                    )
                }

                // Step 3: Check TCP reset.
                if ((flags and TCP_RST) != 0) {
                    isConnected = false

                    return result(
                        false,
                        "The iPad rejected the connection with TCP RST. " +
                            "Flags=0x${flags.toString(16)}.",
                        packet.toHex()
                    )
                }

                val hasSyn = (flags and TCP_SYN) != 0
                val hasAck = (flags and TCP_ACK) != 0

                // Step 4: Require SYN + ACK.
                if (!hasSyn || !hasAck) {
                    continue
                }

                // Step 5: Validate acknowledgement.
                val expectedAcknowledgement =
                    (tcpTxSequence + 1) and 0xFFFFFFFFL

                if (
                    remoteAcknowledgement !=
                    expectedAcknowledgement
                ) {
                    isConnected = false

                    return result(
                        false,
                        "Invalid SYN-ACK acknowledgement. " +
                            "Expected=$expectedAcknowledgement, " +
                            "Received=$remoteAcknowledgement.",
                        packet.toHex()
                    )
                }

                // Step 6: Accept remote SYN sequence.
                tcpTxSequence = expectedAcknowledgement

                tcpRxAcknowledgement =
                    (remoteSequence + 1) and 0xFFFFFFFFL

                // Step 7: Send final TCP ACK.
                sendTcpPacket(
                    flags = TCP_ACK,
                    sequence = tcpTxSequence,
                    acknowledgement = tcpRxAcknowledgement
                )

                // Only mark connected after successful ACK write.
                isConnected = true

                return result(
                    true,
                    "USBMUX2 pseudo-TCP handshake completed. " +
                        "Connected to port $remotePort. " +
                        "Lockdown communication is not implemented yet.",
                    packet.toHex()
                )
            }

            isConnected = false

            return result(
                false,
                "Timed out waiting for a valid TCP SYN-ACK response.",
                diagnosticHex()
            )

        } catch (error: Exception) {

            isConnected = false

            return result(
                false,
                "USBMUX2 TCP connection failed: " +
                    "${error.javaClass.simpleName}: ${error.message}",
                diagnosticHex()
            )
        }
    }

    private fun sendTcpPacket(
        flags: Int,
        sequence: Long,
        acknowledgement: Long
    ) {

        val tcp = ByteArray(TCP_HEADER_SIZE)

        putUInt16BE(tcp, 0, localPort)
        putUInt16BE(tcp, 2, remotePort)

        putUInt32BE(tcp, 4, sequence)
        putUInt32BE(tcp, 8, acknowledgement)

        tcp[12] = 0x50
        tcp[13] = flags.toByte()

        putUInt16BE(tcp, 14, TCP_WINDOW)

        // Checksum and urgent pointer.
        putUInt16BE(tcp, 16, 0)
        putUInt16BE(tcp, 18, 0)

        val packet = ByteArray(
            MUX_HEADER_SIZE + TCP_HEADER_SIZE
        )

        putUInt32BE(packet, 0, PROTOCOL_TCP)
        putUInt32BE(packet, 4, packet.size.toLong())

        // Outgoing Android -> Apple magic.
        putUInt32BE(packet, 8, MUX_MAGIC_OUT)

        putUInt16BE(packet, 12, muxTxSequence)
        putUInt16BE(packet, 14, muxRxSequence)

        System.arraycopy(
            tcp,
            0,
            packet,
            MUX_HEADER_SIZE,
            tcp.size
        )

        val written = transport.write(packet, 3000)

        check(written == packet.size) {
            "Incomplete USBMUX2 TCP write: " +
                "$written/${packet.size} bytes."
        }

        muxTxSequence =
            (muxTxSequence + 1) and 0xFFFF
    }

    private fun readMuxPacket(deadline: Long): ByteArray? {

        while (SystemClock.elapsedRealtime() < deadline) {

            if (receiveBuffer.size >= 8) {

                val packetLengthLong =
                    readUInt32BE(receiveBuffer, 4)

                if (
                    packetLengthLong < MIN_PACKET_SIZE.toLong() ||
                    packetLengthLong > MAX_PACKET_SIZE.toLong()
                ) {
                    throw IllegalStateException(
                        "Invalid USBMUX2 packet length: " +
                            "$packetLengthLong. " +
                            "Received: ${receiveBuffer.toHex()}"
                    )
                }

                val packetLength =
                    packetLengthLong.toInt()

                if (receiveBuffer.size >= packetLength) {

                    val packet = receiveBuffer.copyOfRange(
                        0,
                        packetLength
                    )

                    receiveBuffer = receiveBuffer.copyOfRange(
                        packetLength,
                        receiveBuffer.size
                    )

                    lastReceivedPacket = packet.copyOf()

                    return packet
                }
            }

            val remaining =
                deadline - SystemClock.elapsedRealtime()

            if (remaining <= 0) {
                break
            }

            val chunk = transport.read(
                maxBytes = 4096,
                timeoutMs = minOf(remaining, 500L)
                    .toInt()
                    .coerceAtLeast(1)
            )

            if (chunk != null && chunk.isNotEmpty()) {

                lastReceivedBytes = (
                    lastReceivedBytes + chunk
                ).takeLastBytes(MAX_DIAGNOSTIC_BYTES)

                if (
                    receiveBuffer.size + chunk.size >
                    MAX_PACKET_SIZE * 2
                ) {
                    throw IllegalStateException(
                        "USBMUX2 receive buffer exceeded its limit."
                    )
                }

                receiveBuffer += chunk
            }
        }

        return null
    }

    private fun validateMuxHeader(packet: ByteArray) {

        check(packet.size >= MIN_PACKET_SIZE) {
            "USBMUX2 packet is too short: ${packet.size} bytes."
        }

        val protocol = readUInt32BE(packet, 0)
        val length = readUInt32BE(packet, 4)
        val magic = readUInt32BE(packet, 8)

        check(length == packet.size.toLong()) {
            "USBMUX2 packet length mismatch. " +
                "Header=$length Actual=${packet.size}."
        }

        check(protocol == PROTOCOL_TCP) {
            "Unexpected USBMUX2 protocol: $protocol."
        }

        // Incoming Apple -> Android magic.
        check(magic == MUX_MAGIC_IN) {
            "Invalid incoming USBMUX2 magic. " +
                "Received=0x${magic.toString(16)}, " +
                "Expected=0x${MUX_MAGIC_IN.toString(16)}. " +
                "Raw=${packet.toHex()}"
        }
    }

    private fun diagnosticHex(): String {

        return when {
            lastReceivedPacket.isNotEmpty() ->
                lastReceivedPacket.toHex()

            lastReceivedBytes.isNotEmpty() ->
                lastReceivedBytes.toHex()

            receiveBuffer.isNotEmpty() ->
                receiveBuffer.takeLastBytes(
                    MAX_DIAGNOSTIC_BYTES
                ).toHex()

            else ->
                "No response bytes received."
        }
    }

    private fun ByteArray.takeLastBytes(
        maxBytes: Int
    ): ByteArray {

        if (size <= maxBytes) return copyOf()

        return copyOfRange(
            size - maxBytes,
            size
        )
    }

    private fun result(
        success: Boolean,
        message: String,
        rawResponse: String = ""
    ) = ConnectResult(
        success = success,
        message = message,
        localPort = localPort,
        remotePort = remotePort,
        rawResponse = rawResponse
    )

    private fun readUInt16BE(
        data: ByteArray,
        offset: Int
    ): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
    }

    private fun readUInt32BE(
        data: ByteArray,
        offset: Int
    ): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    private fun putUInt16BE(
        data: ByteArray,
        offset: Int,
        value: Int
    ) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun putUInt32BE(
        data: ByteArray,
        offset: Int,
        value: Long
    ) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") {
            "%02X".format(it.toInt() and 0xFF)
        }

    @Synchronized
    fun close() {
        isConnected = false
        receiveBuffer = ByteArray(0)
        lastReceivedPacket = ByteArray(0)
        lastReceivedBytes = ByteArray(0)
    }
}
