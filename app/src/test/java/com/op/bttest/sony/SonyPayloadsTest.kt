package com.op.bttest.sony

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SonyPayloadsTest {
    @Test
    fun v1BuildsNoiseCancelingPayloadWithWindCapability() {
        val payload = SonyPayloads.buildV1SetAmbientPayload(
            state = SonyAncState(
                mode = SonyAncMode.NOISE_CANCELING,
                ambientLevel = 10,
                focusOnVoice = false,
            ),
            windSupported = true,
        )

        assertArrayEquals(
            byteArrayOf(0x68.asByte(), 0x02, 0x11, 0x02, 0x02, 0x01, 0x00, 0x00),
            payload,
        )
    }

    @Test
    fun v1BuildsAmbientPayloadWithLevelAndFocus() {
        val payload = SonyPayloads.buildV1SetAmbientPayload(
            state = SonyAncState(
                mode = SonyAncMode.AMBIENT_SOUND,
                ambientLevel = 14,
                focusOnVoice = true,
            ),
            windSupported = true,
        )

        assertArrayEquals(
            byteArrayOf(0x68.asByte(), 0x02, 0x11, 0x02, 0x00, 0x01, 0x01, 0x0E),
            payload,
        )
    }

    @Test
    fun v2BuildsDualModeNoiseCancelingPayload() {
        val payload = SonyPayloads.buildV2SetAmbientPayload(
            state = SonyAncState(
                mode = SonyAncMode.NOISE_CANCELING,
                ambientLevel = 10,
                focusOnVoice = false,
            ),
            asmType = SonyAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        )

        assertArrayEquals(
            byteArrayOf(0x68.asByte(), 0x17, 0x01, 0x01, 0x00, 0x00, 0x0A),
            payload!!,
        )
    }

    @Test
    fun v2RejectsWindWhenAsmTypeDoesNotSupportWind() {
        val payload = SonyPayloads.buildV2SetAmbientPayload(
            state = SonyAncState(
                mode = SonyAncMode.WIND_NOISE_REDUCTION,
                ambientLevel = 10,
                focusOnVoice = false,
            ),
            asmType = SonyAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        )

        assertNull(payload)
    }

    @Test
    fun v2RejectsNoiseCancelingForAmbientOnlyAsmType() {
        val payload = SonyPayloads.buildV2SetAmbientPayload(
            state = SonyAncState(
                mode = SonyAncMode.NOISE_CANCELING,
                ambientLevel = 10,
                focusOnVoice = false,
            ),
            asmType = SonyAsmType.ASM_SEAMLESS,
        )

        assertNull(payload)
    }

    @Test
    fun v2BuildsWindPayloadWhenAsmTypeSupportsWind() {
        val payload = SonyPayloads.buildV2SetAmbientPayload(
            state = SonyAncState(
                mode = SonyAncMode.WIND_NOISE_REDUCTION,
                ambientLevel = 10,
                focusOnVoice = false,
            ),
            asmType = SonyAsmType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        )

        assertArrayEquals(
            byteArrayOf(0x68.asByte(), 0x15, 0x01, 0x01, 0x00, 0x03, 0x00, 0x0A),
            payload!!,
        )
    }

    @Test
    fun parsesV2AmbientState() {
        val state = SonyPayloads.parseV2AmbientState(
            payload = byteArrayOf(0x67, 0x17, 0x00, 0x01, 0x01, 0x01, 0x0C),
            asmType = SonyAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        )

        assertEquals(SonyAncMode.AMBIENT_SOUND, state?.mode)
        assertEquals(true, state?.focusOnVoice)
        assertEquals(12, state?.ambientLevel)
    }

    @Test
    fun buildsV1BatteryRequest() {
        assertArrayEquals(
            byteArrayOf(0x10, 0x01),
            SonyPayloads.buildBatteryRequest(SonyProtocolVersion.V1, SonyBatteryType.DUAL),
        )
    }

    @Test
    fun buildsV2BatteryRequest() {
        assertArrayEquals(
            byteArrayOf(0x22, 0x02),
            SonyPayloads.buildBatteryRequest(SonyProtocolVersion.V2, SonyBatteryType.CASE),
        )
    }

    @Test
    fun parsesV1DualBatteryWithChargingLeftEar() {
        val state = SonyPayloads.parseBatteryState(
            byteArrayOf(0x11, 0x01, 76, 0x01, 54, 0x00),
            SonyProtocolVersion.V1,
            SonyBatteryType.DUAL,
        )

        assertEquals(76, state?.left?.percent)
        assertEquals(true, state?.left?.charging)
        assertEquals(54, state?.right?.percent)
        assertEquals(false, state?.right?.charging)
    }

    @Test
    fun parsesV2BatteryNotificationAndTreatsChargedAsCharging() {
        val state = SonyPayloads.parseBatteryState(
            byteArrayOf(0x25, 0x02, 100.toByte(), 0x03),
            SonyProtocolVersion.V2,
            SonyBatteryType.CASE,
        )

        assertEquals(100, state?.case?.percent)
        assertEquals(true, state?.case?.charging)
    }

    @Test
    fun preservesCaseZeroButSkipsMissingEarbudZero() {
        val ears = SonyPayloads.parseBatteryState(
            byteArrayOf(0x23, 0x01, 0x00, 0x00, 42, 0x01),
            SonyProtocolVersion.V2,
            SonyBatteryType.DUAL,
        )
        val case = SonyPayloads.parseBatteryState(
            byteArrayOf(0x23, 0x02, 0x00, 0x00),
            SonyProtocolVersion.V2,
            SonyBatteryType.CASE,
        )

        assertNull(ears?.left)
        assertEquals(42, ears?.right?.percent)
        assertEquals(0, case?.case?.percent)
    }

    @Test
    fun rejectsMalformedBatteryPayload() {
        assertNull(
            SonyPayloads.parseBatteryState(
                byteArrayOf(0x23, 0x01, 50, 0x00),
                SonyProtocolVersion.V2,
                SonyBatteryType.DUAL,
            ),
        )
    }

    @Test
    fun buildsAndParsesDseeForBothProtocolVersions() {
        assertArrayEquals(byteArrayOf(0xE6.toByte(), 0x02), SonyPayloads.buildGetDseePayload(SonyProtocolVersion.V1))
        assertArrayEquals(byteArrayOf(0xE8.toByte(), 0x02, 0x00, 0x01), SonyPayloads.buildSetDseePayload(SonyProtocolVersion.V1, true))
        assertEquals(true, SonyPayloads.parseDseeState(byteArrayOf(0xE7.toByte(), 0x02, 0x00, 0x01), SonyProtocolVersion.V1))
        assertArrayEquals(byteArrayOf(0xE6.toByte(), 0x01), SonyPayloads.buildGetDseePayload(SonyProtocolVersion.V2))
        assertArrayEquals(byteArrayOf(0xE8.toByte(), 0x01, 0x00), SonyPayloads.buildSetDseePayload(SonyProtocolVersion.V2, false))
        assertEquals(false, SonyPayloads.parseDseeState(byteArrayOf(0xE9.toByte(), 0x01, 0x00), SonyProtocolVersion.V2))
    }

    @Test
    fun buildsAndParsesPauseWhenRemovedForBothProtocolVersions() {
        assertArrayEquals(byteArrayOf(0xF6.toByte(), 0x03), SonyPayloads.buildGetPauseWhenRemovedPayload(SonyProtocolVersion.V1))
        assertArrayEquals(byteArrayOf(0xF8.toByte(), 0x03, 0x00, 0x01), SonyPayloads.buildSetPauseWhenRemovedPayload(SonyProtocolVersion.V1, true))
        assertEquals(true, SonyPayloads.parsePauseWhenRemovedState(byteArrayOf(0xF7.toByte(), 0x03, 0x00, 0x01), SonyProtocolVersion.V1))
        assertArrayEquals(byteArrayOf(0xF6.toByte(), 0x01), SonyPayloads.buildGetPauseWhenRemovedPayload(SonyProtocolVersion.V2))
        assertArrayEquals(byteArrayOf(0xF8.toByte(), 0x01, 0x01), SonyPayloads.buildSetPauseWhenRemovedPayload(SonyProtocolVersion.V2, false))
        assertEquals(true, SonyPayloads.parsePauseWhenRemovedState(byteArrayOf(0xF9.toByte(), 0x01, 0x00), SonyProtocolVersion.V2))
        assertEquals(false, SonyPayloads.parsePauseWhenRemovedState(byteArrayOf(0xF7.toByte(), 0x01, 0x01), SonyProtocolVersion.V2))
    }
}
