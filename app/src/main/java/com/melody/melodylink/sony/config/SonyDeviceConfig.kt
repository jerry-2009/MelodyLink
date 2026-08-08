package com.melody.melodylink.sony.config

import com.op.bttest.sony.SonyAncMode
import com.op.bttest.sony.SonyProtocolVersion
import java.util.UUID

const val SONY_CONFIG_SCHEMA_VERSION = 1

enum class SonySupportLevel {
    UNSUPPORTED,
    READ_ONLY,
    EXPERIMENTAL,
    SUPPORTED,
    ;

    val permitsWrites: Boolean
        get() = this == SUPPORTED
}

enum class SonyBatteryLayout {
    NONE,
    SINGLE,
    LEFT_RIGHT,
    LEFT_RIGHT_CASE,
}

enum class SonyAdvancedSettingId {
    DSEE,
    PAUSE_WHEN_REMOVED,
}

enum class SonyAdvancedSettingType {
    SWITCH,
}

data class SonyAdvancedSettingConfig(
    val id: SonyAdvancedSettingId,
    val type: SonyAdvancedSettingType,
    val order: Int,
)

data class DeviceIdentity(
    val bluetoothName: String? = null,
    val modalias: String? = null,
    val deviceInfoModel: String? = null,
    val firmwareVersion: String? = null,
)

data class SonyMatchRules(
    val exactNames: List<String>,
    val namePatterns: List<String>,
    val modaliasPrefixes: List<String>,
    val deviceInfoModels: List<String>,
)

data class SonyProtocolConfig(
    val versions: List<SonyProtocolVersion>,
    val preferredVersion: SonyProtocolVersion,
    val rfcommUuids: Map<SonyProtocolVersion, UUID>,
    val handshakeRequired: Boolean,
    val defaultV1AsmType: Int?,
    val defaultV2AsmType: Int?,
)

data class SonyBatteryConfig(
    val layout: SonyBatteryLayout,
    val supportsChargingState: Boolean,
)

data class SonyCapabilityConfig(
    val ancModes: Set<SonyAncMode>,
    val ambientLevelMin: Int?,
    val ambientLevelMax: Int?,
    val voiceFocus: Boolean,
    val autoAmbient: Boolean,
    val dsee: Boolean,
    val equalizerBands: Int,
    val speakToChat: Boolean,
    val quickAccess: Boolean,
    val extraFeatures: Set<String>,
)

data class SonyControlConfig(
    val buttonModes: List<String>,
    val pauseWhenRemoved: Boolean,
    val automaticPowerOffWhenRemoved: Boolean,
    val automaticPowerOffByTime: Boolean,
    val powerOffFromPhone: Boolean,
)

data class SonyQuirks(
    val v1WindSupported: Boolean,
    val requiresAncReadAfterWrite: Boolean,
)

data class SonyDeviceConfig(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val family: String,
    val modelType: String,
    val image: String?,
    val match: SonyMatchRules,
    val protocol: SonyProtocolConfig,
    val battery: SonyBatteryConfig,
    val capabilities: SonyCapabilityConfig,
    val controls: SonyControlConfig,
    val quirks: SonyQuirks,
    val supportLevel: SonySupportLevel,
    val advancedSettings: List<SonyAdvancedSettingConfig> = emptyList(),
) {
    fun supportsAncMode(mode: SonyAncMode): Boolean = mode in capabilities.ancModes

    fun permitsAncWrites(
        mode: SonyAncMode,
        experimentalWritesEnabled: Boolean = false,
    ): Boolean = supportsAncMode(mode) && (
        supportLevel.permitsWrites ||
            (supportLevel == SonySupportLevel.EXPERIMENTAL && experimentalWritesEnabled)
        )
}

data class EarbudState(
    val ancMode: SonyAncMode? = null,
    val batteryLayout: SonyBatteryLayout = SonyBatteryLayout.NONE,
)

interface SonyDeviceController {
    suspend fun connect(identity: DeviceIdentity): Result<Unit>
    suspend fun readState(): Result<EarbudState>
    suspend fun setAnc(mode: SonyAncMode): Result<Unit>
    suspend fun disconnect()
}
