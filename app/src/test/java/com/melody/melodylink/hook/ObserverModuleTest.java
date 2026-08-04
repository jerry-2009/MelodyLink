package com.melody.melodylink.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.op.bttest.sony.SonyAncMode;

import org.junit.Test;

public class ObserverModuleTest {
    @Test public void mapsMelodyNoiseIndicesToSonyModes() {
        assertEquals(SonyAncMode.OFF, SonyModeMapper.fromMelodyIndex(0));
        assertEquals(SonyAncMode.AMBIENT_SOUND, SonyModeMapper.fromMelodyIndex(1));
        assertEquals(SonyAncMode.NOISE_CANCELING, SonyModeMapper.fromMelodyIndex(2));
        assertEquals(SonyAncMode.NOISE_CANCELING, SonyModeMapper.fromMelodyIndex(3));
        assertEquals(SonyAncMode.NOISE_CANCELING, SonyModeMapper.fromMelodyIndex(4));
        assertNull(SonyModeMapper.fromMelodyIndex(5));
        assertEquals(0, SonyModeMapper.toMelodyIndex(SonyAncMode.OFF));
        assertEquals(1, SonyModeMapper.toMelodyIndex(SonyAncMode.AMBIENT_SOUND));
        assertEquals(2, SonyModeMapper.toMelodyIndex(SonyAncMode.NOISE_CANCELING));
    }
}
