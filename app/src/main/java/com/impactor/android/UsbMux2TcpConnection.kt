
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
 * - TCP reserved-bit validation
 * - TCP NS flag diagnostics
 * - Indexed raw USB response diagnostics
 * - Fragmented USB packet buffering
 *
 * Not yet implemented:
 * - TCP payload transmission
 * - TCP flow control and retransmission
 * - Lockdown protocol
 * - Apple pairing
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

        private const val MIN_MUX_PACKET_SIZE = MUX_HEADER_SIZE

        private const val MIN_TCP_PACKET_SIZE =
            MUX_HEADER_SIZE + TCP_HEADER_SIZE

        private const val MUX_MAGIC_OUT = 0xFEEDFACEL
        private const val MUX_MAGIC_IN = 0xFACEFACEL

        private const val TCP_FIN = 0x01
        private const val TCP_SYN = 0x02
        private const val TCP_RST = 0x04
        private const val TCP_PSH = 0x08
        private const val TCP_ACK = 0x10

        // TCP byte 12:
        // Bits 7-4: Data offset
        // Bits 3-1: Reserved
        // Bit 0: NS flag
        private const val TCP_RESERVED_MASK = 0x0E
        private const val TCP_NS_MASK = 0x01

        private const val TCP_WINDOW = 512

        private const val MAX_PACKET_SIZE = 16 * 1024
        private const val MAX_BUFFER_SIZE = MAX_PACKET_SIZE * 2
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

        resetConnectionState()

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

                if (packet.size < MIN_MUX_PACKET_SIZE) {
                    continue
                }

                val protocol = readUInt32BE(packet, 0)

                // Ignore non-TCP USBMUX2 packets.
                if (protocol != PROTOCOL_TCP) {
                    continue
                }

                validateMuxHeader(packet)

                if (packet.size < MIN_TCP_PACKET_SIZE) {
                    return failure(
                        "TCP packet is shorter than the minimum " +
                            "20-byte TCP header.",
                        packet
                    )
                }

                // Update incoming USBMUX2 sequence.
                muxRxSequence = readUInt16BE(packet, 14)

                val tcpOffset = MUX_HEADER_SIZE

                val sourcePort =
                    readUInt16BE(packet, tcpOffset)

                val destinationPort =
                    readUInt16BE(packet, tcpOffset + 2)

                // Ignore unrelated TCP connections.
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

                // Extract TCP reserved bits separately from NS.
                val reservedBits =
                    dataOffsetByte and TCP_RESERVED_MASK

                val nsFlag =
                    (dataOffsetByte and TCP_NS_MASK) != 0

                // Step 2: Validate TCP header length.
                if (
                    dataOffset < TCP_HEADER_SIZE ||
                    dataOffset > 60 ||
                    packet.size < tcpOffset + dataOffset
                ) {
                    return failure(
                        "Invalid TCP header length. " +
                            "Data-offset byte=0x${hexByte(dataOffsetByte)}, " +
                            "decoded size=$dataOffset bytes. " +
                            "Flags=0x${hexByte(flags)}.",
                        packet
                    )
                }

                // Step 3: Reject nonzero reserved bits.
                if (reservedBits != 0) {
                    return failure(
                        "Invalid TCP reserved bits. " +
                            "Data-offset byte=0x${hexByte(dataOffsetByte)}. " +
                            "Reserved bits=0x${hexByte(reservedBits)}. " +
                            "NS flag=$nsFlag. " +
                            "Expected reserved bits to be zero. " +
                            "The TCP handshake was not completed.",
                        packet
                    )
                }

                // Step 4: Check TCP reset.
                if ((flags and TCP_RST) != 0) {
                    return failure(
                        "The Apple device rejected the connection " +
                            "with TCP RST. " +
                            "Flags=0x${hexByte(flags)}.",
                        packet
                    )
                }

                val hasSyn = (flags and TCP_SYN) != 0
                val hasAck = (flags and TCP_ACK) != 0

                // Step 5: Require SYN + ACK.
                if (!hasSyn || !hasAck) {
                    continue
                }

                // Step 6: Validate acknowledgement.
                val expectedAcknowledgement =
                    (tcpTxSequence + 1) and 0xFFFFFFFFL

                if (
                    remoteAcknowledgement !=
                    expectedAcknowledgement
                ) {
                    return failure(
                        "Invalid SYN-ACK acknowledgement. " +
                            "Expected=$expectedAcknowledgement, " +
                            "Received=$remoteAcknowledgement. " +
                            "The TCP handshake was not completed.",
                        packet
                    )
                }

                // Step 7: Accept remote SYN sequence.
                tcpTxSequence = expectedAcknowledgement

                tcpRxAcknowledgement =
                    (remoteSequence + 1) and 0xFFFFFFFFL

                // Step 8: Send final TCP ACK.
                sendTcpPacket(
                    flags = TCP_ACK,
                    sequence = tcpTxSequence,
                    acknowledgement = tcpRxAcknowledgement
                )

                // Step 9: Mark connected after ACK write.
                isConnected = true

                return result(
                    true,
                    "USBMUX2 pseudo-TCP handshake completed. " +
                        "Connected to port $remotePort. " +
                        "TCP NS flag=$nsFlag. " +
                        "Lockdown communication is not implemented yet.",
                    indexedHex(packet)
                )
            }

            return result(
                false,
                "Timed out waiting for a valid TCP SYN-ACK response. " +
                    "The TCP handshake was not completed.",
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

    private fun resetConnectionState() {

        isConnected = false

        receiveBuffer = ByteArray(0)

        lastReceivedPacket = ByteArray(0)
        lastReceivedBytes = ByteArray(0)

        muxTxSequence = 1
        muxRxSequence = 0xFFFF

        tcpTxSequence = 0L
        tcpRxAcknowledgement = 0L
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

        // Data offset = 5 words = 20 bytes.
        // Reserved bits = 0.
        // NS flag = 0.
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
                    packetLengthLong < MIN_MUX_PACKET_SIZE.toLong() ||
                    packetLengthLong > MAX_PACKET_SIZE.toLong()
                ) {
                    throw IllegalStateException(
                        "Invalid USBMUX2 packet length: " +
                            "$packetLengthLong.\n" +
                            indexedHex(receiveBuffer)
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
                    MAX_BUFFER_SIZE
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

        check(packet.size >= MIN_MUX_PACKET_SIZE) {
            "USBMUX2 packet is too short: ${packet.size} bytes."
        }

        val protocol = readUInt32BE(packet, 0)
        val length = readUInt32BE(packet, 4)
        val magic = readUInt32BE(packet, 8)

        check(length == packet.size.toLong()) {
            "USBMUX2 packet length mismatch. " +
                "Header=$length Actual=${packet.size}.\n" +
                indexedHex(packet)
        }

        check(protocol == PROTOCOL_TCP) {
            "Unexpected USBMUX2 protocol: $protocol."
        }

        check(magic == MUX_MAGIC_IN) {
            "Invalid incoming USBMUX2 magic. " +
                "Received=0x${magic.toString(16)}, " +
                "Expected=0x${MUX_MAGIC_IN.toString(16)}.\n" +
                indexedHex(packet)
        }
    }

    private fun failure(
        message: String,
        packet: ByteArray
    ): ConnectResult {

        isConnected = false

        return result(
            false,
            message,
            indexedHex(packet)
        )
    }

    /**
     * Displays every byte with its exact packet offset.
     */
    private fun indexedHex(data: ByteArray): String {

        if (data.isEmpty()) {
            return "No bytes."
        }

        return buildString {

            append("Packet size: ${data.size} bytes\n")

            data.indices.forEach { index ->

                append(
                    "%02d: %02X".format(
                        index,
                        data[index].toInt() and 0xFF
                    )
                )

                if (index != data.lastIndex) {
                    append('\n')
                }
            }
        }
    }

    private fun diagnosticHex(): String {

        return when {

            lastReceivedPacket.isNotEmpty() ->
                indexedHex(lastReceivedPacket)

            lastReceivedBytes.isNotEmpty() ->
                "Last received USB bytes:\n" +
                    indexedHex(lastReceivedBytes)

            receiveBuffer.isNotEmpty() ->
                "Remaining receive buffer:\n" +
                    indexedHex(
                        receiveBuffer.takeLastBytes(
                            MAX_DIAGNOSTIC_BYTES
                        )
                    )

            else ->
                "No response bytes received."
        }
    }

    private fun ByteArray.takeLastBytes(
        maxBytes: Int
    ): ByteArray {

        if (size <= maxBytes) {
            return copyOf()
        }

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

    private fun hexByte(value: Int): String =
        "%02X".format(value and 0xFF)

    @Synchronized
    fun close() {
        resetConnectionState()
    }
}
