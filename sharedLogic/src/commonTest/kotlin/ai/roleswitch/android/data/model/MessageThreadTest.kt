//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [MessageThread] hand-writes equals/hashCode because of the `ByteArray` avatar — the
 * generated data-class versions would compare it by reference. Every column takes part,
 * so every column is asserted: a field silently dropped from equality is a bug that
 * makes Room rows compare unequal to themselves after a copy.
 */
class MessageThreadTest {

    private val base = MessageThread(
        threadId = "t",
        title = "title",
        prompt = "prompt",
        avatarBytes = byteArrayOf(1),
        createdAt = 1L,
        updatedAt = 2L,
        useCustomSettings = true,
        customApiUrl = "u",
        customApiKey = "k",
        customModel = "m",
        customEnableVision = true,
        customMessageLimit = 3,
        customTemperature = "t1",
        customTopP = "p1",
        customN = "n1",
        customMaxCompletionTokens = "c1",
        customPresencePenalty = "pp",
        customFrequencyPenalty = "fp",
        customReasoningEffort = "re",
        customLanguage = "en",
        customVoice = "v",
        customRequireOnDeviceVoice = true,
        customUseVosk = true,
        customAllowInterruptions = true,
        customEmbedTime = true,
        customKeepLastAttachment = true,
        customUseSystemTts = true,
        customUseExternalTts = true,
        customExternalTtsVoice = "alloy",
        customExternalTtsAPI = "http://voice/v1",
        customExternalTtsKey = "kv",
        customStreamingMode = true,
        customToolIds = "a,b",
        customUseLiteRt = true,
        customLitertModel = "lm",
        customLitertTopK = "64",
        customLitertTopP = "0.95",
        customLitertTemperature = "1.0",
        customLitertMaxTokens = "4000",
        customLitertAccelerator = "gpu",
        customLitertEnableThinking = true,
        cubismId = "hibiki"
    )

    private val variants: List<Pair<String, MessageThread>> = listOf(
        "threadId" to base.copy(threadId = "other"),
        "title" to base.copy(title = "other"),
        "prompt" to base.copy(prompt = "other"),
        "avatarBytes" to base.copy(avatarBytes = byteArrayOf(2)),
        "avatarBytes-null" to base.copy(avatarBytes = null),
        "createdAt" to base.copy(createdAt = 9L),
        "updatedAt" to base.copy(updatedAt = 9L),
        "useCustomSettings" to base.copy(useCustomSettings = false),
        "customApiUrl" to base.copy(customApiUrl = "other"),
        "customApiKey" to base.copy(customApiKey = "other"),
        "customModel" to base.copy(customModel = "other"),
        "customEnableVision" to base.copy(customEnableVision = false),
        "customMessageLimit" to base.copy(customMessageLimit = 4),
        "customTemperature" to base.copy(customTemperature = "other"),
        "customTopP" to base.copy(customTopP = "other"),
        "customN" to base.copy(customN = "other"),
        "customMaxCompletionTokens" to base.copy(customMaxCompletionTokens = "other"),
        "customPresencePenalty" to base.copy(customPresencePenalty = "other"),
        "customFrequencyPenalty" to base.copy(customFrequencyPenalty = "other"),
        "customReasoningEffort" to base.copy(customReasoningEffort = "other"),
        "customLanguage" to base.copy(customLanguage = "ru"),
        "customVoice" to base.copy(customVoice = "other"),
        "customRequireOnDeviceVoice" to base.copy(customRequireOnDeviceVoice = false),
        "customUseVosk" to base.copy(customUseVosk = false),
        "customAllowInterruptions" to base.copy(customAllowInterruptions = false),
        "customEmbedTime" to base.copy(customEmbedTime = false),
        "customKeepLastAttachment" to base.copy(customKeepLastAttachment = false),
        "customUseSystemTts" to base.copy(customUseSystemTts = false),
        "customUseExternalTts" to base.copy(customUseExternalTts = false),
        "customExternalTtsVoice" to base.copy(customExternalTtsVoice = "other"),
        "customExternalTtsAPI" to base.copy(customExternalTtsAPI = "other"),
        "customExternalTtsKey" to base.copy(customExternalTtsKey = "other"),
        "customStreamingMode" to base.copy(customStreamingMode = false),
        "customToolIds" to base.copy(customToolIds = "other"),
        "customUseLiteRt" to base.copy(customUseLiteRt = false),
        "customLitertModel" to base.copy(customLitertModel = "other"),
        "customLitertTopK" to base.copy(customLitertTopK = "other"),
        "customLitertTopP" to base.copy(customLitertTopP = "other"),
        "customLitertTemperature" to base.copy(customLitertTemperature = "other"),
        "customLitertMaxTokens" to base.copy(customLitertMaxTokens = "other"),
        "customLitertAccelerator" to base.copy(customLitertAccelerator = "cpu"),
        "customLitertEnableThinking" to base.copy(customLitertEnableThinking = false),
        "cubismId" to base.copy(cubismId = "hiyori")
    )

