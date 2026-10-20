//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.app

import ai.roleswitch.android.data.database.AppDatabase
import ai.roleswitch.android.data.seed.RoleSeedData
import ai.roleswitch.android.settings.decodeToolIds
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the Swift-facing facades through the same code paths the iOS app does —
 * on the JVM, where a real Room instance is cheap.
 */
class AppContainerTest {

    private lateinit var db: AppDatabase
    private lateinit var container: AppContainer

    @BeforeTest
    fun setUp() {
        val dir = java.nio.file.Files.createTempDirectory("roleswitch-container")
        db = Room.databaseBuilder<AppDatabase>(name = dir.resolve("test.sqlite").toString())
            .setDriver(BundledSQLiteDriver())
            .build()
        container = AppContainer(
            database = db,
            settingsManager = ai.roleswitch.android.settings.SettingsManager(
                ai.roleswitch.android.settings.InMemoryPreferenceDataStore()
            ),
            oaiService = ai.roleswitch.android.service.OAIService(),
            llmService = ai.roleswitch.android.service.LLMService(
                oaiService = ai.roleswitch.android.service.OAIService(),
                settingsManager = ai.roleswitch.android.settings.SettingsManager(
                    ai.roleswitch.android.settings.InMemoryPreferenceDataStore()
                ),
                messageDao = db.messageDao(),
                threadDao = db.messageThreadDao(),
                mcpToolDao = db.mcpToolDao(),
                mcpService = ai.roleswitch.android.service.mcp.McpService()
            ),
            mcpService = ai.roleswitch.android.service.mcp.McpService()
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun threadForIdIsAPlaceholderThatResolvesToGlobals() = runTest {
        val placeholder = container.threadForId("any-id")
        assertEquals("any-id", placeholder.threadId)
        assertFalse(placeholder.useCustomSettings)
        assertTrue(placeholder.title.isEmpty())
    }

    @Test
    fun createThreadAndMessageHelpersBuildPlainRows() {
        val thread = container.createThreadWithPrompt(title = "T", prompt = "P")
        assertEquals("T", thread.title)
        assertEquals("P", thread.prompt)

        val message = container.createMessage(content = "c", isUser = true, threadId = "t")
        assertEquals("c", message.content)
        assertTrue(message.isUser)
    }

    @Test
    fun applyThreadOverridesWritesEveryColumnTheSwiftScreenEdits() = runTest {
        db.seedDefaultThread(locale = "en")
        val thread = db.getAllThreads().first()

        container.applyThreadOverrides(
            thread = thread,
            apiUrl = "http://10.0.2.2:1234/v1",
            apiKey = "sk-1",
            model = "gpt-test",
            enableVision = true,
            messageLimit = 33,
            temperature = "0.5",
            topP = "0.8",
            n = "3",
            maxCompletionTokens = "128",
            presencePenalty = "0.1",
            frequencyPenalty = "0.2",
            reasoningEffort = "high",
            embedTime = true,
            keepLastAttachment = true,
            streamingMode = false,
            language = "ru",
            voice = "Samantha",
            useVosk = true,
            allowInterruptions = true,
            useSystemTts = true,
            useExternalTts = false,
            externalTtsVoice = "alloy",
            externalTtsAPI = "http://voice:9000/v1",
            externalTtsKey = "sk-voice",
            useOnDevice = true,
            litertModel = "gemma-4-E2B-it",
            litertTopK = "64",
            litertTopP = "0.95",
            litertTemperature = "1.0",
            litertMaxTokens = "4000",
            litertAccelerator = "gpu",
            litertEnableThinking = true
        )

        val row = db.getAllThreads().first()
        assertTrue(row.useCustomSettings)
        assertEquals("http://10.0.2.2:1234/v1", row.customApiUrl)
        assertEquals("sk-1", row.customApiKey)
        assertEquals("gpt-test", row.customModel)
        assertEquals(true, row.customEnableVision)
        assertEquals(33, row.customMessageLimit)
        assertEquals("0.5", row.customTemperature)
        assertEquals("0.8", row.customTopP)
        assertEquals("3", row.customN)
        assertEquals("128", row.customMaxCompletionTokens)
        assertEquals("0.1", row.customPresencePenalty)
        assertEquals("0.2", row.customFrequencyPenalty)
        assertEquals("high", row.customReasoningEffort)
        assertEquals(true, row.customEmbedTime)
        assertEquals(true, row.customKeepLastAttachment)
        assertEquals(false, row.customStreamingMode)
        assertEquals("ru", row.customLanguage)
        assertEquals("Samantha", row.customVoice)
        assertEquals(true, row.customUseVosk)
        assertEquals(true, row.customAllowInterruptions)
        assertEquals(true, row.customUseSystemTts)
        assertEquals(false, row.customUseExternalTts)
        assertEquals("alloy", row.customExternalTtsVoice)
        assertEquals("http://voice:9000/v1", row.customExternalTtsAPI)
        assertEquals("sk-voice", row.customExternalTtsKey)
        assertEquals(true, row.customUseLiteRt)
        assertEquals("gemma-4-E2B-it", row.customLitertModel)
        assertEquals("64", row.customLitertTopK)
        assertEquals("0.95", row.customLitertTopP)
        assertEquals("1.0", row.customLitertTemperature)
        assertEquals("4000", row.customLitertMaxTokens)
        assertEquals("gpu", row.customLitertAccelerator)
        assertEquals(true, row.customLitertEnableThinking)
    }

    @Test
    fun theToolSelectionSurvivesAnOverridesSnapshot() = runTest {
        db.seedDefaultThread(locale = "en")
        val thread = db.getAllThreads().first()
        db.applyThreadToolSelection(thread, listOf("t-1"))
        val withTools = db.getAllThreads().first()

        container.applyThreadOverrides(
            thread = withTools,
            apiUrl = "", apiKey = "", model = "", enableVision = false, messageLimit = 50,
            temperature = "", topP = "", n = "", maxCompletionTokens = "",
            presencePenalty = "", frequencyPenalty = "", reasoningEffort = "",
            embedTime = false, keepLastAttachment = false, streamingMode = true,
            language = "en", voice = "", useVosk = false, allowInterruptions = false,
            useSystemTts = false, useExternalTts = false, externalTtsVoice = "",
            externalTtsAPI = "", externalTtsKey = "", useOnDevice = false,
            litertModel = "", litertTopK = "", litertTopP = "", litertTemperature = "",
            litertMaxTokens = "", litertAccelerator = "", litertEnableThinking = false
        )

        // withCustomSettingsEnabled pointedly ignores the snapshot's (empty) tools.
        assertEquals(listOf("t-1"), decodeToolIds(db.getAllThreads().first().customToolIds))
    }

    @Test
    fun getAllThreadsListsWhatWasSeeded() = runTest {
        db.seedDefaultThread(locale = "en")
        assertEquals(listOf(RoleSeedData.DEFAULT_THREAD_ID), container.database.getAllThreads().map { it.threadId })
    }
}
