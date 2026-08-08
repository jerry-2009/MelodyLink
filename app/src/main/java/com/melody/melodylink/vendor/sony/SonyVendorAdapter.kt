package com.melody.melodylink.vendor.sony

import com.melody.melodylink.domain.DeviceIdentity
import com.melody.melodylink.domain.DeviceMatch
import com.melody.melodylink.domain.Vendor
import com.melody.melodylink.sony.config.SonyConfigRegistry
import com.melody.melodylink.sony.config.DeviceIdentity as SonyDeviceIdentity
import com.melody.melodylink.vendor.VendorAdapter

/** Sony is registered through the same boundary that future vendor adapters use. */
class SonyVendorAdapter(private val catalog: SonyConfigRegistry) : VendorAdapter {
    override val vendor: Vendor = Vendor.SONY

    override fun match(identity: DeviceIdentity): DeviceMatch? {
        val profile = catalog.findBest(
            SonyDeviceIdentity(
                bluetoothName = identity.bluetoothName,
            ),
        ) ?: return null
        return DeviceMatch(Vendor.SONY, profile.id, 100)
    }
}
