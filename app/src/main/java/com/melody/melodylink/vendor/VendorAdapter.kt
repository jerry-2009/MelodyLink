package com.melody.melodylink.vendor

import com.melody.melodylink.domain.DeviceIdentity
import com.melody.melodylink.domain.DeviceMatch
import com.melody.melodylink.domain.Vendor

interface VendorAdapter {
    val vendor: Vendor
    fun match(identity: DeviceIdentity): DeviceMatch?
}

class AdapterRegistry(adapters: List<VendorAdapter> = emptyList()) {
    private val adapters = adapters.toMutableList()

    @Synchronized
    fun register(adapter: VendorAdapter) {
        adapters.removeAll { it.vendor == adapter.vendor }
        adapters += adapter
    }

    fun find(identity: DeviceIdentity): DeviceMatch? = adapters
        .mapNotNull { it.match(identity) }
        .maxWithOrNull(compareBy<DeviceMatch> { it.confidence }.thenBy { it.vendor.name })
}
