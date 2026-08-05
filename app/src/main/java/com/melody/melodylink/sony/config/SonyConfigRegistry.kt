package com.melody.melodylink.sony.config

class SonyConfigRegistry private constructor(
    val profiles: List<SonyDeviceConfig>,
) {
    init {
        require(profiles.map { it.id }.distinct().size == profiles.size) { "duplicate Sony profile id" }
    }

    fun candidates(identity: DeviceIdentity): List<SonyDeviceConfig> =
        SonyDeviceMatcher.candidates(identity, profiles)

    fun findBest(identity: DeviceIdentity): SonyDeviceConfig? = candidates(identity).firstOrNull()

    fun profile(id: String): SonyDeviceConfig? = profiles.firstOrNull { it.id == id }

    companion object {
        fun of(profiles: List<SonyDeviceConfig>): SonyConfigRegistry = SonyConfigRegistry(profiles)

        fun empty(): SonyConfigRegistry = SonyConfigRegistry(emptyList())
    }
}

object SonyDeviceMatcher {
    fun candidates(
        identity: DeviceIdentity,
        profiles: List<SonyDeviceConfig>,
    ): List<SonyDeviceConfig> = profiles
        .mapNotNull { profile -> score(identity, profile)?.let { score -> profile to score } }
        .sortedWith(compareByDescending<Pair<SonyDeviceConfig, Int>> { it.second }.thenBy { it.first.id })
        .map { it.first }

    private fun score(identity: DeviceIdentity, profile: SonyDeviceConfig): Int? {
        val name = identity.bluetoothName
        if (name != null && profile.match.exactNames.any { it.equals(name, ignoreCase = true) }) {
            return 300
        }
        if (name != null && profile.match.namePatterns.any { Regex(it).matches(name) }) {
            return 200
        }
        val modalias = identity.modalias
        if (modalias != null && profile.match.modaliasPrefixes.any { modalias.startsWith(it, ignoreCase = true) }) {
            return 100
        }
        val model = identity.deviceInfoModel
        if (model != null && profile.match.deviceInfoModels.any { it.equals(model, ignoreCase = true) }) {
            return 50
        }
        return null
    }
}
