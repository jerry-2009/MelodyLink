package com.melody.melodylink.hook

import com.melody.melodylink.sony.config.DeviceIdentity
import com.melody.melodylink.sony.config.SonyConfigRegistry
import com.melody.melodylink.sony.config.SonyDeviceConfig
import com.melody.melodylink.domain.DeviceIdentity as DomainDeviceIdentity
import com.melody.melodylink.vendor.AdapterRegistry
import com.melody.melodylink.vendor.sony.SonyVendorAdapter

/** Holds configured device profiles outside the Xposed hook orchestration class. */
internal class MelodyDeviceBridge {
    @Volatile
    private var registry: SonyConfigRegistry? = null
    private val adapters = AdapterRegistry()

    fun setRegistry(value: SonyConfigRegistry) {
        registry = value
        adapters.register(SonyVendorAdapter(value))
    }

    fun profileForName(name: String?): SonyDeviceConfig? {
        if (name.isNullOrBlank()) return null
        return registry?.findBest(DeviceIdentity(bluetoothName = name))
    }

    fun hasProfiles(): Boolean = registry != null

    fun isRegisteredDevice(bluetoothName: String?): Boolean =
        bluetoothName != null && adapters.find(DomainDeviceIdentity(bluetoothName = bluetoothName)) != null
}
