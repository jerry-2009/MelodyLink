package com.melody.melodylink.sony.config

import com.op.bttest.sony.SonyAncMode
import com.op.bttest.sony.SonyProtocolVersion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyConfigLoaderTest {
    @Test
    fun loadsEveryBundledProfileWithoutIssues() {
        val result = SonyConfigLoader.fromDirectory(mainAssetsDirectory())

        assertTrue(result.issues.joinToString("\n") { "${it.path}: ${it.message}" }, result.issues.isEmpty())
        assertEquals(22, result.registry.profiles.size)
    }
}

class SonyConfigValidatorTest {
    @Test
    fun skipsInvalidProfileWithoutDiscardingTheRegistry() {
        val source = MapSonyAssetSource(
            mapOf(
                "sony/registry.json" to """{"schemaVersion":1,"devices":["sony/config/bad.json"]}""",
                "sony/config/bad.json" to validProfileJson(id = "not-sony"),
            ),
        )

        val result = SonyConfigLoader.load(source)

        assertTrue(result.registry.profiles.isEmpty())
        assertTrue(result.issues.any { it.message == "invalid profile id" })
    }
}

class SonyDeviceMatcherTest {
    @Test
    fun prefersExactNameThenCanFallBackToModalias() {
        val registry = SonyConfigLoader.fromDirectory(mainAssetsDirectory()).registry

        assertEquals("sony.wf_1000xm3", registry.findBest(DeviceIdentity(bluetoothName = "WF-1000XM3"))?.id)
        assertEquals("sony.wh_1000xm4", registry.findBest(DeviceIdentity(modalias = "v054Cp0D58-any"))?.id)
        assertNull(registry.findBest(DeviceIdentity(bluetoothName = "Unknown Sony Device")))
    }

    @Test
    fun mapsEveryRegisteredSonyNameToAProfile() {
        val registry = SonyConfigLoader.fromDirectory(mainAssetsDirectory()).registry

        registry.profiles.forEach { profile ->
            profile.match.exactNames.forEach { name ->
                assertEquals(profile.id, registry.findBest(DeviceIdentity(bluetoothName = name))?.id)
            }
        }
    }
}

class SonyConfigRegistryTest {
    @Test
    fun skipsOnlyTheSecondDuplicateProfile() {
        val source = MapSonyAssetSource(
            mapOf(
                "sony/registry.json" to """{"schemaVersion":1,"devices":["sony/config/one.json","sony/config/two.json"]}""",
                "sony/config/one.json" to validProfileJson(),
                "sony/config/two.json" to validProfileJson(name = "Duplicate Device"),
            ),
        )

        val result = SonyConfigLoader.load(source)

        assertEquals(1, result.registry.profiles.size)
        assertTrue(result.issues.any { it.message.startsWith("duplicate profile id") })
    }
}

class SonyProfileMigrationTest {
    @Test
    fun preservesKeyBudsLinkCapabilitiesInProfiles() {
        val registry = SonyConfigLoader.fromDirectory(mainAssetsDirectory()).registry
        val xm3 = registry.profile("sony.wf_1000xm3")!!
        val whXm4 = registry.profile("sony.wh_1000xm4")!!
        val c510 = registry.profile("sony.wf_c510")!!

        assertEquals(SonyBatteryLayout.LEFT_RIGHT_CASE, xm3.battery.layout)
        assertTrue(SonyAncMode.WIND_NOISE_REDUCTION in xm3.capabilities.ancModes)
        assertEquals(SonyProtocolVersion.V1, whXm4.protocol.preferredVersion)
        assertEquals(setOf(SonyAncMode.OFF, SonyAncMode.AMBIENT_SOUND), c510.capabilities.ancModes)
        assertTrue(xm3.supportLevel.permitsWrites)
        assertTrue(xm3.permitsAncWrites(SonyAncMode.NOISE_CANCELING))
    }
}

private class MapSonyAssetSource(
    private val files: Map<String, String>,
) : SonyAssetSource {
    override fun readText(path: String): String = files[path] ?: error("missing $path")
}

private fun mainAssetsDirectory(): File = sequenceOf(
    File("src/main/assets"),
    File("app/src/main/assets"),
    File("../app/src/main/assets"),
).firstOrNull(File::isDirectory) ?: error("main assets directory was not found")

private fun validProfileJson(
    id: String = "sony.test_device",
    name: String = "Test Device",
): String = """
    {
      "schemaVersion": 1,
      "id": "$id",
      "name": "$name",
      "family": "TEST",
      "modelType": "TWS",
      "match": {"exactNames": ["$name"], "namePatterns": [], "modaliasPrefixes": [], "deviceInfoModels": []},
      "protocol": {"versions": ["V2"], "preferredVersion": "V2", "rfcommUuids": {"V2": "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"}, "handshakeRequired": true, "defaultV1AsmType": null, "defaultV2AsmType": 23},
      "battery": {"layout": "LEFT_RIGHT", "supportsChargingState": true},
      "capabilities": {"ancModes": ["OFF"], "ambientLevel": {"min": 1, "max": 20}, "voiceFocus": false, "autoAmbient": false, "dsee": false, "equalizerBands": 0, "speakToChat": false, "quickAccess": false, "extraFeatures": []},
      "controls": {"buttonModes": [], "pauseWhenRemoved": false, "automaticPowerOffWhenRemoved": false, "automaticPowerOffByTime": false, "powerOffFromPhone": false},
      "quirks": {"v1WindSupported": false, "requiresAncReadAfterWrite": true},
      "supportLevel": "READ_ONLY"
    }
""".trimIndent()
