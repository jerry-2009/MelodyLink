package com.op.bttest.sony

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SonyFrameCodecTest {
    @Test
    fun encodeDecodeRoundTripWithEscapedBytes() {
        val payload = byteArrayOf(
            0x66,
            SonyFrameConstants.HEADER.asByte(),
            SonyFrameConstants.TRAILER.asByte(),
            SonyFrameConstants.ESCAPE.asByte(),
        )

        val encoded = SonyFrameCodec.encode(
            messageType = SonyMessageType.COMMAND_1,
            sequence = 0,
            payload = payload,
        )

        assertEquals(SonyFrameConstants.HEADER, encoded.first().u8())
        assertEquals(SonyFrameConstants.TRAILER, encoded.last().u8())

        val decoded = SonyFrameCodec.decode(encoded)
        assertNotNull(decoded)
        decoded!!
        assertEquals(SonyMessageType.COMMAND_1, decoded.messageType)
        assertEquals(0, decoded.sequence)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun checksumMatchesBudsLinkSumOfBodyBytes() {
        val body = byteArrayOf(
            SonyMessageType.COMMAND_1.asByte(),
            0x00,
            0x00,
            0x00,
            0x00,
            0x02,
            SonyPayloadTypeV2T1.NCASM_GET_PARAM.asByte(),
            SonyAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS.asByte(),
        )

        val expected = body.fold(0) { acc, byte -> (acc + byte.u8()) and 0xFF }
        assertEquals(expected, SonyFrameCodec.checksum(body))
    }
}
