package com.melody.melodylink.vendor.sony

import android.bluetooth.BluetoothDevice
import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.BatteryValue
import com.melody.melodylink.domain.EarbudsCapabilities
import com.melody.melodylink.domain.EarbudsState
import com.melody.melodylink.domain.DeviceCatalog
import com.melody.melodylink.earbuds.EarbudsFacade
import com.melody.melodylink.sony.SonyTransportAdapter
import com.melody.melodylink.sony.config.SonyConfigRegistry
import com.melody.melodylink.sony.config.SonyAdvancedSettingId
import com.op.bttest.sony.SonyAncMode
import com.op.bttest.sony.SonyAncState
import com.op.bttest.sony.SonyBatteryState

/** Converts the Sony-specific transport callback surface into domain events. */
class SonyEarbudsFacade @JvmOverloads constructor(
    listener: EarbudsFacade.Listener,
    registry: SonyConfigRegistry = SonyConfigRegistry.empty(),
) : EarbudsFacade {
    private val capabilities = EarbudsCapabilities(
        ancModes = setOf(AncMode.OFF, AncMode.NOISE_CANCELING, AncMode.AMBIENT_SOUND),
        batteryParts = setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE),
    )

    private val transport = SonyTransportAdapter(object : SonyTransportAdapter.Listener {
        override fun onConnecting() = listener.onConnecting()

        override fun onConnected(state: SonyAncState) = listener.onConnected(state.toDomain())

        override fun onBatteryState(state: SonyBatteryState) = listener.onBatteryState(state.toDomain())

        override fun onSettingState(id: SonyAdvancedSettingId, value: Boolean) = listener.onSettingState(id, value)

        override fun onSettingWriteResult(id: SonyAdvancedSettingId, success: Boolean, value: Boolean?, reason: String) =
            listener.onSettingWriteResult(id, success, value, reason)

        override fun onAncWriteResult(success: Boolean, state: SonyAncState?, reason: String) =
            listener.onAncWriteResult(success, state?.toDomain(), reason)

        override fun onCommandSessionFinished(reason: String) = listener.onCommandSessionFinished(reason)

        override fun onDisconnected() = listener.onDisconnected()

        override fun onFailed(reason: String) = listener.onFailed(reason)

        override fun onLog(message: String) = listener.onLog(message)
    }, registry)

    override val isConnected: Boolean
        get() = transport.isConnected

    override fun setCatalog(catalog: DeviceCatalog) {
        require(catalog is SonyDeviceCatalogAdapter) { "Sony facade requires a Sony device catalog" }
        transport.setConfigRegistry(catalog.registry)
    }

    override fun isRegisteredDevice(bluetoothName: String?): Boolean =
        transport.isRegisteredDevice(bluetoothName)

    override fun connect(device: BluetoothDevice) = transport.connect(device)

    override fun disconnect() = transport.disconnect()

    override fun setAncMode(mode: AncMode) = transport.setAncMode(mode.toSony(), 10, false)

    override fun refreshBattery() = transport.refreshBattery()

    override fun readSetting(id: SonyAdvancedSettingId) = transport.readSetting(id)

    override fun writeSetting(id: SonyAdvancedSettingId, value: Boolean) = transport.writeSetting(id, value)

    private fun SonyAncState.toDomain() = EarbudsState(
        capabilities = capabilities,
        ancMode = mode.toDomain(),
    )

    private fun SonyBatteryState.toDomain() = EarbudsState(
        capabilities = capabilities,
        battery = buildMap {
            left?.let { put(BatteryPart.LEFT, BatteryValue(it.percent, it.charging)) }
            right?.let { put(BatteryPart.RIGHT, BatteryValue(it.percent, it.charging)) }
            case?.let { put(BatteryPart.CASE, BatteryValue(it.percent, it.charging)) }
        },
    )

    private fun SonyAncMode.toDomain() = when (this) {
        SonyAncMode.OFF -> AncMode.OFF
        SonyAncMode.NOISE_CANCELING -> AncMode.NOISE_CANCELING
        SonyAncMode.AMBIENT_SOUND -> AncMode.AMBIENT_SOUND
        SonyAncMode.WIND_NOISE_REDUCTION -> AncMode.TRANSPARENCY
    }

    private fun AncMode.toSony() = when (this) {
        AncMode.OFF -> SonyAncMode.OFF
        AncMode.NOISE_CANCELING -> SonyAncMode.NOISE_CANCELING
        AncMode.AMBIENT_SOUND, AncMode.TRANSPARENCY -> SonyAncMode.AMBIENT_SOUND
    }
}
