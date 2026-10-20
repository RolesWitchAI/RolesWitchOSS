//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.settings

import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.service.testLitertModel
import ai.roleswitch.android.service.LitertModels
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EffectiveSettingsTest {

    @AfterTest
    fun tearDown() {
        LitertModels.setCustom(emptyList())
    }

    private fun newManager(): SettingsManager = SettingsManager(InMemoryPreferenceDataStore())

    /** The full 33-field constructor, in the shape a resolved remote thread has. */
    private fun settings(
        useOnDevice: Boolean = false,
        enableVision: Boolean = false,
        openaiAPI: String = "http://localhost:1234/v1",
        selectedModel: String = "test-model",
        litertModel: String = "",
        useSystemTts: Boolean = false,
        useExternalTts: Boolean = false
    ): EffectiveSettings = EffectiveSettings(
        openaiAPI = openaiAPI,
        openaiKey = "",
        selectedModel = selectedModel,
        enableVision = enableVision,
        messageLimit = 50,
        temperature = "",
        topP = "",
        n = "",
        maxCompletionTokens = "",
        presencePenalty = "",
        frequencyPenalty = "",
        reasoningEffort = "",
        language = "en",
        voice = "",
        requireOnDeviceVoice = false,
        useVosk = false,
        allowInterruptions = false,
        embedTime = false,
        keepLastAttachment = false,
        useSystemTts = useSystemTts,
        useExternalTts = useExternalTts,
        externalTtsVoice = "",
        externalTtsAPI = "",
        externalTtsKey = "",
        streamingMode = true,
        enabledToolIds = emptyList(),
        useOnDevice = useOnDevice,
        litertModel = litertModel,
        litertTopK = "",
        litertTopP = "",
        litertTemperature = "",
        litertMaxTokens = "",
        litertAccelerator = "",
        litertEnableThinking = false
    )

    // ---- resolve: global vs per-thread overrides -------------------------------------

    @Test
    fun resolveUsesGlobalValuesWhileCustomSettingsAreOff() {
        val global = newManager().apply { setSelectedModel("global-model") }
        val thread = MessageThread(
            threadId = "t",
            title = "T",
            useCustomSettings = false,
            customModel = "leftover-snapshot"
        )
        assertEquals("global-model", EffectiveSettings.resolve(thread, global).selectedModel)
    }

    @Test
    fun resolvePrefersCustomColumnsWhileCustomSettingsAreOn() {
        val global = newManager().apply { setSelectedModel("global-model") }
        val thread = MessageThread(
            threadId = "t",
            title = "T",
            useCustomSettings = true,
            customModel = "thread-model"
        )
        assertEquals("thread-model", EffectiveSettings.resolve(thread, global).selectedModel)
    }

    @Test
    fun resolveFallsBackToGlobalForNullCustomColumns() {
        val global = newManager().apply {
            setSelectedModel("global-model")
            setMessageLimit(33)
        }
        val thread = MessageThread(threadId = "t", title = "T", useCustomSettings = true)
        val resolved = EffectiveSettings.resolve(thread, global)
        assertEquals("global-model", resolved.selectedModel)
        assertEquals(33, resolved.messageLimit)
    }

    @Test
    fun toolSelectionAppliesRegardlessOfTheCustomSettingsSwitch() {
        val global = newManager()
        val withTools = MessageThread(
            threadId = "t",
            title = "T",
            useCustomSettings = false,
            customToolIds = "id-a, id-b"
        )
        assertEquals(listOf("id-a", "id-b"), EffectiveSettings.resolve(withTools, global).enabledToolIds)
        assertEquals(
            listOf("id-a", "id-b"),
            EffectiveSettings.resolve(withTools.copy(useCustomSettings = true), global).enabledToolIds
        )
    }

    @Test
    fun globalSnapshotMirrorsTheManagerAndCarriesNoTools() {
        val global = newManager().apply {
            setUseOnDevice(true) // must come first: an engine switch resets the limit
            setSelectedModel("m")
            setMessageLimit(7)
        }
        val snapshot = EffectiveSettings.global(global)
        assertEquals("m", snapshot.selectedModel)
        assertEquals(7, snapshot.messageLimit)
        assertTrue(snapshot.useOnDevice)
        assertEquals(emptyList(), snapshot.enabledToolIds)
    }

    // ---- tool id encoding -------------------------------------------------------------

    @Test
    fun toolIdsRoundTripThroughTheirStoredForm() {
        assertEquals("a,b", encodeToolIds(listOf("a", "b")))
        assertEquals(listOf("a", "b"), decodeToolIds(encodeToolIds(listOf("a", "b"))))
    }

    @Test
    fun anEmptyToolSelectionStoresAsNull() {
        assertNull(encodeToolIds(emptyList()))
    }

    @Test
    fun decodingDegenerateToolColumnsMeansNoTools() {
        assertEquals(emptyList(), decodeToolIds(null))
        assertEquals(emptyList(), decodeToolIds(""))
        assertEquals(emptyList(), decodeToolIds("   "))
        assertEquals(listOf("a", "b"), decodeToolIds(" a , b ,,"))
    }

    // ---- derived properties -----------------------------------------------------------

    @Test
    fun ttsModePrefersExternalOverSystemOverAuto() {
        assertEquals(TtsMode.AUTO, settings().ttsMode)
        assertEquals(TtsMode.SYSTEM, settings(useSystemTts = true).ttsMode)
        assertEquals(TtsMode.EXTERNAL, settings(useExternalTts = true).ttsMode)
        // A stored combination both flags set can only come from older state; external wins.
        assertEquals(TtsMode.EXTERNAL, settings(useSystemTts = true, useExternalTts = true).ttsMode)
    }

    @Test
    fun remoteVisionIsJustThePreference() {
        assertEquals(false, settings(enableVision = false).visionAvailable)
        assertEquals(true, settings(enableVision = true).visionAvailable)
    }

    @Test
    fun aVisionCheckpointTakesImagesWhateverThePreferenceSays() {
        // gemma-4-E2B-it declares supportsImage in the registry.
        val onDevice = settings(useOnDevice = true, litertModel = "gemma-4-E2B-it")
        assertEquals(true, onDevice.copy(enableVision = false).visionAvailable)
        assertEquals(true, onDevice.copy(enableVision = true).visionAvailable)
    }

    @Test
    fun aTextOnlyOnDeviceModelStillAsksThePreference() {
        LitertModels.setCustom(listOf(testLitertModel(id = "custom:text-only")))
        val base = settings(useOnDevice = true, litertModel = "custom:text-only")
        assertEquals(true, base.copy(enableVision = true).visionAvailable)
        assertEquals(false, base.copy(enableVision = false).visionAvailable)
    }

    @Test
    fun anUnknownOnDeviceModelIdFallsBackToThePreference() {
        val base = settings(useOnDevice = true, litertModel = "no-such-checkpoint")
        assertEquals(true, base.copy(enableVision = true).visionAvailable)
        assertEquals(false, base.copy(enableVision = false).visionAvailable)
    }

    @Test
    fun isConfiguredDependsOnTheEngine() {
        assertFalse(settings(openaiAPI = "", selectedModel = "").isConfigured)
        assertFalse(settings(openaiAPI = "http://localhost:1234/v1", selectedModel = "").isConfigured)
        assertTrue(settings().isConfigured)

        assertFalse(settings(useOnDevice = true, litertModel = "").isConfigured)
        assertTrue(settings(useOnDevice = true, litertModel = "gemma-4-E2B-it").isConfigured)
    }

    // ---- thread helpers ---------------------------------------------------------------

    @Test
    fun withCustomSettingsEnabledSnapshotsTheGlobalsAndKeepsTheToolSelection() {
        val global = newManager().apply {
            setSelectedModel("global-model")
            setMessageLimit(33)
            setLanguage("ru")
        }
        val thread = MessageThread(threadId = "t", title = "T", customToolIds = "keep-me")
        val enabled = thread.withCustomSettingsEnabled(EffectiveSettings.global(global))

        assertTrue(enabled.useCustomSettings)
        assertEquals("global-model", enabled.customModel)
        assertEquals(33, enabled.customMessageLimit)
        assertEquals("ru", enabled.customLanguage)
        // Pointedly untouched: the tool selection lives outside this switch.
        assertEquals("keep-me", enabled.customToolIds)
    }

    @Test
    fun withLitertModelSelectedFillsTheCheckpointDefaults() {
        val thread = MessageThread(
            threadId = "t",
            title = "T",
            customLitertTopK = "1",
            customLitertAccelerator = "gpu"
        )
        val updated = thread.withLitertModelSelected("gemma-4-E2B-it")
        assertEquals("gemma-4-E2B-it", updated.customLitertModel)
        assertEquals("64", updated.customLitertTopK)
        assertEquals("0.95", updated.customLitertTopP)
        assertEquals("1.0", updated.customLitertTemperature)
        assertEquals("4000", updated.customLitertMaxTokens)
        assertEquals("cpu", updated.customLitertAccelerator)
    }

    @Test
    fun withLitertModelSelectedWritesOnlyTheIdForAnUnknownModel() {
        val thread = MessageThread(threadId = "t", title = "T", customLitertTopK = "7")
        val updated = thread.withLitertModelSelected("custom:not-in-registry")
        assertEquals("custom:not-in-registry", updated.customLitertModel)
        assertEquals("7", updated.customLitertTopK)
    }
}
