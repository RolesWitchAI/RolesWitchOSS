//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import ai.roleswitch.android.data.dao.InMemoryMcpToolDao
import ai.roleswitch.android.data.dao.InMemoryMessageDao
import ai.roleswitch.android.data.dao.InMemoryMessageThreadDao
import ai.roleswitch.android.data.model.DocumentInput
import ai.roleswitch.android.data.model.Message
import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.service.mcp.McpService
import ai.roleswitch.android.settings.EffectiveSettings
import ai.roleswitch.android.settings.InMemoryPreferenceDataStore
import ai.roleswitch.android.settings.SettingsManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LLMServiceTest {

    /** Records what the service sends and streams back canned output. */
    private class FakeEngine : InferenceEngine {
        var behavior: suspend () -> GenerationResult = { GenerationResult("ok", "") }
        var streamChunks: List<String> = emptyList()
        var hang = false
        val seenPrompts = mutableListOf<String>()
        val seenSettings = mutableListOf<EffectiveSettings>()
        var seenMessages: List<LLMMessage> = emptyList()
        var seenOnChunk: ((String) -> Unit)? = null
        val forwardedChunks = mutableListOf<String>()

        override suspend fun generate(
            messages: List<LLMMessage>,
            systemPrompt: String,
            settings: EffectiveSettings,
            onChunk: ((String) -> Unit)?,
            toolSpecs: List<ai.roleswitch.android.service.mcp.McpToolSpec>,
            onToolCall: ((Boolean) -> Unit)?,
            callTool: (suspend (String, String) -> String)?
        ): GenerationResult {
            seenMessages = messages
            seenPrompts += systemPrompt
            seenSettings += settings
            seenOnChunk = onChunk
            streamChunks.forEach {
                onChunk?.invoke(it)
                forwardedChunks += it
            }
            if (hang) awaitCancellation()
            return behavior()
        }

        override fun cancel() = Unit
    }

    private class Harness(val engine: FakeEngine = FakeEngine()) {
        val messageDao = InMemoryMessageDao()
        val threadDao = InMemoryMessageThreadDao()
        val settings = SettingsManager(InMemoryPreferenceDataStore())
        val service = LLMService(
            oaiService = OAIService(),
            settingsManager = settings,
            messageDao = messageDao,
            threadDao = threadDao,
            mcpToolDao = InMemoryMcpToolDao(),
            mcpService = McpService(HttpClient(MockEngine { respondOk() })),
            onDeviceEngine = engine
        )

        init {
            settings.setUseOnDevice(true)
        }
    }

    private fun thread(title: String = "T", prompt: String = ""): MessageThread =
        MessageThread(threadId = "thread-1", title = title, prompt = prompt)

    @Test
    fun aTurnStreamsPersistsAndCompletes() = runTest {
        val h = Harness()
        h.threadDao.insert(thread(prompt = "Be terse."))
        h.engine.streamChunks = listOf("Hel", "lo")
        h.engine.behavior = { GenerationResult(content = "Hello", reasoning = "because") }

        val completed = mutableListOf<CompletedResponse>()
        backgroundScope.launch { h.service.responseCompleted.collect { completed += it } }
        runCurrent()

        h.service.sendMessage("Hi", "thread-1")
        runCurrent() // let the SharedFlow collector drain its buffered emission

        assertEquals(listOf("Hi", "Hello"), h.messageDao.messages.map { it.content })
        val assistant = h.messageDao.messages.last()
        assertEquals(false, assistant.isUser)
        assertEquals("because", assistant.reasoning)
        assertEquals(listOf(CompletedResponse("thread-1", "Hello")), completed)
        // The engine's chunk callback is what feeds the live bubble; StateFlow conflation
        // makes the intermediate values themselves unobservable by design.
        assertEquals(listOf("Hel", "lo"), h.engine.forwardedChunks)
        // Teardown cleared the shared progress state.
        assertEquals(false, h.service.isStreaming.value)
        assertEquals("", h.service.streamingResponse.value)
        assertNull(h.service.currentThreadId.value)
    }

    @Test
    fun theThreadPromptIsTheSystemPromptAndFallsBackToTheDefault() = runTest {
        val h = Harness()
        h.threadDao.insert(thread(prompt = "Answer in rhyme."))
        h.service.sendMessage("Hi", "thread-1")
        assertEquals("Answer in rhyme.", h.engine.seenPrompts.single())

        val h2 = Harness()
        h2.threadDao.insert(thread(prompt = ""))
        h2.service.sendMessage("Hi", "thread-1")
        assertEquals("You are a helpful assistant.", h2.engine.seenPrompts.single())
    }

    @Test
    fun embedTimeAddsTheCurrentTimeToTheSystemPrompt() = runTest {
        val h = Harness()
        h.settings.setEmbedTime(true)
        h.threadDao.insert(thread(prompt = "P"))
        h.service.sendMessage("Hi", "thread-1")
        assertTrue(h.engine.seenPrompts.single().startsWith("P\n\nCurrent date and time: "), h.engine.seenPrompts.single())
    }

    @Test
    fun oneShotModePassesNoChunkCallbackAndStreamsNothing() = runTest {
        val h = Harness()
        h.settings.setStreamingMode(false)
        h.engine.streamChunks = listOf("partial")
        h.threadDao.insert(thread())
        h.service.sendMessage("Hi", "thread-1")

        assertNull(h.engine.seenOnChunk)
        assertEquals("", h.service.streamingResponse.value)
        assertEquals("ok", h.messageDao.messages.last().content)
    }

    @Test
    fun thinkTagsAreSplitOutOfTheReply() = runTest {
        val h = Harness()
        h.engine.behavior = { GenerationResult(content = "<think>the reasoning</think>the answer", reasoning = "") }
        h.threadDao.insert(thread())
        h.service.sendMessage("Hi", "thread-1")
        val assistant = h.messageDao.messages.last()
        assertEquals("the answer", assistant.content)
        assertEquals("the reasoning", assistant.reasoning)
    }

    @Test
    fun channelStyleThinkingIsAlsoSplit() = runTest {
        val h = Harness()
        h.engine.behavior = { GenerationResult(content = "<|channel>internal thoughts<channel|>visible text", reasoning = "") }
        h.threadDao.insert(thread())
        h.service.sendMessage("Hi", "thread-1")
        val assistant = h.messageDao.messages.last()
        assertEquals("visible text", assistant.content)
        assertEquals("internal thoughts", assistant.reasoning)
    }

    @Test
    fun engineReportedReasoningWinsOverTagExtraction() = runTest {
        val h = Harness()
        h.engine.behavior = { GenerationResult(content = "plain", reasoning = "from the engine") }
        h.threadDao.insert(thread())
        h.service.sendMessage("Hi", "thread-1")
        assertEquals("from the engine", h.messageDao.messages.last().reasoning)
    }

    @Test
    fun aToolTraceIsAppendedToTheReasoning() = runTest {
        val h = Harness()
        h.engine.behavior = {
            GenerationResult(
                content = "done",
                reasoning = "thought",
                toolCalls = listOf(ToolInvocation(label = "srv__fn", arguments = "{\"a\":1}", result = "42"))
            )
        }
        h.threadDao.insert(thread())
        h.service.sendMessage("Hi", "thread-1")
        val reasoning = h.messageDao.messages.last().reasoning
        assertEquals("thought\n\n→ srv__fn({\"a\":1})\n← 42", reasoning)
    }

    @Test
    fun longToolTracesAreClippedForTheCollapsibleBlock() = runTest {
        val h = Harness()
        val longArgs = "{\"q\":\"${"x".repeat(300)}\"}"
        val longResult = "y".repeat(600)
        h.engine.behavior = {
            GenerationResult(
                content = "done",
                reasoning = "",
                toolCalls = listOf(ToolInvocation(label = "srv__fn", arguments = longArgs, result = longResult))
            )
        }
        h.threadDao.insert(thread())
        h.service.sendMessage("Hi", "thread-1")
        val reasoning = h.messageDao.messages.last().reasoning
        // The trace alone, with the args clipped at 200 and the result at 400 chars.
        assertTrue(reasoning.contains("…"), reasoning.take(60))
        assertTrue(reasoning.lines().none { it.length > 460 }, "an unclipped line survived: ${reasoning.length}")
        assertTrue(reasoning.startsWith("→ srv__fn("))
    }

    @Test
    fun anUnclosedThinkTagLeavesTheReplyWhole() = runTest {
        val h = Harness()
        h.engine.behavior = { GenerationResult(content = "<think>never closed", reasoning = "") }
        h.threadDao.insert(thread())
        h.service.sendMessage("Hi", "thread-1")
        // No matching end tag: the pair never splits, so the text survives as-is.
        assertEquals("<think>never closed", h.messageDao.messages.last().content)
        assertEquals("", h.messageDao.messages.last().reasoning)
    }

    @Test
    fun anUnclosedChannelTagLeavesTheReplyWholeToo() = runTest {
        val h = Harness()
        h.engine.behavior = { GenerationResult(content = "<|channel>dangling", reasoning = "") }
        h.threadDao.insert(thread())
        h.service.sendMessage("Hi", "thread-1")
        assertEquals("<|channel>dangling", h.messageDao.messages.last().content)
    }

    @Test
    fun hardErrorsPropagateAndTheTurnIsDiscarded() = runTest {
        val h = Harness()
        h.engine.behavior = { throw OAIError.InvalidResponse(reason = "boom") }
        h.threadDao.insert(thread())
        val discarded = mutableListOf<String>()
        backgroundScope.launch { h.service.responseDiscarded.collect { discarded += it } }
        runCurrent()

        val error = assertFailsWith<OAIError.InvalidResponse> { h.service.sendMessage("Hi", "thread-1") }
        runCurrent()
        assertTrue(error.message!!.contains("boom"))
        assertEquals(1, h.messageDao.messages.size) // the user row only
        assertEquals(listOf("thread-1"), discarded)
        assertEquals(false, h.service.isStreaming.value)
    }

    @Test
    fun transientErrorsAreSwallowed() = runTest {
        val h = Harness()
        h.engine.behavior = { throw OAIError.TransientError }
        h.threadDao.insert(thread())
        val discarded = mutableListOf<String>()
        backgroundScope.launch { h.service.responseDiscarded.collect { discarded += it } }
        runCurrent()

        h.service.sendMessage("Hi", "thread-1") // must not throw
        runCurrent()
        assertEquals(1, h.messageDao.messages.size)
        assertEquals(listOf("thread-1"), discarded)
        assertEquals(false, h.service.isStreaming.value)
    }

    @Test
    fun unexpectedErrorsAreWrappedAsInvalidResponses() = runTest {
        val h = Harness()
        h.engine.behavior = { throw IllegalStateException("native crash") }
        h.threadDao.insert(thread())

        val error = assertFailsWith<OAIError.InvalidResponse> { h.service.sendMessage("Hi", "thread-1") }
        assertTrue(error.message!!.contains("native crash"), error.message)
    }

    @Test
    fun anEmptyReplyIsNotPersisted() = runTest {
        val h = Harness()
        h.engine.behavior = { GenerationResult(content = "", reasoning = "") }
        h.threadDao.insert(thread())
        val discarded = mutableListOf<String>()
        backgroundScope.launch { h.service.responseDiscarded.collect { discarded += it } }
        runCurrent()

        h.service.sendMessage("Hi", "thread-1")
        runCurrent()
        assertEquals(1, h.messageDao.messages.size)
        assertEquals(listOf("thread-1"), discarded)
    }

    @Test
    fun onDeviceHistoryIsCappedAtTheEngineLimit() = runTest {
        val h = Harness()
        h.threadDao.insert(thread())
        repeat(300) { index ->
            h.messageDao.insert(Message(content = "m$index", isUser = index % 2 == 0, threadId = "thread-1"))
        }
        // "Unlimited" — the value the remote picker offers and the engine must not honour.
        h.settings.setMessageLimit(100000)
        h.service.sendMessage("Hi", "thread-1")
        assertTrue(h.engine.seenMessages.size <= 120, "engine saw ${h.engine.seenMessages.size}")
    }

    @Test
    fun sendMessageAttachesImagesToTheNewestUserMessage() = runTest {
        val h = Harness()
        h.threadDao.insert(thread())
        val image = byteArrayOf(7, 7, 7)
        h.service.sendMessage("look", "thread-1", images = listOf(image))
        val lastUser = h.engine.seenMessages.last { it.role == LLMMessage.LLMRole.USER }
        assertEquals(1, lastUser.imageData.size)
        assertTrue(image.contentEquals(lastUser.imageData[0]))
    }

    @Test
    fun aTurnStartedForAnotherThreadIsNotCancelledByItsCancel() = runTest {
        val h = Harness()
        h.engine.hang = true
        h.threadDao.insert(thread())
        backgroundScope.launch { h.service.sendMessage("Hi", "thread-1") }
        // The turn hops through Dispatchers.Default (extractDocuments) before it reaches
        // the engine, so it needs a beat of real time to start.
        withTimeout(5_000) {
            while (!h.service.isStreaming.value) kotlinx.coroutines.delay(10)
        }
        assertEquals(true, h.service.isStreaming.value)

        h.service.cancelRequestForThread("some-other-thread")
        assertEquals(true, h.service.isStreaming.value, "a different thread's cancel must not kill the live turn")

        h.service.cancelRequestForThread("thread-1")
        withTimeout(5_000) {
            while (h.service.isStreaming.value) kotlinx.coroutines.delay(10)
        }
        assertEquals(false, h.service.isStreaming.value)
    }

    @Test
    fun processLLMResponseRunsAgainstThePersistedHistory() = runTest {
        val h = Harness()
        h.threadDao.insert(thread())
        h.messageDao.insert(Message(content = "already typed", isUser = true, threadId = "thread-1"))
        h.service.processLLMResponse("already typed", "thread-1")
        // The user row is untouched and the reply is appended.
        assertEquals(listOf("already typed", "ok"), h.messageDao.messages.map { it.content })
    }

    @Test
    fun processLLMResponseWithImagesOnAnEmptyThreadHasNoUserMessageToAttachTo() = runTest {
        val h = Harness()
        h.threadDao.insert(thread())
        // No history at all: there is no consolidated user message to hang images on,
        // and the turn still runs.
        h.service.processLLMResponse("typed elsewhere", "thread-1", images = listOf(byteArrayOf(1)))
        assertEquals(listOf("ok"), h.messageDao.messages.map { it.content })
        assertEquals(emptyList(), h.engine.seenMessages.lastOrNull { it.role == LLMMessage.LLMRole.USER }?.imageData ?: emptyList())
    }

    @Test
    fun cancellingWhileIdleIsSafe() {
        val h = Harness()
        h.service.cancelRequest() // nothing in flight — must be a no-op
        assertEquals(false, h.service.isStreaming.value)
    }

    @Test
    fun documentsAreRecordedAsAttachments() = runTest {
        val h = Harness()
        h.threadDao.insert(thread())
        // Not a PDF, on every platform: the pickers' failure mode for a corrupt file.
        h.service.sendMessage("read this", "thread-1", documents = listOf(DocumentInput("broken.pdf", "not a pdf".encodeToByteArray())))

        val user = h.messageDao.messages.first()
        assertEquals(1, user.documents.size)
        assertEquals("broken.pdf", user.documents[0].fileName)
        assertEquals("not a pdf".length.toLong(), user.documents[0].sizeBytes)
        assertEquals("", user.documents[0].extractedText)
        assertEquals(0, user.documents[0].pageCount)
    }
}
