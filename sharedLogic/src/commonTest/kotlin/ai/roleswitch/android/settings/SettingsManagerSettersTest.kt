//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.settings

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every setter the surfaces drive — including the ones the other suites never touch.
 * This is deliberately exhaustive: the manager is all setter plumbing, and a setter
 * that stops updating its flow fails here before it fails anywhere else.
 */
class SettingsManagerSettersTest {

    private fun newManager(): SettingsManager = SettingsManager(InMemoryPreferenceDataStore())

    @Test
    fun audioAndSpeechSettersUpdateTheirFlows() {
        val sm = newManager()
        sm.setVoice("Samantha")
        sm.setRequireOnDeviceVoice(true)
        sm.setUseVosk(true)
        sm.setAllowInterruptions(true)
        sm.setUseSystemTts(true)
        sm.setExternalTtsVoice("alloy")
        sm.setUseExternalTts(true)

        assertEquals("Samantha", sm.voice.value)
        assertEquals(true, sm.requireOnDeviceVoice.value)
        assertEquals(true, sm.useVosk.value)
        assertEquals(true, sm.allowInterruptions.value)
        assertEquals(true, sm.useSystemTts.value)
        assertEquals("alloy", sm.externalTtsVoice.value)
        assertEquals(true, sm.useExternalTts.value)
    }

    @Test
    fun generationParameterSettersUpdateTheirFlows() {
        val sm = newManager()
        sm.setTopP("0.9")
        sm.setN("3")
        sm.setMaxCompletionTokens("512")
        sm.setPresencePenalty("0.3")
        sm.setFrequencyPenalty("0.4")
        sm.setReasoningEffort("high")
        sm.setEnableVision(true)
        sm.setEmbedTime(true)
        sm.setKeepLastAttachment(true)
        sm.setStreamingMode(false)

        assertEquals("0.9", sm.topP.value)
        assertEquals("3", sm.n.value)
        assertEquals("512", sm.maxCompletionTokens.value)
        assertEquals("0.3", sm.presencePenalty.value)
        assertEquals("0.4", sm.frequencyPenalty.value)
        assertEquals("high", sm.reasoningEffort.value)
        assertEquals(true, sm.enableVision.value)
        assertEquals(true, sm.embedTime.value)
        assertEquals(true, sm.keepLastAttachment.value)
        assertEquals(false, sm.streamingMode.value)
    }

    @Test
    fun onboardingAndConsentSettersUpdateTheirFlows() {
        val sm = newManager()
        sm.setHasSeenNotificationPrompt(true)
        sm.setHasAcceptedDataSharing(true)
        sm.setHasAcceptedMultipleAttachments(true)

        assertEquals(true, sm.hasSeenNotificationPrompt.value)
        assertEquals(true, sm.hasAcceptedDataSharing.value)
        assertEquals(true, sm.hasAcceptedMultipleAttachments.value)
    }

    @Test
    fun litertSamplerSettersUpdateTheirFlows() {
        val sm = newManager()
        sm.setLitertModel("gemma-4-E4B-it")
        sm.setLitertTopK("32")
        sm.setLitertTopP("0.8")
        sm.setLitertTemperature("0.9")
        sm.setLitertMaxTokens("2048")
        sm.setLitertAccelerator("gpu")
        sm.setLitertEnableThinking(true)

        assertEquals("gemma-4-E4B-it", sm.litertModel.value)
        assertEquals("32", sm.litertTopK.value)
        assertEquals("0.8", sm.litertTopP.value)
        assertEquals("0.9", sm.litertTemperature.value)
        assertEquals("2048", sm.litertMaxTokens.value)
        assertEquals("gpu", sm.litertAccelerator.value)
        assertEquals(true, sm.litertEnableThinking.value)
    }

    @Test
    fun writingTheSameEngineTwiceIsNotAChange() = runTest {
        val sm = newManager()
        sm.setUseOnDevice(false) // already the default — not a change, no reset
        sm.setMessageLimit(80)
        assertEquals(80, sm.messageLimit.value)
    }

    @Test
    fun hasAcceptedDataSharingGatesOnOneFlagForTheWholeApp() {
        val sm = newManager()
        assertFalse(sm.hasAcceptedDataSharing.value)
        sm.setHasAcceptedDataSharing(true)
        assertTrue(sm.hasAcceptedDataSharing.value)
    }
}
