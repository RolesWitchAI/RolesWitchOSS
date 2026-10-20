//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.settings

import ai.roleswitch.android.service.LitertModels
import ai.roleswitch.android.service.testLitertModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsManagerTest {

    @AfterTest
    fun tearDown() {
        // The manager pushes its custom-model list into the process-wide registry on
        // construction; don't leak that into other tests.
        LitertModels.setCustom(emptyList())
    }

    private fun newManager(): SettingsManager = SettingsManager(InMemoryPreferenceDataStore())

    @Test
    fun aFreshManagerExposesTheDocumentedDefaults() {
        val sm = newManager()
        assertEquals(false, sm.hasCompletedOnboarding.value)
        assertEquals("", sm.openaiAPI.value)
        assertEquals("", sm.openaiKey.value)
        assertEquals("", sm.selectedModel.value)
        assertEquals("", sm.voice.value)
        assertEquals("en", sm.language.value)
        assertEquals(50, sm.messageLimit.value)
        assertEquals(false, sm.enableVision.value)
        assertEquals(false, sm.embedTime.value)
        assertEquals(false, sm.requireOnDeviceVoice.value)
        assertEquals(false, sm.keepLastAttachment.value)
        assertEquals(false, sm.useVosk.value)
        assertEquals(false, sm.allowInterruptions.value)
        assertEquals(false, sm.useSystemTts.value)
        assertEquals(false, sm.useExternalTts.value)
        assertEquals("", sm.externalTtsVoice.value)
        assertEquals("", sm.externalTtsAPI.value)
        assertEquals("", sm.externalTtsKey.value)
        assertEquals(true, sm.streamingMode.value)
        assertEquals("", sm.temperature.value)
        assertEquals("", sm.reasoningEffort.value)
        assertEquals(false, sm.useOnDevice.value)
        assertEquals("", sm.litertModel.value)
        assertEquals("", sm.litertAccelerator.value)
        assertEquals(false, sm.litertEnableThinking.value)
        assertEquals("", sm.huggingFaceToken.value)
        assertEquals(false, sm.hasValidModel)
    }

    @Test
    fun settersUpdateTheInMemoryState() {
        val sm = newManager()
        sm.setHasCompletedOnboarding(true)
        sm.setOpenaiAPI("http://localhost:1234/v1")
        sm.setOpenaiKey("sk-test")
        sm.setSelectedModel("gpt-4o-mini")
        sm.setTemperature("0.7")
        sm.setReasoningEffort("low")
        sm.setUseExternalTts(true)
        sm.setExternalTtsVoice("alloy")
        sm.setExternalTtsAPI("http://192.168.0.5:9000/v1")
        sm.setExternalTtsKey("sk-voice")

        assertEquals(true, sm.hasCompletedOnboarding.value)
        assertEquals("http://localhost:1234/v1", sm.openaiAPI.value)
        assertEquals("sk-test", sm.openaiKey.value)
        assertEquals("gpt-4o-mini", sm.selectedModel.value)
        assertEquals("0.7", sm.temperature.value)
        assertEquals("low", sm.reasoningEffort.value)
        assertEquals(true, sm.useExternalTts.value)
        assertEquals("alloy", sm.externalTtsVoice.value)
        assertEquals("http://192.168.0.5:9000/v1", sm.externalTtsAPI.value)
        assertEquals("sk-voice", sm.externalTtsKey.value)
    }

    @Test
    fun switchingEnginesResetsTheMessageLimitToTheSharedDefault() {
        val sm = newManager()
        sm.setMessageLimit(100000)
        assertEquals(100000, sm.messageLimit.value)

        sm.setUseOnDevice(true)
        assertEquals(50, sm.messageLimit.value)

        sm.setMessageLimit(120)
        sm.setUseOnDevice(false)
        assertEquals(50, sm.messageLimit.value)
    }

    @Test
    fun settingTheSameEngineAgainKeepsTheMessageLimit() {
        val sm = newManager()
        sm.setUseOnDevice(true)
        sm.setMessageLimit(120)
        sm.setUseOnDevice(true) // not a change — must not reset
        assertEquals(120, sm.messageLimit.value)
    }

    @Test
    fun selectingAModelFillsTheSamplerColumnsFromTheCheckpoint() {
        val sm = newManager()
        sm.setLitertTopK("1") // the previous model's tuning must be replaced, not merged
        sm.selectLitertModel("gemma-4-E2B-it")

        assertEquals("gemma-4-E2B-it", sm.litertModel.value)
        assertEquals("64", sm.litertTopK.value)
        assertEquals("0.95", sm.litertTopP.value)
        assertEquals("1.0", sm.litertTemperature.value)
        assertEquals("4000", sm.litertMaxTokens.value)
        assertEquals("cpu", sm.litertAccelerator.value)
    }

    @Test
    fun selectingAnUnknownModelOnlyStoresItsId() {
        val sm = newManager()
        sm.setLitertTopK("7")
        sm.selectLitertModel("custom:not-in-registry")
        assertEquals("custom:not-in-registry", sm.litertModel.value)
        assertEquals("7", sm.litertTopK.value)
    }

    @Test
    fun hasValidModelMeansDifferentThingsPerEngine() {
        val sm = newManager()
        assertFalse(sm.hasValidModel)

        sm.setOpenaiAPI("http://localhost:1234/v1")
        assertFalse(sm.hasValidModel) // model still missing
        sm.setSelectedModel("gpt-4o-mini")
        assertTrue(sm.hasValidModel)

        sm.setUseOnDevice(true)
        assertFalse(sm.hasValidModel) // no on-device checkpoint picked
        sm.selectLitertModel("gemma-4-E2B-it")
        assertTrue(sm.hasValidModel)
    }

    @Test
    fun licenseAcceptanceIsRecordedAndIdempotent() {
        val sm = newManager()
        assertFalse(sm.hasAcceptedLicense("gemma-4-E2B-it"))
        sm.acceptLicense("gemma-4-E2B-it")
        assertTrue(sm.hasAcceptedLicense("gemma-4-E2B-it"))

        sm.acceptLicense("gemma-4-E2B-it") // duplicate must not double up
        sm.acceptLicense("other-model")
        assertTrue(sm.hasAcceptedLicense("other-model"))
        assertFalse(sm.hasAcceptedLicense("third"))
    }

    @Test
    fun huggingFaceTokenIsTrimmedOnTheWayIn() {
        val sm = newManager()
        sm.setHuggingFaceToken("  hf_token  ")
        assertEquals("hf_token", sm.huggingFaceToken.value)
    }

    @Test
    fun customModelsReplaceByIdAndResolveThroughTheRegistry() {
        val sm = newManager()
        val first = testLitertModel(id = "custom:owner/model")
        sm.addCustomLitertModel(first)
        assertEquals(listOf(first), sm.customLitertModels.value)
        assertEquals(first, LitertModels.get("custom:owner/model"))

        val replacement = testLitertModel(id = "custom:owner/model", fileName = "reuploaded.litertlm")
        sm.addCustomLitertModel(replacement)
        assertEquals(listOf(replacement), sm.customLitertModels.value)

        sm.removeCustomLitertModel("custom:owner/model")
        assertEquals(emptyList(), sm.customLitertModels.value)
        assertNull(LitertModels.get("custom:owner/model"))
    }

    @Test
    fun resetRestoresEveryDefaultAndClearsTheStore() = runTest {
        val store = InMemoryPreferenceDataStore()
        val sm = SettingsManager(store)
        sm.setHasCompletedOnboarding(true)
        sm.setOpenaiAPI("http://localhost:1234/v1")
        sm.setOpenaiKey("sk-test")
        sm.setSelectedModel("gpt-4o-mini")
        sm.setLanguage("ru")
        sm.setMessageLimit(100)
        sm.setUseOnDevice(true)
        sm.setStreamingMode(false)
        sm.setUseExternalTts(true)
        sm.setExternalTtsAPI("http://voice:9000/v1")
        sm.acceptLicense("gemma-4-E2B-it")
        sm.setHuggingFaceToken("hf_token")
        sm.setReasoningEffort("high")

        sm.reset()

        assertEquals(false, sm.hasCompletedOnboarding.value)
        assertEquals("", sm.openaiAPI.value)
        assertEquals("", sm.openaiKey.value)
        assertEquals("", sm.selectedModel.value)
        assertEquals("en", sm.language.value)
        assertEquals(50, sm.messageLimit.value)
        assertEquals(false, sm.useOnDevice.value)
        assertEquals(true, sm.streamingMode.value)
        assertEquals(false, sm.useExternalTts.value)
        assertEquals("", sm.externalTtsAPI.value)
        assertEquals(false, sm.hasAcceptedLicense("gemma-4-E2B-it"))
        assertEquals("", sm.huggingFaceToken.value)
        assertEquals("", sm.reasoningEffort.value)
        assertEquals(emptyList(), sm.customLitertModels.value)
        // reset() clears the store and then re-writes the custom-model list's own empty
        // encoding ("[]"), so the store is empty of every *setting* — the one key that
        // may remain is that self-describing empty list.
        val remaining = store.data.first().asMap().keys.map { it.name }
        assertTrue(remaining.all { it == "litertCustomModels" }, "unexpected keys survived reset: $remaining")
    }
}
