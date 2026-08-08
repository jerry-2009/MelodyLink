package com.melody.melodylink.hook

import com.melody.melodylink.domain.AncMode

/** Converts Melody's internal integer command representation into a domain command. */
object MelodyCommandBridge {
    fun ancMode(index: Int): AncMode? = when (index) {
        0 -> AncMode.OFF
        1 -> AncMode.NOISE_CANCELING
        2 -> AncMode.AMBIENT_SOUND
        3, 4 -> AncMode.NOISE_CANCELING
        else -> null
    }
}
