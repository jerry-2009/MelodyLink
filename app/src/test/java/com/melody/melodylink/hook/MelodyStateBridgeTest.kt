package com.melody.melodylink.hook

import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.BatteryValue
import com.melody.melodylink.domain.EarbudsCapabilities
import com.melody.melodylink.domain.EarbudsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MelodyStateBridgeTest {
    private val state = EarbudsState(
        capabilities = EarbudsCapabilities(),
        ancMode = AncMode.TRANSPARENCY,
        battery = mapOf(BatteryPart.LEFT to BatteryValue(87)),
    )

    @Test
    fun mapsDomainAncModeToMelodyIndex() {
        assertEquals(3, MelodyStateBridge.ancModeIndex(state))
        assertEquals(-1, MelodyStateBridge.ancModeIndex(null as EarbudsState?))
    }

    @Test
    fun readsOnlyRequestedBatteryPart() {
        assertEquals(87, MelodyStateBridge.batteryPercent(state, BatteryPart.LEFT))
        assertNull(MelodyStateBridge.batteryPercent(state, BatteryPart.RIGHT))
    }
}
