package com.op.bttest.sony

class SonyProtocol(
    private val client: SonyRfcommClient,
    private val version: SonyProtocolVersion,
    defaultV1AsmType: Int = SonyPayloads.DEFAULT_V1_ASM_TYPE,
    defaultV2AsmType: Int = SonyPayloads.DEFAULT_V2_ASM_TYPE,
    private val v1WindSupported: Boolean = true,
) {
    private var initialized = false
    private var v1AsmType = defaultV1AsmType
    private var v2AsmType = defaultV2AsmType

    suspend fun initialize(): Boolean {
        val protocolInfoOk = requestProtocolInfo()
        requestFirmwareVersion()
        val supportOk = requestSupportFunctions()
        initialized = protocolInfoOk || supportOk
        return initialized
    }

    suspend fun getAncState(): SonyAncState? {
        ensureInitialized()

        val request = when (version) {
            SonyProtocolVersion.V1 -> byteArrayOf(
                SonyPayloadTypeV1T1.NC_ASM_GET_PARAM.asByte(),
                v1AsmType.asByte(),
            )

            SonyProtocolVersion.V2 -> byteArrayOf(
                SonyPayloadTypeV2T1.NCASM_GET_PARAM.asByte(),
                v2AsmType.asByte(),
            )
        }

        val response = client.sendCommandForResponse(
            messageType = SonyMessageType.COMMAND_1,
            payload = request,
            timeoutMs = 5_000L,
        ) { frame ->
            val opcode = frame.payload.firstOrNull()?.u8()
            frame.messageType == SonyMessageType.COMMAND_1 &&
                opcode != null &&
                opcode in setOf(
                    SonyPayloadTypeV1T1.NC_ASM_RET_PARAM,
                    SonyPayloadTypeV1T1.NC_ASM_NTFY_PARAM,
                )
        } ?: return null

        return when (version) {
            SonyProtocolVersion.V1 -> SonyPayloads.parseV1AmbientState(response.payload)
            SonyProtocolVersion.V2 -> SonyPayloads.parseV2AmbientState(response.payload, v2AsmType)
        }
    }

    suspend fun setAncMode(state: SonyAncState): Boolean {
        ensureInitialized()
        val payload = when (version) {
            SonyProtocolVersion.V1 -> SonyPayloads.buildV1SetAmbientPayload(
                state = state,
                windSupported = v1WindSupported,
            )

            SonyProtocolVersion.V2 -> SonyPayloads.buildV2SetAmbientPayload(
                state = state,
                asmType = v2AsmType,
            )
        } ?: run {
            client.logInfo("Mode ${state.mode} is not supported by current Sony ASM type.")
            return false
        }

        return client.sendCommand(
            messageType = SonyMessageType.COMMAND_1,
            payload = payload,
            ackTimeoutMs = 1_500L,
        )
    }

    suspend fun getBatteryState(
        batteryTypes: IntArray = intArrayOf(SonyBatteryType.DUAL, SonyBatteryType.CASE),
    ): SonyBatteryState? {
        ensureInitialized()
        var state = SonyBatteryState()
        var received = false
        for (type in batteryTypes) {
            val response = client.sendCommandForResponse(
                messageType = SonyMessageType.COMMAND_1,
                payload = SonyPayloads.buildBatteryRequest(version, type),
                timeoutMs = 5_000L,
            ) { frame ->
                frame.messageType == SonyMessageType.COMMAND_1 &&
                    SonyPayloads.parseBatteryState(frame.payload, version, type) != null
            } ?: continue
            val parsed = SonyPayloads.parseBatteryState(response.payload, version, type) ?: continue
            state = state.merge(parsed)
            received = true
        }
        return state.takeIf { received }
    }

    suspend fun getDseeEnabled(): Boolean? {
        ensureInitialized()
        val response = client.sendCommandForResponse(
            messageType = SonyMessageType.COMMAND_1,
            payload = SonyPayloads.buildGetDseePayload(version),
            timeoutMs = 5_000L,
        ) { frame ->
            frame.messageType == SonyMessageType.COMMAND_1 &&
                SonyPayloads.parseDseeState(frame.payload, version) != null
        } ?: return null
        return SonyPayloads.parseDseeState(response.payload, version)
    }

    suspend fun setDseeEnabled(enabled: Boolean): Boolean {
        ensureInitialized()
        // Match the established MelodyPlugin implementation.  Older Sony V1 devices,
        // including WF-1000XM3, acknowledge E8 but frequently do not answer an immediate
        // follow-up E6 query.  The command ACK is therefore the reliable completion signal.
        return client.sendCommand(
            messageType = SonyMessageType.COMMAND_1,
            payload = SonyPayloads.buildSetDseePayload(version, enabled),
        )
    }

    suspend fun getPauseWhenRemovedEnabled(): Boolean? {
        ensureInitialized()
        val response = client.sendCommandForResponse(
            messageType = SonyMessageType.COMMAND_1,
            payload = SonyPayloads.buildGetPauseWhenRemovedPayload(version),
            timeoutMs = 5_000L,
        ) { frame ->
            frame.messageType == SonyMessageType.COMMAND_1 &&
                SonyPayloads.parsePauseWhenRemovedState(frame.payload, version) != null
        } ?: return null
        return SonyPayloads.parsePauseWhenRemovedState(response.payload, version)
    }

    suspend fun setPauseWhenRemovedEnabled(enabled: Boolean): Boolean {
        ensureInitialized()
        if (!client.sendCommand(
                messageType = SonyMessageType.COMMAND_1,
                payload = SonyPayloads.buildSetPauseWhenRemovedPayload(version, enabled),
            )
        ) return false
        return getPauseWhenRemovedEnabled() == enabled
    }

    private suspend fun ensureInitialized() {
        if (!initialized) {
            initialized = initialize()
            if (!initialized) {
                client.logInfo("Protocol init did not receive expected responses; using fallback ASM defaults.")
                initialized = true
            }
        }
    }

    private suspend fun requestProtocolInfo(): Boolean {
        val request = when (version) {
            SonyProtocolVersion.V1 -> byteArrayOf(
                SonyPayloadTypeV1T1.CONNECT_GET_PROTOCOL_INFO.asByte(),
                SonyValueType.FIXED.asByte(),
            )

            SonyProtocolVersion.V2 -> byteArrayOf(
                SonyPayloadTypeV2T1.CONNECT_GET_PROTOCOL_INFO.asByte(),
                SonyValueType.FIXED.asByte(),
            )
        }

        val expected = when (version) {
            SonyProtocolVersion.V1 -> SonyPayloadTypeV1T1.CONNECT_RET_PROTOCOL_INFO
            SonyProtocolVersion.V2 -> SonyPayloadTypeV2T1.CONNECT_RET_PROTOCOL_INFO
        }

        return client.sendCommandForResponse(
            messageType = SonyMessageType.COMMAND_1,
            payload = request,
            timeoutMs = 5_000L,
        ) { frame ->
            frame.messageType == SonyMessageType.COMMAND_1 &&
                frame.payload.firstOrNull()?.u8() == expected
        } != null
    }

    private suspend fun requestFirmwareVersion() {
        val request = when (version) {
            SonyProtocolVersion.V1 -> byteArrayOf(
                SonyPayloadTypeV1T1.CONNECT_GET_DEVICE_INFO.asByte(),
                SonyValueType.FW_VERSION.asByte(),
            )

            SonyProtocolVersion.V2 -> byteArrayOf(
                SonyPayloadTypeV2T1.CONNECT_GET_DEVICE_INFO.asByte(),
                SonyValueType.FW_VERSION.asByte(),
            )
        }

        client.sendCommandForResponse(
            messageType = SonyMessageType.COMMAND_1,
            payload = request,
            timeoutMs = 1_000L,
        ) { frame ->
            val opcode = frame.payload.firstOrNull()?.u8()
            frame.messageType == SonyMessageType.COMMAND_1 &&
                opcode != null &&
                opcode in setOf(
                    SonyPayloadTypeV1T1.CONNECT_RET_DEVICE_INFO,
                    SonyPayloadTypeV2T1.CONNECT_RET_DEVICE_INFO,
                )
        }
    }

    private suspend fun requestSupportFunctions(): Boolean {
        val request = when (version) {
            SonyProtocolVersion.V1 -> byteArrayOf(
                SonyPayloadTypeV1T1.CONNECT_GET_SUPPORT_FUNCTION.asByte(),
                SonyValueType.FIXED.asByte(),
            )

            SonyProtocolVersion.V2 -> byteArrayOf(
                SonyPayloadTypeV2T1.CONNECT_GET_SUPPORT_FUNCTION.asByte(),
                SonyValueType.FIXED.asByte(),
            )
        }

        val expected = when (version) {
            SonyProtocolVersion.V1 -> SonyPayloadTypeV1T1.CONNECT_RET_SUPPORT_FUNCTION
            SonyProtocolVersion.V2 -> SonyPayloadTypeV2T1.CONNECT_RET_SUPPORT_FUNCTION
        }

        val response = client.sendCommandForResponse(
            messageType = SonyMessageType.COMMAND_1,
            payload = request,
            timeoutMs = 5_000L,
        ) { frame ->
            frame.messageType == SonyMessageType.COMMAND_1 &&
                frame.payload.firstOrNull()?.u8() == expected
        } ?: return false

        when (version) {
            SonyProtocolVersion.V1 -> {
                val functions = SonyPayloads.parseV1SupportFunctions(response.payload)
                v1AsmType = SonyPayloads.v1AsmTypeFromSupportFunctions(functions)
            }

            SonyProtocolVersion.V2 -> {
                val functions = SonyPayloads.parseV2SupportFunctions(response.payload)
                v2AsmType = SonyPayloads.v2AsmTypeFromSupportFunctions(functions)
            }
        }
        return true
    }
}

