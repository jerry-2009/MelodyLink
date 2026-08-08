package com.melody.melodylink.sony

import com.melody.melodylink.sony.config.DeviceIdentity
import com.melody.melodylink.sony.config.SonyBatteryLayout
import com.melody.melodylink.sony.config.SonyDeviceCatalog
import com.melody.melodylink.sony.config.SonyDeviceConfig
import com.op.bttest.sony.SonyBatteryType
import com.op.bttest.sony.SonyProtocolVersion
import com.op.bttest.sony.SonyUuids
import java.util.UUID

/** Pure connection policy. Bluetooth I/O and coroutine lifecycle stay in the transport facade. */
internal class SonyConnectionPlanner(
    private val catalog: SonyDeviceCatalog?,
) {
    fun resolve(identity: DeviceIdentity): SonyDeviceConfig? = catalog?.findBest(identity)

    fun protocolCandidates(knownUuids: Set<UUID>, config: SonyDeviceConfig?): List<SonyProtocolVersion> {
        if (config != null) {
            return config.protocol.versions.sortedWith(
                compareByDescending<SonyProtocolVersion> { config.protocol.rfcommUuids[it] in knownUuids }
                    .thenByDescending { it == config.protocol.preferredVersion },
            )
        }
        return when {
            SonyUuids.V2 in knownUuids -> listOf(SonyProtocolVersion.V2, SonyProtocolVersion.V1)
            SonyUuids.V1 in knownUuids -> listOf(SonyProtocolVersion.V1, SonyProtocolVersion.V2)
            else -> listOf(SonyProtocolVersion.V2, SonyProtocolVersion.V1)
        }
    }

    fun batteryTypes(config: SonyDeviceConfig?): IntArray = when (config?.battery?.layout) {
        SonyBatteryLayout.LEFT_RIGHT_CASE -> intArrayOf(SonyBatteryType.DUAL, SonyBatteryType.CASE)
        SonyBatteryLayout.LEFT_RIGHT -> intArrayOf(SonyBatteryType.DUAL)
        else -> intArrayOf()
    }
}
