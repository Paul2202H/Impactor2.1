
package com.impactor.android

import android.os.SystemClock

/**
 * USBMUX2 pseudo-TCP connection.
 *
 * Supports:
 * - USBMUX2 extended packet framing
 * - Directional USBMUX2 magic values
 * - USBMUX2 sequence tracking
 * - TCP SYN / SYN-ACK / ACK handshake
 * - Compatible TCP header parsing
 * - TCP payload transmission and reception
 * - Wraparound-safe acknowledgement tracking
 * - Receive-window handling
 * - Incoming payload buffering
 * - Basic FIN/RST handling
 * - Fragmented USB packet buffering
 *
 * Not implemented:
 * - TCP retransmission
 * - Full TCP recovery/state machine
 * - Out-of-order payload buffering
 * - TCP window scaling
 * - Zero-window probing
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

    data class TransferResult(
        val success: Boolean,
        val message: String,
        val bytesTransferred: Int = 0,
        val rawResponse: String = ""
    )

    data class ReceiveResult(
        val success: Boolean,
        val message: String,
        val data: ByteArray = ByteArray(0)
    )

    private data class TcpPacket(
        val sequence: Long,
        val acknowledgement: Long,
        val flags: Int,
        val window: Int,
        val headerControlBits: Int,
        val payload: ByteArray
    )

    companion object {
        private const val PROTOCOL_TCP = 6L

        private const val MUX_HEADER_SIZE = 16
        private const val TCP_HEADER_SIZE = 20

        private const val MIN_MUX_PACKET_SIZE = 16
        private const val MIN_TCP_PACKET_SIZE = 36

        private const val MUX_MAGIC_OUT = 0xFEEDFACEL
        private const val MUX_MAGIC_IN = 0xFACEFACEL

        private const val TCP_FIN = 0x01
        private const val TCP_SYN = 0x02
        private const val TCP_RST = 0x04
        private const val TCP_PSH = 0x08
        private const val TCP_ACK = 0x10

        private const val TCP_WINDOW = 512

        private const val MAX_PACKET_SIZE = 16 * 1024
        private const val MAX_BUFFER_SIZE = MAX_PACKET_SIZE * 2
        private const val MAX_DIAGNOSTIC_BYTES = 256

        private const val MAX_TCP_PAYLOAD = 512

        private const val SEQUENCE_MASK = 0xFFFFFFFFL
        private const val SEQUENCE_HALF = 0x80000000L
    }

    // USBMUX2 sequence numbers.
    private var muxTxSequence = 1
    private var muxRxSequence = 0xFFFF

    // TCP sequence numbers.
    private var tcpTxSequence = 0L
    private var tcpTxAcknowledged = 0L
    private var tcpRxAcknowledgement = 0L

    private var peerWindow = TCP_WINDOW

    // USB receive buffer.
    private var receiveBuffer = ByteArray(0)

    // Application data waiting to be read.
    private var pendingReceivedData = ByteArray(0)

    // Diagnostics.
    private var lastReceivedPacket = ByteArray(0)
    private var lastReceivedBytes = ByteArray(0)
    private var lastHeaderControlBits = 0

    @Volatile
    var isConnected = false
        private set

    @Volatile
    var isRemoteClosed = false
        private set

    // ---------------------------------------------------------
    // CONNECTION
    // ---------------------------------------------------------

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
            sendTcpPacket(
                flags = TCP_SYN,
                sequence = tcpTxSequence,
                acknowledgement = tcpRxAcknowledgement
            )

            val deadline =
                SystemClock.elapsedRealtime() + timeoutMs

            while (SystemClock.elapsedRealtime() < deadline) {

                val packet = readMuxPacket(deadline) ?: continue

                if (readUInt32BE(packet, 0) != PROTOCOL_TCP) {
                    continue
                }

                val tcp = parseTcpPacket(packet) ?: continue

                if ((tcp.flags and TCP_RST) != 0) {
                    return failure(
                        "The Apple device rejected the connection.",
                        packet
                    )
                }

                val hasSyn = (tcp.flags and TCP_SYN) != 0
                val hasAck = (tcp.flags and TCP_ACK) != 0

                if (!hasSyn || !hasAck) {
                    continue
                }

                val expectedAck = addSequence(tcpTxSequence, 1)

                if (tcp.acknowledgement != expectedAck) {
                    return failure(
                        "Invalid SYN-ACK acknowledgement. " +
                            "Expected=$expectedAck, " +
                            "Received=${tcp.acknowledgement}.",
                        packet
                    )
                }

                tcpTxSequence = expectedAck
                tcpTxAcknowledged = expectedAck

                tcpRxAcknowledgement =
                    addSequence(tcp.sequence, 1)

                peerWindow = tcp.window

                // Final handshake ACK.
                sendTcpPacket(
                    flags = TCP_ACK,
                    sequence = tcpTxSequence,
                    acknowledgement = tcpRxAcknowledgement
                )

                isConnected = true

                return result(
                    true,
                    "USBMUX2 pseudo-TCP handshake completed. " +
                        "Connected to port $remotePort. " +
                        "Further data exchange still needs validation. " +
                        "Header control bits: 0x${hexByte(tcp.headerControlBits)}.",
                    indexedHex(packet)
                )
            }

            return result(
                false,
                "Timed out waiting for TCP SYN-ACK.",
                diagnosticHex()
            )

        } catch (error: Exception) {
            isConnected = false

            return result(
                false,
                "Connection failed: " +
                    "${error.javaClass.simpleName}: ${error.message}",
                diagnosticHex()
            )
        }
    }

    // ---------------------------------------------------------
    // SEND TCP DATA
    // ---------------------------------------------------------

    @Synchronized
    fun sendData(
        data: ByteArray,
        timeoutMs: Int = 5000
    ): TransferResult {

        if (!isConnected) {
            return transferFailure("TCP is not connected.")
        }

        if (isRemoteClosed) {
            return transferFailure(
                "The remote device closed the connection."
            )
        }

        if (data.isEmpty()) {
            return TransferResult(
                true,
                "No data to send.",
                0
            )
        }

        require(timeoutMs > 0)

        if (tcpTxSequence != tcpTxAcknowledged) {
            return transferFailure(
                "Previous TCP data is still unacknowledged. " +
                    "Reconnect before sending more data."
            )
        }

        val deadline =
            SystemClock.elapsedRealtime() + timeoutMs

        var totalSent = 0

        try {
            while (totalSent < data.size) {

                // Wait for a non-zero peer receive window.
                while (
                    peerWindow == 0 &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    val packet = readMuxPacket(deadline) ?: continue
                    processIncomingPacket(packet)
                }

                if (peerWindow == 0) {
                    return TransferResult(
                        false,
                        "Peer receive window remained zero.",
                        totalSent,
                        diagnosticHex()
                    )
                }

                val chunkSize = minOf(
                    MAX_TCP_PAYLOAD,
                    peerWindow,
                    data.size - totalSent
                )

                if (chunkSize <= 0) {
                    return TransferResult(
                        false,
                        "No available TCP send window.",
                        totalSent,
                        diagnosticHex()
                    )
                }

                val chunk = data.copyOfRange(
                    totalSent,
                    totalSent + chunkSize
                )

                val expectedAck = addSequence(
                    tcpTxSequence,
                    chunk.size.toLong()
                )

                /*
                 * IMPORTANT:
                 *
                 * Apple's USBMUX TCP handler reported:
                 *
                 * th._flags = 0x18, not TH_ACK(0x10)
                 *
                 * 0x18 = PSH | ACK
                 * 0x10 = ACK only
                 *
                 * Therefore, send application payload using
                 * ACK-only flags for this protocol test.
                 */
                sendTcpPacket(
                    flags = TCP_ACK,
                    sequence = tcpTxSequence,
                    acknowledgement = tcpRxAcknowledgement,
                    payload = chunk
                )

                tcpTxSequence = expectedAck

                // Wait for acknowledgement.
                while (
                    tcpTxAcknowledged != expectedAck &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    val packet = readMuxPacket(deadline) ?: continue
                    processIncomingPacket(packet)
                }

                if (tcpTxAcknowledged != expectedAck) {
                    return TransferResult(
                        false,
                        "TCP acknowledgement timeout. " +
                            "Expected ACK=$expectedAck, " +
                            "Received ACK=$tcpTxAcknowledged. " +
                            "Retransmission is not implemented.",
                        totalSent,
                        diagnosticHex()
                    )
                }

                totalSent += chunkSize
            }

            return TransferResult(
                true,
                "TCP data acknowledged by the device.",
                totalSent,
                diagnosticHex()
            )

        } catch (error: Exception) {
            return TransferResult(
                false,
                "TCP send failed: ${error.message}",
                totalSent,
                diagnosticHex()
            )
        }
    }

    // ---------------------------------------------------------
    // RECEIVE TCP DATA
    // ---------------------------------------------------------

    @Synchronized
    fun receiveData(
        maxBytes: Int = 4096,
        timeoutMs: Int = 5000
    ): ReceiveResult {

        if (!isConnected) {
            return ReceiveResult(
                false,
                "TCP is not connected."
            )
        }

        require(maxBytes > 0)
        require(timeoutMs > 0)

        val deadline =
            SystemClock.elapsedRealtime() + timeoutMs

        try {
            while (
                pendingReceivedData.isEmpty() &&
                SystemClock.elapsedRealtime() < deadline &&
                !isRemoteClosed
            ) {
                val packet = readMuxPacket(deadline) ?: continue
                processIncomingPacket(packet)
            }

            if (pendingReceivedData.isEmpty()) {
                return ReceiveResult(
                    true,
                    if (isRemoteClosed) {
                        "The remote device closed the connection."
                    } else {
                        "Receive timeout. No TCP payload received."
                    }
                )
            }

            val count = minOf(
                maxBytes,
                pendingReceivedData.size
            )

            val output = pendingReceivedData.copyOfRange(0, count)

            pendingReceivedData = pendingReceivedData.copyOfRange(
                count,
                pendingReceivedData.size
            )

            return ReceiveResult(
                true,
                "Received $count TCP payload bytes.",
                output
            )

        } catch (error: Exception) {
            return ReceiveResult(
                false,
                "TCP receive failed: ${error.message}"
            )
        }
    }

    // ---------------------------------------------------------
    // INCOMING PACKET PROCESSING
    // ---------------------------------------------------------

    private fun processIncomingPacket(packet: ByteArray) {

        if (packet.size < MIN_MUX_PACKET_SIZE) return

        if (readUInt32BE(packet, 0) != PROTOCOL_TCP) return

        val tcp = parseTcpPacket(packet) ?: return

        if ((tcp.flags and TCP_RST) != 0) {
            isConnected = false
            isRemoteClosed = true

            throw IllegalStateException(
                "The Apple device sent TCP RST."
            )
        }

        peerWindow = tcp.window

        if ((tcp.flags and TCP_ACK) != 0) {
            updateTcpAcknowledgement(tcp.acknowledgement)
        }

        var shouldSendAck = false

        if (tcp.payload.isNotEmpty()) {

            if (tcp.sequence == tcpRxAcknowledgement) {

                pendingReceivedData += tcp.payload

                tcpRxAcknowledgement = addSequence(
                    tcpRxAcknowledgement,
                    tcp.payload.size.toLong()
                )

                shouldSendAck = true

            } else {
                // Duplicate or out-of-order data.
                // Do not append it; acknowledge the next expected byte.
                shouldSendAck = true
            }
        }

        if ((tcp.flags and TCP_FIN) != 0) {

            val finSequence = addSequence(
                tcp.sequence,
                tcp.payload.size.toLong()
            )

            if (finSequence == tcpRxAcknowledgement) {
                tcpRxAcknowledgement =
                    addSequence(tcpRxAcknowledgement, 1)

                isRemoteClosed = true
            }

            shouldSendAck = true
        }

        if (shouldSendAck) {
            sendTcpPacket(
                flags = TCP_ACK,
                sequence = tcpTxSequence,
                acknowledgement = tcpRxAcknowledgement
            )
        }
    }

    // ---------------------------------------------------------
    // TCP ACK TRACKING
    // ---------------------------------------------------------

    private fun updateTcpAcknowledgement(ack: Long) {

        val outstanding = sequenceDistance(
            tcpTxAcknowledged,
            tcpTxSequence
        )

        val advancement = sequenceDistance(
            tcpTxAcknowledged,
            ack
        )

        if (
            advancement <= outstanding &&
            advancement < SEQUENCE_HALF
        ) {
            tcpTxAcknowledged = ack
        }
    }

    // ---------------------------------------------------------
    // TCP PACKET PARSER
    // ---------------------------------------------------------

    private fun parseTcpPacket(packet: ByteArray): TcpPacket? {

        validateMuxHeader(packet)

        if (packet.size < MIN_TCP_PACKET_SIZE) {
            return null
        }

        val offset = MUX_HEADER_SIZE

        val sourcePort = readUInt16BE(packet, offset)
        val destinationPort = readUInt16BE(packet, offset + 2)

        if (
            sourcePort != remotePort ||
            destinationPort != localPort
        ) {
            return null
        }

        val sequence = readUInt32BE(packet, offset + 4)
        val acknowledgement = readUInt32BE(packet, offset + 8)

        val dataOffsetByte =
            packet[offset + 12].toInt() and 0xFF

        val headerSize =
            ((dataOffsetByte ushr 4) and 0x0F) * 4

        val headerControlBits = dataOffsetByte and 0x0F

        val flags = packet[offset + 13].toInt() and 0xFF

        if (
            headerSize < TCP_HEADER_SIZE ||
            headerSize > 60 ||
            packet.size < offset + headerSize
        ) {
            throw IllegalStateException(
                "Invalid TCP header length: $headerSize."
            )
        }

        val window = readUInt16BE(packet, offset + 14)

        val payloadOffset = offset + headerSize

        val payload = packet.copyOfRange(
            payloadOffset,
            packet.size
        )

        lastHeaderControlBits = headerControlBits

        return TcpPacket(
            sequence = sequence,
            acknowledgement = acknowledgement,
            flags = flags,
            window = window,
            headerControlBits = headerControlBits,
            payload = payload
        )
    }

    // ---------------------------------------------------------
    // TCP PACKET BUILDER
    // ---------------------------------------------------------

    private fun sendTcpPacket(
        flags: Int,
        sequence: Long,
        acknowledgement: Long,
        payload: ByteArray = ByteArray(0)
    ) {

        require(
            payload.size <=
                MAX_PACKET_SIZE - MIN_TCP_PACKET_SIZE
        )

        val tcp = ByteArray(
            TCP_HEADER_SIZE + payload.size
        )

        putUInt16BE(tcp, 0, localPort)
        putUInt16BE(tcp, 2, remotePort)

        putUInt32BE(tcp, 4, sequence)
        putUInt32BE(tcp, 8, acknowledgement)

        tcp[12] = 0x50.toByte()
        tcp[13] = flags.toByte()

        putUInt16BE(tcp, 14, TCP_WINDOW)

        putUInt16BE(tcp, 16, 0)
        putUInt16BE(tcp, 18, 0)

        if (payload.isNotEmpty()) {
            System.arraycopy(
                payload,
                0,
                tcp,
                TCP_HEADER_SIZE,
                payload.size
            )
        }

        val packet = ByteArray(
            MUX_HEADER_SIZE + tcp.size
        )

        putUInt32BE(packet, 0, PROTOCOL_TCP)
        putUInt32BE(packet, 4, packet.size.toLong())
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
            "Incomplete USBMUX2 write: $written/${packet.size} bytes."
        }

        muxTxSequence = (muxTxSequence + 1) and 0xFFFF
    }

    // ---------------------------------------------------------
    // USBMUX2 PACKET READER
    // ---------------------------------------------------------

    private fun readMuxPacket(deadline: Long): ByteArray? {

        while (SystemClock.elapsedRealtime() < deadline) {

            if (receiveBuffer.size >= 8) {

                val length = readUInt32BE(receiveBuffer, 4)

                if (
                    length < MIN_MUX_PACKET_SIZE.toLong() ||
                    length > MAX_PACKET_SIZE.toLong()
                ) {
                    throw IllegalStateException(
                        "Invalid USBMUX2 packet length: $length."
                    )
                }

                val packetLength = length.toInt()

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

            if (remaining <= 0) break

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
                        "USBMUX2 receive buffer limit exceeded."
                    )
                }

                receiveBuffer += chunk
            }
        }

        return null
    }

    // ---------------------------------------------------------
    // USBMUX2 HEADER VALIDATION
    // ---------------------------------------------------------

    private fun validateMuxHeader(packet: ByteArray) {

        check(packet.size >= MIN_MUX_PACKET_SIZE) {
            "USBMUX2 packet is too short."
        }

        val protocol = readUInt32BE(packet, 0)
        val length = readUInt32BE(packet, 4)
        val magic = readUInt32BE(packet, 8)

        check(protocol == PROTOCOL_TCP) {
            "Unexpected USBMUX2 protocol: $protocol."
        }

        check(length == packet.size.toLong()) {
            "USBMUX2 packet length mismatch."
        }

        check(magic == MUX_MAGIC_IN) {
            "Invalid incoming USBMUX2 magic: 0x${magic.toString(16)}."
        }

        muxRxSequence = readUInt16BE(packet, 14)
    }

    // ---------------------------------------------------------
    // SEQUENCE HELPERS
    // ---------------------------------------------------------

    private fun addSequence(
        sequence: Long,
        amount: Long
    ): Long {
        return (sequence + amount) and SEQUENCE_MASK
    }

    private fun sequenceDistance(
        start: Long,
        end: Long
    ): Long {
        return (end - start) and SEQUENCE_MASK
    }

    // ---------------------------------------------------------
    // DIAGNOSTICS
    // ---------------------------------------------------------

    private fun indexedHex(data: ByteArray): String {

        if (data.isEmpty()) return "No bytes."

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
                indexedHex(
                    receiveBuffer.takeLastBytes(
                        MAX_DIAGNOSTIC_BYTES
                    )
                )

            else -> "No response bytes received."
        }
    }

    private fun ByteArray.takeLastBytes(maxBytes: Int): ByteArray {
        if (size <= maxBytes) return copyOf()

        return copyOfRange(
            size - maxBytes,
            size
        )
    }

    // ---------------------------------------------------------
    // RESET AND CLOSE
    // ---------------------------------------------------------

    private fun resetConnectionState() {

        isConnected = false
        isRemoteClosed = false

        receiveBuffer = ByteArray(0)
        pendingReceivedData = ByteArray(0)

        lastReceivedPacket = ByteArray(0)
        lastReceivedBytes = ByteArray(0)
        lastHeaderControlBits = 0

        muxTxSequence = 1
        muxRxSequence = 0xFFFF

        tcpTxSequence = 0L
        tcpTxAcknowledged = 0L
        tcpRxAcknowledgement = 0L

        peerWindow = TCP_WINDOW
    }

    @Synchronized
    fun close() {
        resetConnectionState()
    }

    // ---------------------------------------------------------
    // RESULT HELPERS
    // ---------------------------------------------------------

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

    private fun transferFailure(
        message: String
    ) = TransferResult(
        success = false,
        message = message
    )

    // ---------------------------------------------------------
    // BYTE HELPERS
    // ---------------------------------------------------------

    private fun readUInt16BE(
        data: ByteArray,
        offset: Int
    ): Int {
        return (
            (data[offset].toInt() and 0xFF) shl 8
        ) or (
            data[offset + 1].toInt() and 0xFF
        )
    }

    private fun readUInt32BE(
        data: ByteArray,
        offset: Int
    ): Long {
        return (
            (data[offset].toLong() and 0xFF) shl 24
        ) or (
            (data[offset + 1].toLong() and 0xFF) shl 16
        ) or (
            (data[offset + 2].toLong() and 0xFF) shl 8
        ) or (
            data[offset + 3].toLong() and 0xFF
        )
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

    private fun hexByte(value: Int): String {
        return "%02X".format(value and 0xFF)
    }
}
