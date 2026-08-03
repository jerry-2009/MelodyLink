package com.melody.melodylink.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceProfileMapperTest {
    @Test public void recognizesOnlyExactXm3Name() {
        assertTrue(DeviceProfileMapper.isWf1000Xm3("WF-1000XM3"));
        assertFalse(DeviceProfileMapper.isWf1000Xm3("WF-1000XM4"));
        assertFalse(DeviceProfileMapper.isWf1000Xm3(null));
    }
}