    @Test
    fun everyColumnParticipatesInEquality() {
        variants.forEach { (field, variant) ->
            assertNotEquals(base, variant, "$field does not participate in equals")
            assertNotEquals(base.hashCode(), variant.hashCode(), "$field does not participate in hashCode")
        }
    }

    /** Null is the other value every nullable column carries — and the leg of
     *  hashCode's `?: 0` that non-null variants can never reach. */
    @Test
    fun nullColumnsParticipateInEqualityToo() {
        val nullVariants: List<Pair<String, MessageThread>> = listOf(
            "customApiUrl" to base.copy(customApiUrl = null),
            "customApiKey" to base.copy(customApiKey = null),
            "customModel" to base.copy(customModel = null),
            "customEnableVision" to base.copy(customEnableVision = null),
            "customMessageLimit" to base.copy(customMessageLimit = null),
            "customTemperature" to base.copy(customTemperature = null),
            "customTopP" to base.copy(customTopP = null),
            "customN" to base.copy(customN = null),
            "customMaxCompletionTokens" to base.copy(customMaxCompletionTokens = null),
            "customPresencePenalty" to base.copy(customPresencePenalty = null),
            "customFrequencyPenalty" to base.copy(customFrequencyPenalty = null),
            "customReasoningEffort" to base.copy(customReasoningEffort = null),
            "customLanguage" to base.copy(customLanguage = null),
            "customVoice" to base.copy(customVoice = null),
            "customRequireOnDeviceVoice" to base.copy(customRequireOnDeviceVoice = null),
            "customUseVosk" to base.copy(customUseVosk = null),
            "customAllowInterruptions" to base.copy(customAllowInterruptions = null),
            "customEmbedTime" to base.copy(customEmbedTime = null),
            "customKeepLastAttachment" to base.copy(customKeepLastAttachment = null),
            "customUseSystemTts" to base.copy(customUseSystemTts = null),
            "customUseExternalTts" to base.copy(customUseExternalTts = null),
            "customExternalTtsVoice" to base.copy(customExternalTtsVoice = null),
            "customExternalTtsAPI" to base.copy(customExternalTtsAPI = null),
            "customExternalTtsKey" to base.copy(customExternalTtsKey = null),
            "customStreamingMode" to base.copy(customStreamingMode = null),
            "customToolIds" to base.copy(customToolIds = null),
            "customUseLiteRt" to base.copy(customUseLiteRt = null),
            "customLitertModel" to base.copy(customLitertModel = null),
            "customLitertTopK" to base.copy(customLitertTopK = null),
            "customLitertTopP" to base.copy(customLitertTopP = null),
            "customLitertTemperature" to base.copy(customLitertTemperature = null),
            "customLitertMaxTokens" to base.copy(customLitertMaxTokens = null),
            "customLitertAccelerator" to base.copy(customLitertAccelerator = null),
            "customLitertEnableThinking" to base.copy(customLitertEnableThinking = null),
            "cubismId" to base.copy(cubismId = null)
        )
        nullVariants.forEach { (field, variant) ->
            assertNotEquals(base, variant, "$field = null does not participate in equals")
            assertNotEquals(base.hashCode(), variant.hashCode(), "$field = null does not participate in hashCode")
        }
    }

    @Test
    fun anIdenticalCopyComparesEqualWithTheSameHash() {
        assertEquals(base, base.copy())
        assertEquals(base.hashCode(), base.copy().hashCode())
    }

    @Test
    fun equalityBasicsHold() {
        assertTrue(base.equals(base))
        assertFalse(base.equals(null))
        assertFalse(base.equals("not a thread"))
        // The avatar compares by content, not by reference.
        assertEquals(base, base.copy(avatarBytes = byteArrayOf(1)))
    }

    @Test
    fun toStringNamesTheClassAndCarriesTheIdentity() {
        val text = base.toString()
        assertTrue(text.contains("MessageThread"), text)
        assertTrue(text.contains("threadId=t"), text)
        assertTrue(text.contains("title=title"), text)
    }
}
