package com.op.bttest.sony

import java.io.ByteArrayOutputStream

data class SonyFrame(
    val messageType: Int,
    val sequence: Int,
    val payload: ByteArray,
)

object SonyFrameCodec {
    fun encode(messageType: Int, sequence: Int, payload: ByteArray): ByteArray {
        val body = ByteArray(6 + payload.size)
        body[0] = messageType.asByte()
        body[1] = sequence.asByte()
        body[2] = (payload.size ushr 24).asByte()
        body[3] = (payload.size ushr 16).asByte()
        body[4] = (payload.size ushr 8).asByte()
        body[5] = payload.size.asByte()
        payload.copyInto(body, destinationOffset = 6)

        val checksum = checksum(body).asByte()
        val out = ByteArrayOutputStream()
        out.write(SonyFrameConstants.HEADER)
        out.write(escape(body))
        out.write(escape(byteArrayOf(checksum)))
        out.write(SonyFrameConstants.TRAILER)
        return out.toByteArray()
    }

    fun decode(rawFrame: ByteArray): SonyFrame? {
        if (rawFrame.size < 8) return null
        if (rawFrame.first().u8() != SonyFrameConstants.HEADER) return null
        if (rawFrame.last().u8() != SonyFrameConstants.TRAILER) return null

        val unescaped = unescape(rawFrame)
        if (unescaped.size < 8) return null

        val checksumIndex = unescaped.lastIndex - 1
        val actualChecksum = unescaped[checksumIndex].u8()
        val expectedChecksum = checksum(unescaped.copyOfRange(1, checksumIndex))
        if (actualChecksum != expectedChecksum) return null

        val payloadLength =
            (unescaped[3].u8() shl 24) or
                (unescaped[4].u8() shl 16) or
                (unescaped[5].u8() shl 8) or
                unescaped[6].u8()

        val payloadStart = 7
        val payloadEnd = payloadStart + payloadLength
        if (payloadEnd > checksumIndex) return null

        return SonyFrame(
            messageType = unescaped[1].u8(),
            sequence = unescaped[2].u8(),
            payload = unescaped.copyOfRange(payloadStart, payloadEnd),
        )
    }

    fun checksum(bytes: ByteArray): Int {
        var sum = 0
        for (byte in bytes) {
            sum = (sum + byte.u8()) and 0xFF
        }
        return sum
    }

    fun escape(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (byte in bytes) {
            val value = byte.u8()
            if (
                value == SonyFrameConstants.HEADER ||
                value == SonyFrameConstants.TRAILER ||
                value == SonyFrameConstants.ESCAPE
            ) {
                out.write(SonyFrameConstants.ESCAPE)
                out.write(value and SonyFrameConstants.ESCAPE_MASK)
            } else {
                out.write(value)
            }
        }
        return out.toByteArray()
    }

    fun unescape(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < bytes.size) {
            val value = bytes[index].u8()
            if (value == SonyFrameConstants.ESCAPE && index + 1 < bytes.size) {
                index += 1
                out.write(bytes[index].u8() or 0x10)
            } else {
                out.write(value)
            }
            index += 1
        }
        return out.toByteArray()
    }
}
