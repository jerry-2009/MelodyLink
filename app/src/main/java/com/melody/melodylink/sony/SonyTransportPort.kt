package com.melody.melodylink.sony

import android.bluetooth.BluetoothDevice
import com.melody.melodylink.sony.config.SonyDeviceCatalog
import com.op.bttest.sony.SonyAncMode

/** Minimal command/state surface exposed to target-app hooks. */
interface SonyTransportPort {
    val isConnected: Boolean

    fun setConfigRegistry(registry: SonyDeviceCatalog)
    fun isRegisteredDevice(bluetoothName: String?): Boolean
    fun connect(device: BluetoothDevice)
    fun disconnect()
    fun setAncMode(mode: SonyAncMode, ambientLevel: Int, focusOnVoice: Boolean)
    fun refreshBattery()
}