internal object SonyPayloads {
    const val DEFAULT_V1_ASM_TYPE = 0x02
    const val DEFAULT_V2_ASM_TYPE = SonyAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS

    fun parseV1SupportFunctions(payload: ByteArray): Set<Int> {
        if (payload.size < 3 || payload[0].u8() != SonyPayloadTypeV1T1.CONNECT_RET_SUPPORT_FUNCTION) {
            return emptySet()
        }
        val count = payload[2].u8()
        if (payload.size < 3 + count) return emptySet()
        return (0 until count).map { payload[3 + it].u8() }.toSet()
    }

    fun parseV2SupportFunctions(payload: ByteArray): Set<Int> {
        if (payload.size < 3 || payload[0].u8() != SonyPayloadTypeV2T1.CONNECT_RET_SUPPORT_FUNCTION) {
            return emptySet()
        }
        val count = payload[2].u8()
        if (payload.size < 3 + count * 2) return emptySet()
        return (0 until count).map { payload[3 + it * 2].u8() }.toSet()
    }

    fun v1AsmTypeFromSupportFunctions(functions: Set<Int>): Int {
        return when {
            SonyFunctionTypeV1T1.NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE in functions -> 0x02
            SonyFunctionTypeV1T1.AMBIENT_SOUND_MODE in functions -> 0x03
            SonyFunctionTypeV1T1.NOISE_CANCELLING in functions -> 0x01
            else -> DEFAULT_V1_ASM_TYPE
        }
    }

