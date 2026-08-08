package com.melody.melodylink.hook

import com.melody.melodylink.domain.AncMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MelodyCommandBridgeTest {
    @Test
    fun mapsSupportedMelodyAncIndices() {
        assertEquals(AncMode.OFF, MelodyCommandBridge.ancMode(0))
        assertEquals(AncMode.NOISE_CANCELING, MelodyCommandBridge.ancMode(1))
        assertEquals(AncMode.AMBIENT_SOUND, MelodyCommandBridge.ancMode(2))
    }

    @Test
    fun rejectsUnknownMelodyAncIndices() {
        assertNull(MelodyCommandBridge.ancMode(5))
    }
}
