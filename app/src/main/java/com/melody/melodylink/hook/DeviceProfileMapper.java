package com.melody.melodylink.hook;

/** Stable, Android-independent identity checks used by the whitelist hook. */
public final class DeviceProfileMapper {
    public static final String SONY_WF_1000XM3_PROFILE_ID = "067410";
    public static final String SONY_WF_1000XM3_PROFILE_NAME = "OPPO Enco X3";
    private static final int WF_1000XM3_NAME_HASH = 0x46f63221;

    private DeviceProfileMapper() { }

    public static boolean isWf1000Xm3(String bluetoothName) {
        return bluetoothName != null && bluetoothName.hashCode() == WF_1000XM3_NAME_HASH
                && "WF-1000XM3".equals(bluetoothName);
    }
}
