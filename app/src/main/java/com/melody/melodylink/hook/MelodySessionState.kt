package com.melody.melodylink.hook

import com.melody.melodylink.domain.EarbudsState

internal class MelodySessionState {
    @Volatile var anc: EarbudsState? = null
        private set
    @Volatile var battery: EarbudsState? = null
        private set

    fun acceptAnc(state: EarbudsState) { anc = state }
    fun acceptBattery(state: EarbudsState) { battery = state }
    fun clear() { anc = null; battery = null }
}
