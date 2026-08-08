package com.melody.melodylink.vendor.sony

import com.melody.melodylink.domain.AncMode
import com.op.bttest.sony.SonyAncMode

object SonyAncModeMapper {
    fun toSony(mode: AncMode): SonyAncMode = when (mode) {
        AncMode.OFF -> SonyAncMode.OFF
        AncMode.NOISE_CANCELING -> SonyAncMode.NOISE_CANCELING
        AncMode.AMBIENT_SOUND, AncMode.TRANSPARENCY -> SonyAncMode.AMBIENT_SOUND
    }
}
