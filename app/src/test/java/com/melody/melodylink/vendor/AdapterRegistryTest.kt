package com.melody.melodylink.vendor

import com.melody.melodylink.domain.DeviceIdentity
import com.melody.melodylink.domain.DeviceMatch
import com.melody.melodylink.domain.Vendor
import org.junit.Assert.assertEquals
import org.junit.Test

class AdapterRegistryTest {
    @Test
    fun selectsHighestConfidenceMatch() {
        val registry = AdapterRegistry(
            listOf(
                object : VendorAdapter {
                    override val vendor = Vendor.UNKNOWN
                    override fun match(identity: DeviceIdentity) = DeviceMatch(vendor, "weak", 10)
                },
                object : VendorAdapter {
                    override val vendor = Vendor.BOSE
                    override fun match(identity: DeviceIdentity) = DeviceMatch(vendor, "strong", 50)
                },
            ),
        )

        assertEquals("strong", registry.find(DeviceIdentity("headset"))?.profileId)
    }

    @Test
    fun laterRegistrationReplacesSameVendor() {
        val registry = AdapterRegistry()
        registry.register(object : VendorAdapter {
            override val vendor = Vendor.SONY
            override fun match(identity: DeviceIdentity) = DeviceMatch(vendor, "old", 10)
        })
        registry.register(object : VendorAdapter {
            override val vendor = Vendor.SONY
            override fun match(identity: DeviceIdentity) = DeviceMatch(vendor, "new", 20)
        })

        assertEquals("new", registry.find(DeviceIdentity())?.profileId)
    }
}
