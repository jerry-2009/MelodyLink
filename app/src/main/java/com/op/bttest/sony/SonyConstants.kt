package com.op.bttest.sony

import android.bluetooth.BluetoothDevice
import java.util.UUID

enum class SonyProtocolVersion {
    V1,
    V2,
}

enum class SonyAncMode {
    OFF,
    NOISE_CANCELING,
    AMBIENT_SOUND,
    WIND_NOISE_REDUCTION,
}

data class SonyAncState(
    val mode: SonyAncMode,
    val ambientLevel: Int,
    val focusOnVoice: Boolean,
    val autoAmbient: Boolean = false,
    val autoAmbientSensitivity: Int = 0,
)

data class SonyBattery(
    val percent: Int,
    val charging: Boolean,
)

data class SonyBatteryState(
    val left: SonyBattery? = null,
    val right: SonyBattery? = null,
    val case: SonyBattery? = null,
) {
    fun merge(other: SonyBatteryState): SonyBatteryState = SonyBatteryState(
        left = other.left ?: left,
        right = other.right ?: right,
        case = other.case ?: case,
    )
}

interface SonyAncController {
    suspend fun connect(device: BluetoothDevice, version: SonyProtocolVersion): Boolean
    suspend fun disconnect()
    suspend fun getAncState(): SonyAncState?
    suspend fun setAncMode(state: SonyAncState): Boolean
}

data class SonyLogEntry(
    val direction: Direction,
    val message: String,
    val bytes: ByteArray? = null,
) {
    enum class Direction {
        INFO,
        TX,
        RX,
        ERROR,
    }
}

object SonyUuids {
    val V1: UUID = UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba")
    val V2: UUID = UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2")

    fun forVersion(version: SonyProtocolVersion): UUID {
        return when (version) {
            SonyProtocolVersion.V1 -> V1
            SonyProtocolVersion.V2 -> V2
        }
    }
}

object SonyFrameConstants {
    const val HEADER = 0x3E
    const val TRAILER = 0x3C
    const val ESCAPE = 0x3D
    const val ESCAPE_MASK = 0xEF
}

object SonyMessageType {
    const val ACK = 0x01
    const val COMMAND_1 = 0x0C
    const val COMMAND_2 = 0x0E
}

object SonyValueType {
    const val FIXED = 0x00
    const val FW_VERSION = 0x02
}

object SonyPayloadTypeV1T1 {
    const val CONNECT_GET_PROTOCOL_INFO = 0x00
    const val CONNECT_RET_PROTOCOL_INFO = 0x01
    const val CONNECT_GET_DEVICE_INFO = 0x04
    const val CONNECT_RET_DEVICE_INFO = 0x05
    const val CONNECT_GET_SUPPORT_FUNCTION = 0x06
    const val CONNECT_RET_SUPPORT_FUNCTION = 0x07
    const val COMMON_GET_BATTERY_LEVEL = 0x10
    const val COMMON_RET_BATTERY_LEVEL = 0x11
    const val COMMON_NTFY_BATTERY_LEVEL = 0x13
    const val NC_ASM_GET_PARAM = 0x66
    const val NC_ASM_RET_PARAM = 0x67
    const val NC_ASM_SET_PARAM = 0x68
    const val NC_ASM_NTFY_PARAM = 0x69
    const val AUDIO_GET_PARAM = 0xE6
    const val AUDIO_RET_PARAM = 0xE7
    const val AUDIO_SET_PARAM = 0xE8
    const val AUDIO_NTFY_PARAM = 0xE9
    const val SYSTEM_GET_PARAM = 0xF2
    const val SYSTEM_RET_PARAM = 0xF3
    const val SYSTEM_SET_PARAM = 0xF4
    const val SYSTEM_NTFY_PARAM = 0xF5
}

object SonyPayloadTypeV2T1 {
    const val CONNECT_GET_PROTOCOL_INFO = 0x00
    const val CONNECT_RET_PROTOCOL_INFO = 0x01
    const val CONNECT_GET_DEVICE_INFO = 0x04
    const val CONNECT_RET_DEVICE_INFO = 0x05
    const val CONNECT_GET_SUPPORT_FUNCTION = 0x06
    const val CONNECT_RET_SUPPORT_FUNCTION = 0x07
    const val POWER_GET_STATUS = 0x22
    const val POWER_RET_STATUS = 0x23
    const val POWER_NTFY_STATUS = 0x25
    const val NCASM_GET_PARAM = 0x66
    const val NCASM_RET_PARAM = 0x67
    const val NCASM_SET_PARAM = 0x68
    const val NCASM_NTFY_PARAM = 0x69
    const val AUDIO_GET_PARAM = 0xE6
    const val AUDIO_RET_PARAM = 0xE7
    const val AUDIO_SET_PARAM = 0xE8
    const val AUDIO_NTFY_PARAM = 0xE9
    const val SYSTEM_GET_PARAM = 0xF2
    const val SYSTEM_RET_PARAM = 0xF3
    const val SYSTEM_SET_PARAM = 0xF4
    const val SYSTEM_NTFY_PARAM = 0xF5
}

object SonyBatteryType {
    const val DUAL = 0x01
    const val CASE = 0x02
}

object SonyFunctionTypeV1T1 {
    const val NOISE_CANCELLING = 0x61
    const val NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE = 0x62
    const val AMBIENT_SOUND_MODE = 0x63
}

object SonyFunctionTypeV2T1 {
    const val AMBIENT_SOUND_MODE_ONOFF = 0x66
    const val AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT = 0x67
    const val MODE_NC_ASM_NOISE_CANCELLING_DUAL_AUTO_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT = 0x68
    const val MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT = 0x6B
    const val MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT_NOISE_ADAPTATION = 0x6D
}

object SonyAsmType {
    const val MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS = 0x15
    const val MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS = 0x17
    const val MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA = 0x19
    const val ASM_ON_OFF = 0x21
    const val ASM_SEAMLESS = 0x22
}

internal fun Int.asByte(): Byte = toByte()

internal fun Byte.u8(): Int = toInt() and 0xFF

fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it.u8()) }
