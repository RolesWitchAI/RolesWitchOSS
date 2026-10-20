//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import ai.roleswitch.android.data.dao.InMemoryMessageDao
import ai.roleswitch.android.data.dao.InMemoryMessageThreadDao
import ai.roleswitch.android.data.dao.InMemoryMcpToolDao
import ai.roleswitch.android.data.model.DocumentAttachment
import ai.roleswitch.android.data.model.Message
import ai.roleswitch.android.service.mcp.McpService
import ai.roleswitch.android.settings.InMemoryPreferenceDataStore
import ai.roleswitch.android.settings.SettingsManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LLMServiceConsolidateMessagesTest {

    private val service = LLMService(
        oaiService = OAIService(),
        settingsManager = SettingsManager(InMemoryPreferenceDataStore()),
        messageDao = InMemoryMessageDao(),
        threadDao = InMemoryMessageThreadDao(),
        mcpToolDao = InMemoryMcpToolDao(),
        mcpService = McpService(HttpClient(MockEngine { respondOk() }))
    )

    private fun u(
        content: String,
        vararg images: ByteArray,
        documents: List<DocumentAttachment> = emptyList(),
        createdAt: Long = 0L
    ): Message = Message(
        content = content,
        createdAt = createdAt,
        isUser = true,
        imageData = images.toList(),
        documents = documents
    )

    private fun a(content: String, createdAt: Long = 0L): Message =
        Message(content = content, createdAt = createdAt, isUser = false)

    @Test
    fun anEmptyHistoryYieldsNothing() {
        assertTrue(service.consolidateMessages(emptyList(), 50, embedTime = false).isEmpty())
    }

    @Test
    fun alternatingMessagesSurviveInOrder() {
        val out = service.consolidateMessages(listOf(u("Hello"), a("Hi"), u("How are you?")), 50, false)
        assertEquals(3, out.size)
        assertEquals(LLMMessage.LLMRole.USER, out[0].role)
        assertEquals("Hello", out[0].content)
        assertEquals(LLMMessage.LLMRole.ASSISTANT, out[1].role)
        assertEquals("Hi", out[1].content)
        assertEquals(LLMMessage.LLMRole.USER, out[2].role)
        assertEquals("How are you?", out[2].content)
    }

    @Test
    fun consecutiveSameRoleMessagesMergeInChronologicalOrder() {
        val out = service.consolidateMessages(listOf(u("one"), u("two"), a("reply")), 50, false)
        assertEquals(2, out.size)
        assertEquals(LLMMessage.LLMRole.USER, out[0].role)
        assertEquals("one\ntwo", out[0].content)
        assertEquals("reply", out[1].content)
    }

    @Test
    fun theLimitKeepsTheNewestMergedMessages() {
        val history = listOf(u("u1"), a("a1"), u("u2"), a("a2"), u("u3"), a("a3"))
        val out = service.consolidateMessages(history, 3, false)
        assertEquals(listOf("a2", "u3", "a3"), out.map { it.content })
    }

    @Test
    fun aZeroLimitYieldsNothing() {
        assertTrue(service.consolidateMessages(listOf(u("x")), 0, false).isEmpty())
    }

    @Test
    fun onlyTheNewestMessageKeepsItsImages() {
        val old = byteArrayOf(1)
        val newestA = byteArrayOf(2)
        val newestB = byteArrayOf(3)
        val out = service.consolidateMessages(
            listOf(u("old", old), a("mid"), u("new", newestA, newestB)),
            50,
            false
        )
        assertTrue(out[0].imageData.isEmpty())
        assertTrue(out[2].imageData.size == 2)
        assertTrue(newestA.contentEquals(out[2].imageData[0]))
        assertTrue(newestB.contentEquals(out[2].imageData[1]))
    }

    @Test
    fun imagesOutsideTheNewestMessageWindowAreDroppedEntirely() {
        // The window is the single newest message; an image one turn back does not reach
        // the engine at all.
        val out = service.consolidateMessages(listOf(u("look", byteArrayOf(9)), a("reply")), 50, false)
        assertTrue(out[0].imageData.isEmpty())
        assertTrue(out[1].imageData.isEmpty())
    }

    @Test
    fun mergedUserMessagesKeepOnlyTheNewestImages() {
        val out = service.consolidateMessages(
            listOf(u("first", byteArrayOf(1)), u("second", byteArrayOf(2))),
            50,
            false
        )
        assertEquals("first\nsecond", out[0].content)
        assertEquals(1, out[0].imageData.size)
        assertEquals(2, out[0].imageData[0][0])
    }

    @Test
    fun onlyTheNewestDocumentMessageCarriesItsExtractedText() {
        val docs = listOf(DocumentAttachment(fileName = "notes.pdf", sizeBytes = 10, extractedText = "the text"))
        val out = service.consolidateMessages(
            listOf(u("old question", documents = docs), a("mid answer"), u("new question", documents = docs)),
            50,
            false
        )
        assertEquals("old question", out[0].content)
        assertEquals("new question\n\n[Attached document: notes.pdf]\nthe text\n[/document]", out[2].content)
    }

    @Test
    fun aDocumentWithNoTextSaysSoExplicitly() {
        val docs = listOf(DocumentAttachment(fileName = "", sizeBytes = 0, extractedText = "  "))
        val out = service.consolidateMessages(listOf(u("q", documents = docs)), 50, false)
        assertEquals(
            "q\n\n[Attached document: document]\n(no text could be extracted from this PDF)\n[/document]",
            out[0].content
        )
    }

    @Test
    fun multipleDocumentsAreJoinedWithBlankLines() {
        val docs = listOf(
            DocumentAttachment(fileName = "a.pdf", sizeBytes = 1, extractedText = "A text"),
            DocumentAttachment(fileName = "b.pdf", sizeBytes = 2, extractedText = "B text")
        )
        val out = service.consolidateMessages(listOf(u("q", documents = docs)), 50, false)
        assertEquals(
            "q\n\n[Attached document: a.pdf]\nA text\n[/document]\n\n[Attached document: b.pdf]\nB text\n[/document]",
            out[0].content
        )
    }

    @Test
    fun embedTimePrefixesOnlyUserMessagesWithATimestamp() {
        val out = service.consolidateMessages(
            listOf(u("hello", createdAt = 0L), a("hi", createdAt = 0L)),
            50,
            embedTime = true
        )
        assertTrue(
            out[0].content.matches(Regex("\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}] hello")),
            "expected a timestamped prefix on '${out[0].content}'"
        )
        assertEquals("hi", out[1].content)
    }

    @Test
    fun withoutEmbedTimeNoTimestampIsAdded() {
        val out = service.consolidateMessages(listOf(u("hello", createdAt = 0L)), 50, embedTime = false)
        assertEquals("hello", out[0].content)
    }
}
