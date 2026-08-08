package com.melody.melodylink.earbuds

import android.bluetooth.BluetoothDevice
import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.BatteryValue
import com.melody.melodylink.domain.EarbudsCapabilities
import com.melody.melodylink.domain.EarbudsState
import com.melody.melodylink.domain.DeviceCatalog
import com.melody.melodylink.domain.OperationResult
import com.melody.melodylink.sony.config.SonyAdvancedSettingId

interface EarbudsFacade {
    interface Listener {
        fun onConnecting()
        fun onConnected(state: EarbudsState)
        fun onBatteryState(state: EarbudsState)
        fun onSettingState(id: SonyAdvancedSettingId, value: Boolean)
        fun onSettingWriteResult(id: SonyAdvancedSettingId, success: Boolean, value: Boolean?, reason: String)
        fun onAncWriteResult(success: Boolean, state: EarbudsState?, reason: String)
        fun onCommandSessionFinished(reason: String)
        fun onDisconnected()
        fun onFailed(reason: String)
        fun onLog(message: String)
    }

    val isConnected: Boolean
    fun setCatalog(catalog: DeviceCatalog)
    fun isRegisteredDevice(bluetoothName: String?): Boolean
    fun connect(device: BluetoothDevice)
    fun disconnect()
    fun setAncMode(mode: AncMode)
    fun refreshBattery()
    fun readSetting(id: SonyAdvancedSettingId)
    fun writeSetting(id: SonyAdvancedSettingId, value: Boolean)
}

internal fun EarbudsCapabilities.withAncMode(mode: AncMode?): EarbudsState =
    EarbudsState(capabilities = this, ancMode = mode)

internal fun batteryState(
    capabilities: EarbudsCapabilities,
    left: BatteryValue?,
    right: BatteryValue?,
    case: BatteryValue?,
): EarbudsState {
    val values = buildMap {
        left?.let { put(BatteryPart.LEFT, it) }
        right?.let { put(BatteryPart.RIGHT, it) }
        case?.let { put(BatteryPart.CASE, it) }
    }
    return EarbudsState(capabilities = capabilities, battery = values)
}
