package com.melody.melodylink.hook;

import com.op.bttest.sony.SonyAncState;
import com.op.bttest.sony.SonyBatteryState;

/** Mutable state owned by Sony transport callbacks, separate from hook orchestration. */
final class SonySessionState {
    volatile SonyAncState anc;
    volatile SonyBatteryState battery;

    void clear() {
        anc = null;
        battery = null;
    }

    void acceptAnc(SonyAncState state) {
        anc = state;
    }

    void acceptBattery(SonyBatteryState state) {
        battery = battery == null ? state : battery.merge(state);
    }
}