    fun v2AsmTypeFromSupportFunctions(functions: Set<Int>): Int {
        return when {
            SonyFunctionTypeV2T1.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT_NOISE_ADAPTATION in functions ->
                SonyAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA

            SonyFunctionTypeV2T1.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT in functions ->
                SonyAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS

            SonyFunctionTypeV2T1.MODE_NC_ASM_NOISE_CANCELLING_DUAL_AUTO_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT in functions ->
                SonyAsmType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS

            SonyFunctionTypeV2T1.AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT in functions ->
                SonyAsmType.ASM_SEAMLESS

            SonyFunctionTypeV2T1.AMBIENT_SOUND_MODE_ONOFF in functions ->
                SonyAsmType.ASM_ON_OFF

            else -> DEFAULT_V2_ASM_TYPE
        }
    }

    fun buildBatteryRequest(version: SonyProtocolVersion, type: Int): ByteArray = byteArrayOf(
        when (version) {
            SonyProtocolVersion.V1 -> SonyPayloadTypeV1T1.COMMON_GET_BATTERY_LEVEL
            SonyProtocolVersion.V2 -> SonyPayloadTypeV2T1.POWER_GET_STATUS
        }.asByte(),
        type.asByte(),
    )

    fun parseBatteryState(
        payload: ByteArray,
        version: SonyProtocolVersion,
        expectedType: Int,
    ): SonyBatteryState? {
        val opcodes = when (version) {
            SonyProtocolVersion.V1 -> setOf(
                SonyPayloadTypeV1T1.COMMON_RET_BATTERY_LEVEL,
                SonyPayloadTypeV1T1.COMMON_NTFY_BATTERY_LEVEL,
            )
            SonyProtocolVersion.V2 -> setOf(
                SonyPayloadTypeV2T1.POWER_RET_STATUS,
                SonyPayloadTypeV2T1.POWER_NTFY_STATUS,
            )
        }
        if (payload.size < 4 || payload[0].u8() !in opcodes || payload[1].u8() != expectedType) {
            return null
        }

        fun battery(levelIndex: Int, statusIndex: Int, zeroMeansMissing: Boolean): SonyBattery? {
            if (statusIndex >= payload.size) return null
            val level = payload[levelIndex].u8()
            if (zeroMeansMissing && level == 0) return null
            val chargingStatus = payload[statusIndex].u8()
            val charging = when (version) {
                SonyProtocolVersion.V1 -> chargingStatus == 0x01
                SonyProtocolVersion.V2 -> chargingStatus == 0x01 || chargingStatus == 0x03
            }
            return SonyBattery(level.coerceIn(0, 100), charging)
        }

        return when (expectedType) {
            SonyBatteryType.DUAL -> {
                if (payload.size < 6) null else SonyBatteryState(
                    left = battery(2, 3, zeroMeansMissing = true),
                    right = battery(4, 5, zeroMeansMissing = true),
                )
            }
            SonyBatteryType.CASE -> SonyBatteryState(
                case = battery(2, 3, zeroMeansMissing = false),
            )
            else -> null
        }
    }

