package com.melody.melodylink.domain

enum class Vendor { SONY, BOSE, HUAWEI, XIAOMI, UNKNOWN }

enum class AncMode { OFF, NOISE_CANCELING, AMBIENT_SOUND, TRANSPARENCY }

enum class BatteryPart { LEFT, RIGHT, CASE, SINGLE }

data class BatteryValue(val percent: Int, val charging: Boolean = false)

data class DeviceIdentity(
    val bluetoothName: String? = null,
    val address: String? = null,
    val serviceUuids: Set<String> = emptySet(),
    val manufacturerId: Int? = null,
)

data class EarbudsCapabilities(
    val ancModes: Set<AncMode> = emptySet(),
    val batteryParts: Set<BatteryPart> = emptySet(),
    val supportsAmbientLevel: Boolean = false,
    val supportsVoiceFocus: Boolean = false,
    val supportsDsee: Boolean = false,
)

data class EarbudsState(
    val capabilities: EarbudsCapabilities,
    val ancMode: AncMode? = null,
    val battery: Map<BatteryPart, BatteryValue> = emptyMap(),
)

sealed interface OperationResult {
    data object Success : OperationResult
    data class Failed(val reason: String) : OperationResult
    data class Unsupported(val reason: String) : OperationResult
}

interface EarbudsController {
    suspend fun connect(): Result<Unit>
    suspend fun readState(): Result<EarbudsState>
    suspend fun setAnc(mode: AncMode): OperationResult
    suspend fun refreshBattery(): OperationResult
    suspend fun disconnect()
}

data class DeviceMatch(val vendor: Vendor, val profileId: String, val confidence: Int)

data class DeviceProfile(
    val vendor: Vendor,
    val id: String,
    val displayName: String,
    val capabilities: EarbudsCapabilities,
)

interface DeviceCatalog {
    fun findBest(identity: DeviceIdentity): DeviceProfile?
}
