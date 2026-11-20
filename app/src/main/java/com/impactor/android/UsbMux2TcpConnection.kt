
package com.impactor.android

import android.os.SystemClock
import java.io.ByteArrayOutputStream

/**
 * Initial USBMUX2 pseudo-TCP connection implementation.
 *
 * Connects to a TCP service through Apple's USBMUX2 transport.
 *
 * Implemented:
 * - USBMUX2 extended headers
 * - USBMUX packet sequence tracking
 * - TCP SYN
 * - TCP SYN-ACK validation
 * - TCP ACK
 *
 * Not yet implemented:
 * - TCP data transfer and flow control
 * - Retransmission
 * - Lockdown protocol
 * - Pairing
 */
class UsbMux2TcpConnection(
    private val transport: AppleUsbTransport,
    private val remotePort: Int = 62078,
    private val localPort: Int = 1
) {

    data class ConnectResult(
        val success: Boolean,
        val message: String,
        val localPort: Int,
        val remotePort: Int,
        val rawResponse: String = ""
    )

    companion object {
        private const val PROTOCOL_TCP = 6
        private const val MUX_HEADER_SIZE = 16
        private const val TCP_HEADER_SIZE = 20
        private const val MIN_PACKET_SIZE =
            MUX_HEADER_SIZE + TCP_HEADER_SIZE

        private const val MUX_MAGIC = 0xFEEDFACE
        private const val TCP_SYN = 0x02
        private const val TCP_ACK = 0x10
        private const val TCP_RST = 0x04
        private const val TCP_WINDOW = 512

        private const val MAX_PACKET_SIZE = 16 * 1024
    }

    private var muxTxSequence = 1
    private var muxRxSequence = 0xFFFF

    private var tcpTxSequence = 0L
    private var tcpRxAcknowledgement = 0L

    private var receiveBuffer = ByteArray(0)

    @Volatile
    var isConnected: Boolean = false
        private set

    @Synchronized
    fun connect(timeoutMs: Int = 7000): ConnectResult {

        if (isConnected) {
            return result(true, "TCP connection is already established.")
        }

        require(remotePort in 1..65535)
        require(localPort in 1..65535)
        require(timeoutMs > 0)

        receiveBuffer = ByteArray(0)
        muxTxSequence = 1
        muxRxSequence = 0xFFFF
        tcpTxSequence = 0L
        tcpRxAcknowledgement = 0L

        try {
            // Send TCP SYN.
            sendTcpPacket(
                flags = TCP_SYN,
                sequence = tcpTxSequence,
                acknowledgement = tcpRxAcknowledgement
            )

            val deadline = SystemClock.elapsedRealtime() + timeoutMs

            while (SystemClock.elapsedRealtime() < deadline) {

                val packet = readMuxPacket(deadline)
                    ?: continue

                val protocol = readUInt32BE(packet, 0)

                if (protocol != PROTOCOL_TCP) {
                    continue
                }

                validateMuxHeader(packet)

                // Acknowledge the device's USBMUX packet sequence.
                muxRxSequence = readUInt16BE(packet, 12)

                val tcpOffset = MUX_HEADER_SIZE

                if (packet.size < MIN_PACKET_SIZE) {
                    continue
                }

                val sourcePort =
                    readUInt16BE(packet, tcpOffset)

                val destinationPort =
                    readUInt16BE(packet, tcpOffset + 2)

                val remoteSequence =
                    readUInt32BE(packet, tcpOffset + 4)

                val remoteAcknowledgement =
                    readUInt32BE(packet, tcpOffset + 8)

                val dataOffset =
                    ((packet[tcpOffset + 12].toInt() and 0xFF) ushr 4) * 4

                val flags =
                    packet[tcpOffset + 13].toInt() and 0xFF

                if (dataOffset < TCP_HEADER_SIZE ||
                    packet.size < MUX_HEADER_SIZE + dataOffset
                ) {
                    continue
                }

                if (sourcePort != remotePort ||
                    destinationPort != localPort
                ) {
                    continue
                }

                if ((flags and TCP_RST) != 0) {
                    return result(
                        false,
                        "The iPad rejected the connection with TCP RST.",
                        packet.toHex()
                    )
                }

                val hasSyn = (flags and TCP_SYN) != 0
                val hasAck = (flags and TCP_ACK) != 0

                if (!hasSyn || !hasAck) {
                    continue
                }

                val expectedAcknowledgement =
                    (tcpTxSequence + 1) and 0xFFFFFFFFL

                if (remoteAcknowledgement != expectedAcknowledgement) {
                    return result(
                        false,
                        "Invalid SYN-ACK acknowledgement. " +
                            "Expected $expectedAcknowledgement, " +
                            "received $remoteAcknowledgement.",
                        packet.toHex()
                    )
                }

                // SYN consumes one TCP sequence number.
                tcpTxSequence = expectedAcknowledgement

                // Acknowledge the device's SYN.
                tcpRxAcknowledgement =
                    (remoteSequence + 1) and 0xFFFFFFFFL

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
                        "Lockdown data exchange is not implemented yet.",
                    packet.toHex()
                )
            }

            return result(
                false,
                "Timed out waiting for the iPad's TCP SYN-ACK response."
            )

        } catch (error: Exception) {

            isConnected = false

            return result(
                false,
                "USBMUX2 TCP connection failed: " +
                    "${error.javaClass.simpleName}: ${error.message}"
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

        // TCP header length = 20 bytes.
        tcp[12] = 0x50

        tcp[13] = flags.toByte()

        putUInt16BE(tcp, 14, TCP_WINDOW)

        // Checksum and urgent pointer are zero.
        val packet = ByteArray(MUX_HEADER_SIZE + tcp.size)

        putUInt32BE(packet, 0, PROTOCOL_TCP.toLong())
        putUInt32BE(packet, 4, packet.size.toLong())
        putUInt32BE(packet, 8, MUX_MAGIC.toLong())

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

        muxTxSequence = (muxTxSequence + 1) and 0xFFFF
    }

    private fun readMuxPacket(deadline: Long): ByteArray? {

        while (SystemClock.elapsedRealtime() < deadline) {

            
            if (receiveBuffer.size >= 8) {

                val packetLengthLong =
                    readUInt32BE(receiveBuffer, 4)

                if (packetLengthLong < MIN_PACKET_SIZE.toLong() ||
                    packetLengthLong > MAX_PACKET_SIZE.toLong()
                ) {
                    throw IllegalStateException(
                        "Invalid USBMUX2 packet length: $packetLengthLong"
                    )
                }

                val packetLength = packetLengthLong.toInt()

                if (receiveBuffer.size >= packetLength) {

                    val packet =
                        receiveBuffer.copyOfRange(0, packetLength)

                    receiveBuffer =
                        receiveBuffer.copyOfRange(
                            packetLength,
                            receiveBuffer.size
                        )

                    return packet
                }
            }


            val remaining =
                deadline - SystemClock.elapsedRealtime()

            if (remaining <= 0) break

            val chunk = transport.read(
                maxBytes = 4096,
                timeoutMs = minOf(remaining, 500L).toInt()
                    .coerceAtLeast(1)
            )

            if (chunk != null && chunk.isNotEmpty()) {

                if (receiveBuffer.size + chunk.size > MAX_PACKET_SIZE * 2) {
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
            "USBMUX2 packet is too short."
        }

        val length = readUInt32BE(packet, 4)
        val magic = readUInt32BE(packet, 8)

        check(length == packet.size.toLong()) {
            "USBMUX2 packet length mismatch."
        }

        check(magic == MUX_MAGIC.toLong()) {
            "Invalid USBMUX2 magic value."
        }
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
        // The underlying USB transport owns the physical connection.
        // Closing that transport terminates this pseudo-TCP session.
        isConnected = false
        receiveBuffer = ByteArray(0)
    }
}