    fun buildGetDseePayload(version: SonyProtocolVersion): ByteArray = byteArrayOf(
        when (version) {
            SonyProtocolVersion.V1 -> SonyPayloadTypeV1T1.AUDIO_GET_PARAM
            SonyProtocolVersion.V2 -> SonyPayloadTypeV2T1.AUDIO_GET_PARAM
        }.asByte(),
        if (version == SonyProtocolVersion.V1) 0x02 else 0x01,
    )

    fun buildSetDseePayload(version: SonyProtocolVersion, enabled: Boolean): ByteArray = when (version) {
        SonyProtocolVersion.V1 -> byteArrayOf(0xE8.toByte(), 0x02, 0x00, if (enabled) 0x01 else 0x00)
        SonyProtocolVersion.V2 -> byteArrayOf(0xE8.toByte(), 0x01, if (enabled) 0x01 else 0x00)
    }

    fun parseDseeState(payload: ByteArray, version: SonyProtocolVersion): Boolean? {
        val expectedOpcode = when (version) {
            SonyProtocolVersion.V1 -> setOf(SonyPayloadTypeV1T1.AUDIO_RET_PARAM, SonyPayloadTypeV1T1.AUDIO_NTFY_PARAM)
            SonyProtocolVersion.V2 -> setOf(SonyPayloadTypeV2T1.AUDIO_RET_PARAM, SonyPayloadTypeV2T1.AUDIO_NTFY_PARAM)
        }
        if (payload.firstOrNull()?.u8() !in expectedOpcode) return null
        val value = when (version) {
            SonyProtocolVersion.V1 -> if (payload.size == 4 && payload[1].u8() == 0x02 && payload[2].u8() == 0x00) payload[3].u8() else return null
            SonyProtocolVersion.V2 -> if (payload.size == 3 && payload[1].u8() == 0x01) payload[2].u8() else return null
        }
        return value.toBooleanOrNull()
    }

