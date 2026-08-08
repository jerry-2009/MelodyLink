package com.melody.melodylink.hook

import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.EarbudsState

/** Host-facing conversion kept independent from vendor protocol models. */
object MelodyStateBridge {
    fun ancModeIndex(state: EarbudsState?): Int = when (state?.ancMode) {
        AncMode.OFF -> 0
        AncMode.NOISE_CANCELING -> 1
        AncMode.AMBIENT_SOUND -> 2
        AncMode.TRANSPARENCY -> 3
        null -> -1
    }

    fun batteryPercent(state: EarbudsState?, part: BatteryPart): Int? =
        state?.battery?.get(part)?.percent
}
