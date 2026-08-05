package com.melody.melodylink.sony.config

import android.content.res.AssetManager
import com.op.bttest.sony.SonyAncMode
import com.op.bttest.sony.SonyProtocolVersion
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

interface SonyAssetSource {
    fun readText(path: String): String
}

class AssetManagerSonyAssetSource(
    private val assetManager: AssetManager,
) : SonyAssetSource {
    override fun readText(path: String): String =
        assetManager.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}

class DirectorySonyAssetSource(
    private val root: File,
) : SonyAssetSource {
    override fun readText(path: String): String = File(root, path).readText(Charsets.UTF_8)
}

data class SonyConfigIssue(
    val path: String,
    val message: String,
)

data class SonyConfigLoadResult(
    val registry: SonyConfigRegistry,
    val issues: List<SonyConfigIssue>,
)

object SonyConfigLoader {
    private const val REGISTRY_PATH = "sony/registry.json"

    fun fromAssets(assetManager: AssetManager): SonyConfigLoadResult =
        load(AssetManagerSonyAssetSource(assetManager))

    fun fromDirectory(root: File): SonyConfigLoadResult = load(DirectorySonyAssetSource(root))

    fun load(source: SonyAssetSource): SonyConfigLoadResult {
        val issues = mutableListOf<SonyConfigIssue>()
        val registry = try {
            JSONObject(source.readText(REGISTRY_PATH))
        } catch (error: Throwable) {
            return SonyConfigLoadResult(
                registry = SonyConfigRegistry.empty(),
                issues = listOf(SonyConfigIssue(REGISTRY_PATH, "registry unavailable: ${error.message}")),
            )
        }
        if (registry.optInt("schemaVersion", -1) != SONY_CONFIG_SCHEMA_VERSION) {
            return SonyConfigLoadResult(
                registry = SonyConfigRegistry.empty(),
                issues = listOf(SonyConfigIssue(REGISTRY_PATH, "unsupported registry schema")),
            )
        }
        val files = registry.optJSONArray("devices")
        if (files == null) {
            return SonyConfigLoadResult(
                registry = SonyConfigRegistry.empty(),
                issues = listOf(SonyConfigIssue(REGISTRY_PATH, "devices must be an array")),
            )
        }

        val profiles = mutableListOf<SonyDeviceConfig>()
        for (index in 0 until files.length()) {
            val path = files.optString(index)
            if (path.isBlank()) {
                issues += SonyConfigIssue(REGISTRY_PATH, "devices[$index] is not a path")
                continue
            }
            val profile = try {
                parseDevice(JSONObject(source.readText(path)))
            } catch (error: Throwable) {
                issues += SonyConfigIssue(path, error.message ?: error.javaClass.simpleName)
                continue
            }
            val errors = SonyConfigValidator.validate(profile)
            if (errors.isNotEmpty()) {
                errors.forEach { issues += SonyConfigIssue(path, it) }
                continue
            }
            if (profiles.any { it.id == profile.id }) {
                issues += SonyConfigIssue(path, "duplicate profile id ${profile.id}")
                continue
            }
            if (profiles.any { it.name.equals(profile.name, ignoreCase = true) }) {
                issues += SonyConfigIssue(path, "duplicate profile name ${profile.name}")
                continue
            }
            profiles += profile
        }
        return SonyConfigLoadResult(SonyConfigRegistry.of(profiles), issues)
    }