    fun buildGetPauseWhenRemovedPayload(version: SonyProtocolVersion): ByteArray = byteArrayOf(
        0xF6.toByte(),
        if (version == SonyProtocolVersion.V1) 0x03 else 0x01,
    )

    fun buildSetPauseWhenRemovedPayload(version: SonyProtocolVersion, enabled: Boolean): ByteArray = when (version) {
        SonyProtocolVersion.V1 -> byteArrayOf(0xF8.toByte(), 0x03, 0x00, if (enabled) 0x01 else 0x00)
        SonyProtocolVersion.V2 -> byteArrayOf(0xF8.toByte(), 0x01, if (enabled) 0x00 else 0x01)
    }

    fun parsePauseWhenRemovedState(payload: ByteArray, version: SonyProtocolVersion): Boolean? {
        if (payload.firstOrNull()?.u8() !in setOf(0xF7, 0xF9)) return null
        val value = when (version) {
            SonyProtocolVersion.V1 -> if (payload.size == 4 && payload[1].u8() == 0x03 && payload[2].u8() == 0x00) payload[3].u8() else return null
            SonyProtocolVersion.V2 -> if (payload.size == 3 && payload[1].u8() == 0x01) payload[2].u8() else return null
        }.toBooleanOrNull() ?: return null
        return if (version == SonyProtocolVersion.V1) value else !value
    }

    fun buildV1SetAmbientPayload(
        state: SonyAncState,
        windSupported: Boolean,
    ): ByteArray {
        val modeIsOff = state.mode == SonyAncMode.OFF
        val modeIsNoiseCanceling = state.mode == SonyAncMode.NOISE_CANCELING
        val modeIsWind = state.mode == SonyAncMode.WIND_NOISE_REDUCTION
        val modeIsAmbient = state.mode == SonyAncMode.AMBIENT_SOUND

        val modeCode = if (windSupported) {
            when {
                modeIsNoiseCanceling -> 0x02
                modeIsWind -> 0x01
                else -> 0x00
            }
        } else {
            if (modeIsNoiseCanceling) 0x01 else 0x00
        }

        val ambientLevel = if (modeIsOff || modeIsAmbient) {
            state.ambientLevel.coerceIn(1, 20)
        } else {
            0x00
        }

        return byteArrayOf(
            SonyPayloadTypeV1T1.NC_ASM_SET_PARAM.asByte(),
            0x02.asByte(),
            (if (modeIsOff) 0x00 else 0x11).asByte(),
            (if (windSupported) 0x02 else 0x00).asByte(),
            modeCode.asByte(),
            0x01.asByte(),
            (if (state.focusOnVoice) 0x01 else 0x00).asByte(),
            ambientLevel.asByte(),
        )
    }

