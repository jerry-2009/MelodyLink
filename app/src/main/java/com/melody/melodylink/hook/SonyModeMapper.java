package com.melody.melodylink.hook;

import com.op.bttest.sony.SonyAncMode;

/** Maps Melody's Enco X3 profile indices to the WF-1000XM3 mode set. */
public final class SonyModeMapper {
    private SonyModeMapper() { }

    public static SonyAncMode fromMelodyIndex(int modeIndex) {
        switch (modeIndex) {
            case 0: return SonyAncMode.OFF;
            case 1: return SonyAncMode.AMBIENT_SOUND;
            case 2:
            case 3:
            case 4: return SonyAncMode.NOISE_CANCELING;
            default: return null;
        }
    }

    public static int toMelodyIndex(SonyAncMode mode) {
        if (mode == null) return -1;
        switch (mode) {
            case OFF: return 0;
            case AMBIENT_SOUND: return 1;
            case NOISE_CANCELING: return 2;
            case WIND_NOISE_REDUCTION: return 3;
            default: return -1;
        }
    }
}
