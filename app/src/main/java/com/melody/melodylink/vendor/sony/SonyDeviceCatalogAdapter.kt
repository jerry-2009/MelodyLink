package com.melody.melodylink.vendor.sony

import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.DeviceCatalog
import com.melody.melodylink.domain.DeviceIdentity
import com.melody.melodylink.domain.DeviceProfile
import com.melody.melodylink.domain.EarbudsCapabilities
import com.melody.melodylink.domain.Vendor
import com.melody.melodylink.sony.config.SonyConfigRegistry
import com.melody.melodylink.sony.config.DeviceIdentity as SonyDeviceIdentity
import com.op.bttest.sony.SonyAncMode

class SonyDeviceCatalogAdapter(internal val registry: SonyConfigRegistry) : DeviceCatalog {
    override fun findBest(identity: DeviceIdentity): DeviceProfile? {
        val profile = registry.findBest(
            SonyDeviceIdentity(
                bluetoothName = identity.bluetoothName,
            ),
        ) ?: return null
        return DeviceProfile(
            vendor = Vendor.SONY,
            id = profile.id,
            displayName = profile.name,
            capabilities = EarbudsCapabilities(
                ancModes = profile.capabilities.ancModes.mapNotNull { it.toDomain() }.toSet(),
                batteryParts = profile.battery.layout.toDomain(),
                supportsDsee = profile.capabilities.dsee,
                supportsAmbientLevel = profile.capabilities.ambientLevelMax != null,
                supportsVoiceFocus = profile.capabilities.voiceFocus,
            ),
        )
    }

    private fun SonyAncMode.toDomain() = when (this) {
        SonyAncMode.OFF -> AncMode.OFF
        SonyAncMode.NOISE_CANCELING -> AncMode.NOISE_CANCELING
        SonyAncMode.AMBIENT_SOUND -> AncMode.AMBIENT_SOUND
        SonyAncMode.WIND_NOISE_REDUCTION -> null
    }

    private fun com.melody.melodylink.sony.config.SonyBatteryLayout.toDomain() = when (this) {
        com.melody.melodylink.sony.config.SonyBatteryLayout.LEFT_RIGHT_CASE ->
            setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE)
        com.melody.melodylink.sony.config.SonyBatteryLayout.LEFT_RIGHT ->
            setOf(BatteryPart.LEFT, BatteryPart.RIGHT)
        com.melody.melodylink.sony.config.SonyBatteryLayout.SINGLE -> setOf(BatteryPart.SINGLE)
        com.melody.melodylink.sony.config.SonyBatteryLayout.NONE -> emptySet()
    }
}