    fun buildV2SetAmbientPayload(
        state: SonyAncState,
        asmType: Int,
    ): ByteArray? {
        val noNoiseCanceling = asmType == SonyAsmType.ASM_SEAMLESS ||
            asmType == SonyAsmType.ASM_ON_OFF
        val includesWind = asmType == SonyAsmType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS
        val autoAmbientType =
            asmType == SonyAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA

        if (state.mode == SonyAncMode.WIND_NOISE_REDUCTION && !includesWind) {
            return null
        }
        if (state.mode == SonyAncMode.NOISE_CANCELING && noNoiseCanceling) {
            return null
        }

        val payload = mutableListOf(
            SonyPayloadTypeV2T1.NCASM_SET_PARAM,
            asmType,
            0x01,
            if (state.mode == SonyAncMode.OFF) 0x00 else 0x01,
        )

        if (!noNoiseCanceling) {
            payload += if (state.mode == SonyAncMode.AMBIENT_SOUND) 0x01 else 0x00
        }

        if (includesWind) {
            payload += if (state.mode == SonyAncMode.WIND_NOISE_REDUCTION) 0x03 else 0x02
        }

        payload += if (state.focusOnVoice) 0x01 else 0x00
        payload += state.ambientLevel.coerceIn(1, 20)

        if (autoAmbientType) {
            payload += if (state.autoAmbient) 0x01 else 0x00
            payload += state.autoAmbientSensitivity.coerceIn(0, 2)
        }

        return payload.map { it.asByte() }.toByteArray()
    }

    fun parseV1AmbientState(payload: ByteArray): SonyAncState? {
        if (payload.size != 8) return null
        val mode = when (payload[2].u8()) {
            0x00 -> SonyAncMode.OFF
            0x01 -> parseV1EnabledMode(payload[3].u8(), payload[4].u8())
            else -> null
        } ?: return null

        return SonyAncState(
            mode = mode,
            focusOnVoice = payload[6].u8() == 0x01,
            ambientLevel = parseAmbientLevel(payload[7].u8()),
        )
    }

    private fun parseV1EnabledMode(m1: Int, m2: Int): SonyAncMode? {
        return when (m1) {
            0x00 -> if (m2 == 0x00) SonyAncMode.AMBIENT_SOUND else SonyAncMode.NOISE_CANCELING
            0x02 -> when (m2) {
                0x00 -> SonyAncMode.AMBIENT_SOUND
                0x01 -> SonyAncMode.WIND_NOISE_REDUCTION
                else -> SonyAncMode.NOISE_CANCELING
            }

            else -> null
        }
    }

    fun parseV2AmbientState(payload: ByteArray, asmType: Int): SonyAncState? {
        if (payload.size < 6 || payload.size > 9) return null
        if (payload[1].u8() != asmType) return null

        val noNoiseCanceling = asmType == SonyAsmType.ASM_SEAMLESS ||
            asmType == SonyAsmType.ASM_ON_OFF
        val includesWind = asmType == SonyAsmType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS
        val autoAmbientType =
            asmType == SonyAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA

        val mode = if (payload[3].u8() == 0x00) {
            SonyAncMode.OFF
        } else if (includesWind) {
            when (payload[5].u8()) {
                0x03, 0x05 -> SonyAncMode.WIND_NOISE_REDUCTION
                0x02 -> if (payload[4].u8() == 0x00) {
                    SonyAncMode.NOISE_CANCELING
                } else {
                    SonyAncMode.AMBIENT_SOUND
                }

                else -> return null
            }
        } else if (noNoiseCanceling) {
            SonyAncMode.AMBIENT_SOUND
        } else {
            if (payload[4].u8() == 0x00) SonyAncMode.NOISE_CANCELING else SonyAncMode.AMBIENT_SOUND
        }

        var index = payload.size - if (autoAmbientType) 4 else 2
        val focusOnVoice = payload[index].u8() == 0x01
        index += 1
        val ambientLevel = parseAmbientLevel(payload[index].u8())

        var autoAmbient = false
        var sensitivity = 0
        if (autoAmbientType) {
            index += 1
            autoAmbient = payload[index].u8() == 0x01
            index += 1
            sensitivity = payload[index].u8().coerceIn(0, 2)
        }

        return SonyAncState(
            mode = mode,
            focusOnVoice = focusOnVoice,
            ambientLevel = ambientLevel,
            autoAmbient = autoAmbient,
            autoAmbientSensitivity = sensitivity,
        )
    }

    private fun parseAmbientLevel(raw: Int): Int = if (raw in 0..20) raw else 10

    private fun Int.toBooleanOrNull(): Boolean? = when (this) {
        0x00 -> false
        0x01 -> true
        else -> null
    }
}
