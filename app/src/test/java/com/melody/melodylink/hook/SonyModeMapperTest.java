package com.melody.melodylink.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.melody.melodylink.vendor.sony.SonyAncModeMapper;
import com.op.bttest.sony.SonyAncMode;

import org.junit.Test;

public class SonyModeMapperTest {
    @Test public void mapsMelodyNoiseIndicesToSonyModes() {
        assertEquals(SonyAncMode.OFF, SonyAncModeMapper.INSTANCE.toSony(MelodyCommandBridge.INSTANCE.ancMode(0)));
        assertEquals(SonyAncMode.NOISE_CANCELING, SonyAncModeMapper.INSTANCE.toSony(MelodyCommandBridge.INSTANCE.ancMode(1)));
        assertEquals(SonyAncMode.AMBIENT_SOUND, SonyAncModeMapper.INSTANCE.toSony(MelodyCommandBridge.INSTANCE.ancMode(2)));
        assertEquals(SonyAncMode.NOISE_CANCELING, SonyAncModeMapper.INSTANCE.toSony(MelodyCommandBridge.INSTANCE.ancMode(3)));
        assertEquals(SonyAncMode.NOISE_CANCELING, SonyAncModeMapper.INSTANCE.toSony(MelodyCommandBridge.INSTANCE.ancMode(4)));
        assertNull(MelodyCommandBridge.INSTANCE.ancMode(5));
    }
}
