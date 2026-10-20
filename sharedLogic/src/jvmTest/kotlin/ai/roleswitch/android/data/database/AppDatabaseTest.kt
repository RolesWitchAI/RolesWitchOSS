//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.database

import ai.roleswitch.android.data.model.McpTool
import ai.roleswitch.android.data.model.Message
import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.data.seed.RoleSeedData
import ai.roleswitch.android.settings.EffectiveSettings
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The database-behaviour tests run against a real Room instance through the bundled
 * SQLite driver — the same setup the iOS factory uses — in a per-test temp file.
 */
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        val dir = java.nio.file.Files.createTempDirectory("roleswitch-db")
        db = Room.databaseBuilder<AppDatabase>(name = dir.resolve("test.sqlite").toString())
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun seedingTheDefaultThreadIsIdempotent() = runTest {
        db.seedDefaultThread(locale = "en", defaultAvatarBytes = byteArrayOf(1, 2, 3))
        db.seedDefaultThread(locale = "en") // second run must not duplicate or throw

        val threads = db.getAllThreads()
        assertEquals(1, threads.size)
        assertEquals(RoleSeedData.DEFAULT_THREAD_ID, threads[0].threadId)
        assertEquals("Roles Witch", threads[0].title)
        assertTrue(threads[0].avatarBytes!!.contentEquals(byteArrayOf(1, 2, 3)))
        // The seeded roles came along, and the welcome message is the role's greeting.
        assertEquals(listOf("roles_witch"), db.roleDao().getAllRoles().map { it.id })
        val messages = db.messageDao().getMessagesByThread(RoleSeedData.DEFAULT_THREAD_ID)
        assertEquals(1, messages.size)
        assertFalse(messages[0].isUser)
        assertEquals("Hello, I'm Roles Witch and I'm your smart assistant.", messages[0].content)
    }

    @Test
    fun anExistingThreadRowIsOnlyRepairedNotReplaced() = runTest {
        db.seedDefaultThread(locale = "en")
        val renamed = db.getAllThreads().first().copy(title = "My Witch", prompt = "")
        db.messageThreadDao().update(renamed)

        db.seedDefaultThread(locale = "en")

        val thread = db.getAllThreads().first()
        assertEquals("My Witch", thread.title, "the user owns the row now")
        assertTrue(thread.prompt.isNotBlank(), "a blank prompt is repaired from the seed")
    }

    @Test
    fun seedingRolesIsIdempotentAndRespectsTheLocale() = runTest {
        assertTrue(db.seedRolesIfEmpty("ru"))
        assertFalse(db.seedRolesIfEmpty("ru")) // already populated
        val ru = db.roleDao().getAllRoles().single()
        assertEquals("Привет, я Roles Witch и я твой умный ассистент.", ru.greeting)
    }

    @Test
    fun stressSeedingFillsTheThreadOnceAndBackdatesTheWelcome() = runTest {
        db.seedDefaultThread(locale = "en")
        db.seedStressMessagesInDefaultThread(50)
        assertEquals(51, db.messageDao().countByThread(RoleSeedData.DEFAULT_THREAD_ID))

        db.seedStressMessagesInDefaultThread(50) // no-op: the thread already has history
        assertEquals(51, db.messageDao().countByThread(RoleSeedData.DEFAULT_THREAD_ID))

        val messages = db.messageDao().getMessagesByThread(RoleSeedData.DEFAULT_THREAD_ID)
        assertEquals(messages.minOf { it.createdAt }, messages.first().createdAt)
        assertEquals("Hello, I'm Roles Witch and I'm your smart assistant.", messages.first().content)
        // 50 stress rows start with a user row (index 0) and end on an assistant row
        // (index 49) — the alternation puts an even count's newest on the model side.
        assertTrue(messages[1].isUser)
        assertFalse(messages.last().isUser)
    }

    @Test
    fun deletingAThreadTakesItsMessagesWithIt() = runTest {
        db.seedDefaultThread(locale = "en")
        val thread = db.getAllThreads().first()
        db.messageDao().insert(Message(content = "hi", isUser = true, threadId = thread.threadId))
        assertTrue(db.messageDao().getMessagesByThread(thread.threadId).isNotEmpty())

        db.deleteThread(thread)
        assertTrue(db.messageDao().getMessagesByThread(thread.threadId).isEmpty())
        assertTrue(db.getAllThreads().isEmpty())
    }

    @Test
    fun updateThreadTitlePromptAndAvatarRefreshTheRow() = runTest {
        db.seedDefaultThread(locale = "en")
        val thread = db.getAllThreads().first()

        db.updateThreadTitleAndPrompt(thread, "New title", "New prompt")
        val renamed = db.getAllThreads().first()
        db.updateThreadAvatar(renamed, byteArrayOf(9))

        val updated = db.getAllThreads().first()
        assertEquals("New title", updated.title)
        assertEquals("New prompt", updated.prompt)
        assertTrue(updated.avatarBytes!!.contentEquals(byteArrayOf(9)))
        assertTrue(updated.updatedAt >= thread.updatedAt)
    }

    @Test
    fun applyThreadOverridesSnapshotsAndDisablesCleanly() = runTest {
        db.seedDefaultThread(locale = "en")
        val thread = db.getAllThreads().first()
        val snapshot = EffectiveSettings.global(
            ai.roleswitch.android.settings.SettingsManager(
                ai.roleswitch.android.settings.InMemoryPreferenceDataStore()
            )
        ).copy(selectedModel = "snapshot-model", messageLimit = 9)

        db.applyThreadOverrides(thread, snapshot)
        val overridden = db.getAllThreads().first()
        assertTrue(overridden.useCustomSettings)
        assertEquals("snapshot-model", overridden.customModel)
        assertEquals(9, overridden.customMessageLimit)

        db.disableThreadOverrides(overridden)
        val disabled = db.getAllThreads().first()
        assertFalse(disabled.useCustomSettings)
        // The columns survive for a re-enable.
        assertEquals("snapshot-model", disabled.customModel)
    }

    @Test
    fun theToolSelectionIsAppliedAgainstTheCurrentRowNotAStaleSnapshot() = runTest {
        db.seedDefaultThread(locale = "en")
        val stale = db.getAllThreads().first()
        // Someone renames the thread after the picker took its snapshot.
        db.updateThreadTitleAndPrompt(stale, "Renamed meanwhile", "p")

        db.applyThreadToolSelection(stale, listOf("tool-1", "tool-2"))

        val current = db.getAllThreads().first()
        assertEquals("Renamed meanwhile", current.title, "the rename must not be rolled back")
        assertEquals("tool-1,tool-2", current.customToolIds)

        db.applyThreadToolSelection(current, emptyList())
        assertNull(db.getAllThreads().first().customToolIds)
    }

    @Test
    fun upsertingToolsInsertsThenUpdatesInPlace() = runTest {
        db.upsertMcpTool(id = null, name = "Weather", url = "http://mcp.test/mcp", token = "t")
        val inserted = db.mcpToolDao().getAllTools().single()
        assertEquals("Weather", inserted.name)

        db.upsertMcpTool(id = inserted.id, name = "Weather v2", url = "http://mcp2.test/mcp", token = "")
        val updated = db.mcpToolDao().getAllTools().single()
        assertEquals("Weather v2", updated.name)
        assertEquals("http://mcp2.test/mcp", updated.url)
        assertEquals(inserted.createdAt, updated.createdAt, "list order stays stable")
    }

    @Test
    fun messageEditsPersist() = runTest {
        db.seedDefaultThread(locale = "en")
        val thread = db.getAllThreads().first()
        db.messageDao().insert(Message(content = "note", isUser = true, threadId = thread.threadId))
        val message = db.messageDao().getMessagesByThread(thread.threadId).last()

        db.setMessageFavorite(message, true)
        db.updateMessageContent(db.messageDao().getMessagesByThread(thread.threadId).last(), "edited note")

        val final = db.messageDao().getMessagesByThread(thread.threadId).last()
        assertEquals("edited note", final.content)
        assertTrue(final.favorite)
    }

    @Test
    fun searchingFindsNeedlesCaseInsensitively() = runTest {
        db.seedDefaultThread(locale = "en")
        val thread = db.getAllThreads().first()
        db.messageDao().insert(Message(content = "Обычное Привет сообщение", isUser = true, threadId = thread.threadId))

        val found = db.messageDao().searchMessages("привет", "привет", "ПРИВЕТ", "Привет", 10)
        assertEquals(1, found.size)
        assertTrue(found[0].content.contains("Привет"))
    }

    @Test
    fun clearAllDataWipesEveryTable() = runTest {
        db.seedDefaultThread(locale = "en")
        db.upsertMcpTool(id = null, name = "t", url = "u", token = "")
        assertTrue(db.getAllThreads().isNotEmpty())

        db.clearAllData()

        assertTrue(db.getAllThreads().isEmpty())
        assertTrue(db.messageDao().getMessagesByThread(RoleSeedData.DEFAULT_THREAD_ID).isEmpty())
        assertTrue(db.roleDao().getAllRoles().isEmpty())
        assertTrue(db.mcpToolDao().getAllTools().isEmpty())
    }
}