    private fun parseDevice(json: JSONObject): SonyDeviceConfig {
        val match = json.requiredObject("match")
        val protocol = json.requiredObject("protocol")
        val battery = json.requiredObject("battery")
        val capabilities = json.requiredObject("capabilities")
        val controls = json.requiredObject("controls")
        val quirks = json.requiredObject("quirks")
        val ambientLevel = capabilities.optionalObject("ambientLevel")
        val uuidObject = protocol.requiredObject("rfcommUuids")
        val versions = protocol.requiredStringList("versions").map(SonyProtocolVersion::valueOf)
        val uuids = versions.associateWith { version ->
            UUID.fromString(uuidObject.requiredString(version.name))
        }
        return SonyDeviceConfig(
            schemaVersion = json.requiredInt("schemaVersion"),
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            family = json.requiredString("family"),
            modelType = json.requiredString("modelType"),
            match = SonyMatchRules(
                exactNames = match.requiredStringList("exactNames"),
                namePatterns = match.requiredStringList("namePatterns"),
                modaliasPrefixes = match.requiredStringList("modaliasPrefixes"),
                deviceInfoModels = match.requiredStringList("deviceInfoModels"),
            ),
            protocol = SonyProtocolConfig(
                versions = versions,
                preferredVersion = SonyProtocolVersion.valueOf(protocol.requiredString("preferredVersion")),
                rfcommUuids = uuids,
                handshakeRequired = protocol.requiredBoolean("handshakeRequired"),
                defaultV1AsmType = protocol.optionalInt("defaultV1AsmType"),
                defaultV2AsmType = protocol.optionalInt("defaultV2AsmType"),
            ),
            battery = SonyBatteryConfig(
                layout = SonyBatteryLayout.valueOf(battery.requiredString("layout")),
                supportsChargingState = battery.requiredBoolean("supportsChargingState"),
            ),
            capabilities = SonyCapabilityConfig(
                ancModes = capabilities.requiredStringList("ancModes").map(SonyAncMode::valueOf).toSet(),
                ambientLevelMin = ambientLevel?.requiredInt("min"),
                ambientLevelMax = ambientLevel?.requiredInt("max"),
                voiceFocus = capabilities.requiredBoolean("voiceFocus"),
                autoAmbient = capabilities.requiredBoolean("autoAmbient"),
                dsee = capabilities.requiredBoolean("dsee"),
                equalizerBands = capabilities.requiredInt("equalizerBands"),
                speakToChat = capabilities.requiredBoolean("speakToChat"),
                quickAccess = capabilities.requiredBoolean("quickAccess"),
                extraFeatures = capabilities.requiredStringList("extraFeatures").toSet(),
            ),
            controls = SonyControlConfig(
                buttonModes = controls.requiredStringList("buttonModes"),
                pauseWhenRemoved = controls.requiredBoolean("pauseWhenRemoved"),
                automaticPowerOffWhenRemoved = controls.requiredBoolean("automaticPowerOffWhenRemoved"),
                automaticPowerOffByTime = controls.requiredBoolean("automaticPowerOffByTime"),
                powerOffFromPhone = controls.requiredBoolean("powerOffFromPhone"),
            ),
            quirks = SonyQuirks(
                v1WindSupported = quirks.requiredBoolean("v1WindSupported"),
                requiresAncReadAfterWrite = quirks.requiredBoolean("requiresAncReadAfterWrite"),
            ),
            supportLevel = SonySupportLevel.valueOf(json.requiredString("supportLevel")),
        )
    }
}

object SonyConfigValidator {
    fun validate(profile: SonyDeviceConfig): List<String> = buildList {
        if (profile.schemaVersion != SONY_CONFIG_SCHEMA_VERSION) add("unsupported profile schema")
        if (!profile.id.matches(Regex("sony\\.[a-z0-9_]+"))) add("invalid profile id")
        if (profile.name.isBlank()) add("name must not be blank")
        if (profile.match.exactNames.isEmpty() && profile.match.namePatterns.isEmpty()) {
            add("at least one name matcher is required")
        }
        profile.match.namePatterns.forEach { pattern ->
            try {
                Regex(pattern)
            } catch (_: Throwable) {
                add("invalid name pattern $pattern")
            }
        }
        if (profile.protocol.versions.isEmpty()) add("at least one protocol version is required")
        if (profile.protocol.preferredVersion !in profile.protocol.versions) {
            add("preferred protocol version must be enabled")
        }
        profile.protocol.versions.forEach { version ->
            if (profile.protocol.rfcommUuids[version] == null) add("missing RFCOMM UUID for $version")
        }
        val min = profile.capabilities.ambientLevelMin
        val max = profile.capabilities.ambientLevelMax
        if ((min == null) != (max == null) || (min != null && (min < 0 || max!! < min))) {
            add("invalid ambient level range")
        }
        if (profile.capabilities.equalizerBands !in setOf(0, 6, 10)) {
            add("equalizer bands must be 0, 6, or 10")
        }
    }
}

private fun JSONObject.requiredObject(name: String): JSONObject =
    if (has(name) && !isNull(name)) getJSONObject(name) else error("missing object $name")

private fun JSONObject.optionalObject(name: String): JSONObject? =
    if (has(name) && !isNull(name)) getJSONObject(name) else null

private fun JSONObject.requiredString(name: String): String =
    if (has(name) && !isNull(name)) getString(name) else error("missing string $name")

private fun JSONObject.requiredInt(name: String): Int =
    if (has(name) && !isNull(name)) getInt(name) else error("missing integer $name")

private fun JSONObject.optionalInt(name: String): Int? =
    if (has(name) && !isNull(name)) getInt(name) else null

private fun JSONObject.requiredBoolean(name: String): Boolean =
    if (has(name) && !isNull(name)) getBoolean(name) else error("missing boolean $name")

private fun JSONObject.requiredStringList(name: String): List<String> {
    if (!has(name) || isNull(name)) error("missing array $name")
    return getJSONArray(name).stringList(name)
}

private fun JSONArray.stringList(name: String): List<String> = buildList {
    for (index in 0 until length()) {
        val value = optString(index)
        if (value.isBlank()) error("$name contains an empty value")
        add(value)
    }
}
