//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.sqlite.SQLiteConnection
import ai.roleswitch.android.data.dao.McpToolDao
import ai.roleswitch.android.data.dao.MessageDao
import ai.roleswitch.android.data.dao.MessageThreadDao
import ai.roleswitch.android.data.dao.RoleDao
import ai.roleswitch.android.data.model.McpTool
import ai.roleswitch.android.data.model.Message
import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.data.model.Role
import ai.roleswitch.android.data.seed.RoleSeedData
import ai.roleswitch.android.settings.EffectiveSettings
import ai.roleswitch.android.settings.encodeToolIds
import ai.roleswitch.android.settings.withCustomSettingsEnabled
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.random.Random

@Database(
    entities = [Message::class, MessageThread::class, Role::class, McpTool::class],
    version = 28,
    exportSchema = false
)
@TypeConverters(Converters::class)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun messageThreadDao(): MessageThreadDao
    abstract fun roleDao(): RoleDao
    abstract fun mcpToolDao(): McpToolDao

    /**
     * Serializes the two seed entry points below, which are check-then-insert across a
     * suspension point and so are only idempotent if they cannot interleave.
     *
     * Settings → Reset used to seed directly *and* clear `hasCompletedOnboarding`, which
     * mounts onboarding — whose own seed started before the first had finished. Both
     * callers read an empty `message_threads`, both inserted the fixed
     * [RoleSeedData.DEFAULT_THREAD_ID], and the loser threw
     * `UNIQUE constraint failed: message_threads.threadId` — fatal on iOS, where
     * `SQLiteException` is in no `@Throws` list and so cannot cross into Swift.
     *
     * Reset no longer seeds, leaving onboarding as the sole caller, but this stays: the
     * guarantee is now "one caller, forever", and a re-mounted onboarding screen can
     * still start a second seed while the first is in flight (cancellation does not
     * reliably propagate across the Swift/Kotlin async bridge).
     *
     * A `Mutex` is not re-entrant, so the public functions take the lock and delegate to
     * [seedRolesIfEmptyLocked] — [seedDefaultThread] needs the role seed inside its own
     * hold, and must not re-enter through the public door.
     */
    private val seedMutex = Mutex()

    suspend fun seedRolesIfEmpty(locale: String): Boolean = seedMutex.withLock {
        seedRolesIfEmptyLocked(locale)
    }

    private suspend fun seedRolesIfEmptyLocked(locale: String): Boolean {
        val existing = roleDao().getAllRoles()
        if (existing.isEmpty()) {
            RoleSeedData.toRoles(locale).forEach { roleDao().insert(it) }
            return true
        }
        return false
    }

    /** Deletes a thread together with all of its messages. */
    suspend fun deleteThread(thread: MessageThread) {
        messageDao().deleteByThread(thread.threadId)
        messageThreadDao().delete(thread)
    }

    /** One-shot snapshot of every thread. Swift-friendly equivalent of
     *  `messageThreadDao().observeAllThreads().first()` (Kotlin `Flow.first()`
     *  does not export to ObjC/Swift). Used for title-uniqueness checks. */
    suspend fun getAllThreads(): List<MessageThread> =
        messageThreadDao().observeAllThreads().first()

    /** Wipes every persisted row (roles / threads / messages / MCP tools). Used by Settings → Reset. */
    suspend fun clearAllData() {
        messageDao().deleteAll()
        messageThreadDao().deleteAll()
        roleDao().deleteAll()
        mcpToolDao().deleteAll()
    }

    /**
     * Idempotently creates the default "Roles Witch" thread together with its
     * welcome message. Roles are seeded too if empty. Mirrors the Android onboarding seed.
     *
     * The avatar the thread starts with is [defaultAvatarBytes] — the platform's bundled
     * profile image, encoded by the caller — so the default thread ships with a picture that
     * is stored like any other thread's and editable like one. Only applied at insert time:
     * an existing row (e.g. the prompt-repair path below) keeps whatever it has.
     *
     * Only ever called from onboarding. The thread is deletable like any other, so seeding
     * it on every launch would quietly undo that on the next start; a reset is how you get
     * it back — not because reset seeds, but because it returns the user to onboarding.
     */
    suspend fun seedDefaultThread(locale: String, defaultAvatarBytes: ByteArray? = null): Unit = seedMutex.withLock {
        seedRolesIfEmptyLocked(locale)
        // Prompt and welcome message both come from the seed data at [locale], so the
        // thread cannot end up with its prompt in one language and its first message in
        // another — which is what happened while the message was a platform string
        // resolved against the live system language.
        val witch = RoleSeedData.seedRoles
            .firstOrNull { it.id == "roles_witch" }
            ?.let { RoleSeedData.getTranslation(it, locale) }
        val prompt = witch?.prompt ?: ""
        val welcomeMessage = witch?.greeting ?: ""
        val existing = messageThreadDao().getThreadById(RoleSeedData.DEFAULT_THREAD_ID)
        if (existing != null) {
            // Leave the row otherwise untouched — the user owns it now.
            if (existing.prompt.isBlank() && prompt.isNotBlank()) {
                messageThreadDao().update(existing.copy(prompt = prompt))
            }
        } else {
            messageThreadDao().insert(
                MessageThread(
                    threadId = RoleSeedData.DEFAULT_THREAD_ID,
                    title = "Roles Witch",
                    prompt = prompt,
                    avatarBytes = defaultAvatarBytes
                )
            )
            // Guarded: an upgraded install that never re-seeded its roles has no welcome
            // text to write, and an empty first message reads as a broken thread.
            if (welcomeMessage.isNotEmpty()) {
                messageDao().insert(
                    Message(
                        content = welcomeMessage,
                        isUser = false,
                        threadId = RoleSeedData.DEFAULT_THREAD_ID
                    )
                )
            }
        }
    }

    /**
     * TEST SEED — fills [threadId] with [count] alternating user/assistant messages so the
     * chat screen can be exercised with a long history (scrolling, paging, memory).
     *
     * No-op unless the thread holds at most one message (the welcome one), so repeated
     * onboarding runs don't stack multiple batches. Timestamps are spaced one minute apart
     * ending "now", keeping `ORDER BY createdAt ASC` stable and the newest message last.
     * Inserted in chunks of 200 rather than one row at a time: 1000 single-row transactions
     * take seconds on a real device.
     */
    suspend fun seedStressMessagesInDefaultThread(count: Int) =
        seedStressMessages(RoleSeedData.DEFAULT_THREAD_ID, count)

    /** @see seedStressMessagesInDefaultThread */
    suspend fun seedStressMessages(threadId: String, count: Int) {
        if (count <= 0) return
        if (messageDao().countByThread(threadId) > 1) return

        val random = Random(threadId.hashCode())
        val now = Clock.System.now().toEpochMilliseconds()
        val stepMillis = 60_000L
        val messages = (0 until count).map { index ->
            val isUser = index % 2 == 0
            val pool = if (isUser) StressSeedText.userLines else StressSeedText.assistantLines
            val createdAt = now - (count - index) * stepMillis
            Message(
                content = pool[random.nextInt(pool.size)],
                createdAt = createdAt,
                updatedAt = createdAt,
                isUser = isUser,
                threadId = threadId
            )
        }
        messages.chunked(200).forEach { messageDao().insertAll(it) }

        // The welcome message was stamped "now", which would sort it *below* the whole
        // backdated batch and leave the thread opening on a greeting. Push it in front.
        val firstAt = messages.first().createdAt
        val seeded = messages.mapTo(mutableSetOf(), Message::id)
        messageDao().getMessagesByThread(threadId)
            .filter { it.createdAt >= firstAt && it.id !in seeded }
            .forEach { messageDao().update(it.copy(createdAt = firstAt - stepMillis, updatedAt = firstAt - stepMillis)) }
    }

    /** Updates title + prompt of an existing thread. */
    suspend fun updateThreadTitleAndPrompt(thread: MessageThread, title: String, prompt: String) {
        messageThreadDao().update(
            thread.copy(title = title, prompt = prompt, updatedAt = Clock.System.now().toEpochMilliseconds())
        )
    }

    /** Updates the avatar bytes of an existing thread. */
    suspend fun updateThreadAvatar(thread: MessageThread, avatarBytes: ByteArray?) {
        messageThreadDao().update(
            thread.copy(avatarBytes = avatarBytes, updatedAt = Clock.System.now().toEpochMilliseconds())
        )
    }

    /**
     * Updates the Live2D character of an existing thread. A null [cubismId] puts the
     * thread back on its [MessageThread.avatarBytes] photo. Android-only feature; the
     * column simply stays null on iOS.
     */
    suspend fun updateThreadCubismId(thread: MessageThread, cubismId: String?) {
        messageThreadDao().update(
            thread.copy(cubismId = cubismId, updatedAt = Clock.System.now().toEpochMilliseconds())
        )
    }

    /**
     * Applies an arbitrary per-thread model/voice/misc settings change. Used by the
     * "Model settings" screen; refreshes [MessageThread.updatedAt].
     */
    suspend fun updateThreadSettings(thread: MessageThread, transform: (MessageThread) -> MessageThread) {
        messageThreadDao().update(
            transform(thread).copy(updatedAt = Clock.System.now().toEpochMilliseconds())
        )
    }

    /**
     * Snapshots [overrides] onto the thread's `custom*` columns and enables per-thread
     * overrides. Swift-friendly facade over
     * [withCustomSettingsEnabled] + [updateThreadSettings], since `MessageThread.copy(...)`
     * does not export to ObjC/Swift. Each edit should resolve the current effective settings,
     * mutate one field, and pass the whole snapshot back here.
     */
    suspend fun applyThreadOverrides(thread: MessageThread, overrides: EffectiveSettings) {
        messageThreadDao().update(
            thread.withCustomSettingsEnabled(overrides).copy(updatedAt = Clock.System.now().toEpochMilliseconds())
        )
    }

    /**
     * Disables per-thread overrides for [thread]. The `custom*`
     * columns are kept as-is but ignored while [MessageThread.useCustomSettings] is false, so
     * re-enabling restores the previous overrides. Swift-friendly.
     */
    suspend fun disableThreadOverrides(thread: MessageThread) {
        messageThreadDao().update(
            thread.copy(useCustomSettings = false, updatedAt = Clock.System.now().toEpochMilliseconds())
        )
    }

    /**
     * Creates or updates an MCP endpoint. Swift-friendly facade: `McpTool`'s default
     * arguments and `copy(...)` do not export to ObjC/Swift, so iOS cannot build or
     * amend one itself. Pass a null [id] to add; an existing id updates in place and
     * keeps the original `createdAt` so the list order is stable.
     */
    suspend fun upsertMcpTool(
        id: String?,
        name: String,
        url: String,
        token: String
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        val existing = id?.let { mcpToolDao().getToolById(it) }
        if (existing == null) {
            mcpToolDao().insert(
                McpTool(name = name, url = url, token = token, createdAt = now, updatedAt = now)
            )
        } else {
            mcpToolDao().update(
                existing.copy(name = name, url = url, token = token, updatedAt = now)
            )
        }
    }

    /**
     * Sets a thread's tool selection — the only place tool
     * participation is decided. An empty list means this chat calls no tools, which is what
     * a chat starts as. Swift-friendly (see [upsertMcpTool]).
     */
    suspend fun applyThreadToolSelection(thread: MessageThread, toolIds: List<String>) {
        // Applied against the row as it stands *now*, not against the caller's [thread]. The
        // tool picker is reached from a settings screen that holds a value snapshot taken when
        // it opened; since [MessageThreadDao.update] rewrites the whole row, applying the edit
        // to that snapshot would silently roll back anything changed in between — most visibly
        // the "Use custom settings" switch and the override columns it writes. Falls back to
        // the caller's copy only if the row is gone.
        val current = messageThreadDao().getThreadById(thread.threadId) ?: thread
        updateThreadSettings(current) { it.copy(customToolIds = encodeToolIds(toolIds)) }
    }

    suspend fun setMessageFavorite(message: Message, favorite: Boolean) {
        messageDao().update(message.copy(favorite = favorite))
    }

    suspend fun updateMessageContent(message: Message, content: String) {
        messageDao().update(
            message.copy(content = content, updatedAt = Clock.System.now().toEpochMilliseconds())
        )
    }

    companion object {
        const val DATABASE_NAME = "roleswitch_database"

        /**
         * Adds the `avatarBytes BLOB` column to `message_threads`. Registered on both
         * Android and iOS factories so user data survives the v6 → v7 upgrade.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare("ALTER TABLE message_threads ADD COLUMN avatarBytes BLOB").use { it.step() }
            }
        }

        /**
         * Adds the per-thread "custom settings" columns to `message_threads`: a master
         * `useCustomSettings` flag plus nullable overrides for every global setting
         * (intelligence / voice / misc). Null means "inherit global". Registered on both
         * Android and iOS factories so user data survives the v7 → v8 upgrade.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE message_threads ADD COLUMN useCustomSettings INTEGER NOT NULL DEFAULT 0"
                ).use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customApiUrl TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customApiKey TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customModel TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customEnableVision INTEGER").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customMessageLimit INTEGER").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customTemperature TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customTopP TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customN TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customMaxCompletionTokens TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customPresencePenalty TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customFrequencyPenalty TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customLanguage TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customVoice TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customVoiceSpeed REAL").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customRequireOnDeviceVoice INTEGER").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customUseVosk INTEGER").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customAllowInterruptions INTEGER").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customEmbedTime INTEGER").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customKeepLastAttachment INTEGER").use { it.step() }
            }
        }

        /**
         * Adds the per-thread `customUseSystemTts` override column to `message_threads`.
         * Null means "inherit global". Registered on both Android and iOS factories so
         * user data survives the v8 → v9 upgrade.
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customUseSystemTts INTEGER").use { it.step() }
            }
        }

        /**
         * Creates the `mcp_tools` table holding the user's configured MCP endpoints.
         * The column types must match what Room generates for [McpTool] exactly, or
         * the identity-hash check fails on the next open. Registered on both Android
         * and iOS factories so user data survives the v9 → v10 upgrade — both
         * factories fall back to a destructive migration, so an unmigrated version
         * bump would wipe every thread.
         */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    """
                    CREATE TABLE IF NOT EXISTS `mcp_tools` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `token` TEXT NOT NULL,
                        `isEnabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                ).use { it.step() }
            }
        }

        /**
         * Adds the per-thread tool overrides to `message_threads`: `customUseTools`
         * (null inherits the global switch) and `customToolIds`, a comma-separated list
         * of `mcp_tools` ids that replaces the per-endpoint enabled flags when set.
         * Registered on both Android and iOS factories so user data survives v10 → v11.
         */
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customUseTools INTEGER").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customToolIds TEXT").use { it.step() }
            }
        }

        /**
         * Data-only (the schema is untouched — `customUseTools` still exists here; it is
         * [MIGRATION_12_13] that drops it).
         *
         * `customToolIds` and `customUseTools` left the custom-settings override block and
         * now apply regardless of `useCustomSettings`. A thread that once had overrides on
         * and then off still carries the values snapshotted back then — inert until now, and
         * they would silently take effect — so clear them. Threads with overrides on keep
         * theirs: those were visible choices.
         *
         * This version shipped to no store, but it did reach dev devices, so it stays exactly
         * as they ran it. That is the whole reason the column drop is a separate version:
         * changing a schema under a version number that some database already claims leaves
         * Room comparing identity hashes it cannot reconcile, and it throws on open.
         *
         * Registered on both Android and iOS factories so user data survives v11 → v12.
         */
        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "UPDATE message_threads SET customUseTools = NULL, customToolIds = NULL " +
                        "WHERE useCustomSettings = 0"
                ).use { it.step() }
            }
        }

        /**
         * Drops `customUseTools`: the "Use tools" switch is gone, globally and per-thread.
         * Whether an endpoint participates is now said once, on the endpoint itself
         * (`mcp_tools.isEnabled`) or in a thread's [MessageThread.customToolIds]; a second
         * master switch on top of that only added a way for the two to disagree.
         *
         * SQLite before 3.35 has no `ALTER TABLE ... DROP COLUMN` and the minSdk here
         * predates that, so the table is rebuilt the long way — the same create/copy/drop/
         * rename dance Room itself generates. The stale-value clearing already happened in
         * [MIGRATION_11_12], which every database reaching this point has run, so the copy
         * is straight across.
         *
         * Endpoints are deliberately left alone. An enabled `mcp_tools` row now simply means
         * "use it", so a user who had endpoints enabled while the old global switch was off
         * ends up with tools live. That is the point of removing the switch.
         *
         * Registered on both Android and iOS factories so user data survives v12 → v13.
         */
        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    """
                    CREATE TABLE IF NOT EXISTS `message_threads_new` (
                        `threadId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `isProtected` INTEGER NOT NULL,
                        `avatarBytes` BLOB,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `useCustomSettings` INTEGER NOT NULL,
                        `customApiUrl` TEXT,
                        `customApiKey` TEXT,
                        `customModel` TEXT,
                        `customEnableVision` INTEGER,
                        `customMessageLimit` INTEGER,
                        `customTemperature` TEXT,
                        `customTopP` TEXT,
                        `customN` TEXT,
                        `customMaxCompletionTokens` TEXT,
                        `customPresencePenalty` TEXT,
                        `customFrequencyPenalty` TEXT,
                        `customLanguage` TEXT,
                        `customVoice` TEXT,
                        `customVoiceSpeed` REAL,
                        `customRequireOnDeviceVoice` INTEGER,
                        `customUseVosk` INTEGER,
                        `customAllowInterruptions` INTEGER,
                        `customEmbedTime` INTEGER,
                        `customKeepLastAttachment` INTEGER,
                        `customUseSystemTts` INTEGER,
                        `customToolIds` TEXT,
                        PRIMARY KEY(`threadId`)
                    )
                    """.trimIndent()
                ).use { it.step() }

                connection.prepare(
                    """
                    INSERT INTO `message_threads_new` (
                        `threadId`, `title`, `prompt`, `isProtected`, `avatarBytes`, `createdAt`,
                        `updatedAt`, `useCustomSettings`, `customApiUrl`, `customApiKey`,
                        `customModel`, `customEnableVision`, `customMessageLimit`,
                        `customTemperature`, `customTopP`, `customN`, `customMaxCompletionTokens`,
                        `customPresencePenalty`, `customFrequencyPenalty`, `customLanguage`,
                        `customVoice`, `customVoiceSpeed`, `customRequireOnDeviceVoice`,
                        `customUseVosk`, `customAllowInterruptions`, `customEmbedTime`,
                        `customKeepLastAttachment`, `customUseSystemTts`, `customToolIds`
                    )
                    SELECT
                        `threadId`, `title`, `prompt`, `isProtected`, `avatarBytes`, `createdAt`,
                        `updatedAt`, `useCustomSettings`, `customApiUrl`, `customApiKey`,
                        `customModel`, `customEnableVision`, `customMessageLimit`,
                        `customTemperature`, `customTopP`, `customN`, `customMaxCompletionTokens`,
                        `customPresencePenalty`, `customFrequencyPenalty`, `customLanguage`,
                        `customVoice`, `customVoiceSpeed`, `customRequireOnDeviceVoice`,
                        `customUseVosk`, `customAllowInterruptions`, `customEmbedTime`,
                        `customKeepLastAttachment`, `customUseSystemTts`, `customToolIds`
                    FROM `message_threads`
                    """.trimIndent()
                ).use { it.step() }

                connection.prepare("DROP TABLE `message_threads`").use { it.step() }
                connection.prepare(
                    "ALTER TABLE `message_threads_new` RENAME TO `message_threads`"
                ).use { it.step() }
            }
        }

        /**
         * Drops `mcp_tools.isEnabled`. The table is now a registry: a row says an endpoint is
         * configured, not that anything may call it. Participation is decided once, per chat,
         * in [MessageThread.customToolIds] — keeping a second flag here only allowed the two
         * to disagree. Same rebuild dance as [MIGRATION_12_13], for the same SQLite reason.
         *
         * **Nothing is UPDATEd, and that is deliberate — the disabling is in the semantics.**
         * `customToolIds IS NULL` used to mean "follow the global flags"; it now means "no
         * tools". So every chat that had never picked its own set goes quiet on upgrade,
         * which is exactly the intent, without touching a row. Chats that ticked an explicit
         * set keep it: those were deliberate choices and stay honoured.
         *
         * Registered on both Android and iOS factories so user data survives v13 → v14.
         */
        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    """
                    CREATE TABLE IF NOT EXISTS `mcp_tools_new` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `token` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                ).use { it.step() }

                connection.prepare(
                    """
                    INSERT INTO `mcp_tools_new` (`id`, `name`, `url`, `token`, `createdAt`, `updatedAt`)
                    SELECT `id`, `name`, `url`, `token`, `createdAt`, `updatedAt` FROM `mcp_tools`
                    """.trimIndent()
                ).use { it.step() }

                connection.prepare("DROP TABLE `mcp_tools`").use { it.step() }
                connection.prepare("ALTER TABLE `mcp_tools_new` RENAME TO `mcp_tools`").use { it.step() }
            }
        }

        /**
         * Adds the per-thread `customReasoningEffort` override column to `message_threads`,
         * backing the Thinking setting (`reasoning_effort`). Null means "inherit global",
         * and the global itself defaults to empty — which means the field is left out of the
         * request — so every existing chat keeps sending exactly what it sent on v14.
         * Registered on both Android and iOS factories so user data survives v14 → v15.
         */
        val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customReasoningEffort TEXT").use { it.step() }
            }
        }

        /**
         * Replaces `roles.hasGreeting` with `roles.greeting`, the opening line itself.
         *
         * The greeting used to be read from a platform string when a thread was created,
         * so it followed the *current* system language while the role's name and prompt
         * were frozen in whatever language the roles were seeded in. Change the system
         * language after onboarding and a Russian character greeted you in English.
         * Storing the text next to the name and prompt keeps all three in one language.
         *
         * Existing rows are backfilled by name, which is the only language marker in the
         * table — the two greeting roles have distinct Russian names, and everything else
         * falls back to English. `roles_witch` is deliberately left empty: its greeting is
         * the default thread's welcome message, which is only ever written when that
         * thread is first created, and by then the roles have been re-seeded.
         *
         * Same rebuild dance as [MIGRATION_13_14] — SQLite cannot drop a column in place.
         * Registered on both Android and iOS factories so user data survives v15 → v16.
         */
        val MIGRATION_15_16: Migration = object : Migration(15, 16) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    """
                    CREATE TABLE IF NOT EXISTS `roles_new` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `isProtected` INTEGER NOT NULL,
                        `greeting` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                ).use { it.step() }

                connection.prepare(
                    """
                    INSERT INTO `roles_new` (`id`, `name`, `prompt`, `summary`, `isProtected`, `greeting`, `createdAt`)
                    SELECT `id`, `name`, `prompt`, `summary`, `isProtected`,
                        CASE
                            WHEN `hasGreeting` = 0 THEN ''
                            WHEN `name` IN ('Виртуальный персонаж', 'Умный Ассистент') THEN 'Привет'
                            ELSE 'Hello'
                        END,
                        `createdAt`
                    FROM `roles`
                    """.trimIndent()
                ).use { it.step() }

                connection.prepare("DROP TABLE `roles`").use { it.step() }
                connection.prepare("ALTER TABLE `roles_new` RENAME TO `roles`").use { it.step() }
            }
        }

        /**
         * Adds the per-thread on-device inference overrides to `message_threads`:
         * `customUseLiteRt` picks the engine for the thread, the rest mirror the global
         * `litert*` sampler settings. Null means "inherit global".
         *
         * This landed on its own branch as v11 → v12, written before the tools and
         * greeting work took 12 through 16 on develop. Two different schemas cannot
         * share one version number — Room compares identity hashes on open and throws
         * when they disagree — so on the merge it moves to the end of the chain.
         *
         * Registered on both Android and iOS factories so user data survives v16 → v17;
         * both fall back to a destructive migration, so an unregistered bump would wipe
         * every thread.
         */
        val MIGRATION_16_17: Migration = object : Migration(16, 17) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customUseLiteRt INTEGER").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customLitertModel TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customLitertTopK TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customLitertTopP TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customLitertTemperature TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customLitertMaxTokens TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customLitertAccelerator TEXT").use { it.step() }
            }
        }

        /**
         * Adds `customLitertEnableThinking`, the per-thread override for the runtime's
         * `enable_thinking` flag (see [ai.roleswitch.android.service.LitertModel.supportsThinking]).
         *
         * Its own version rather than an extra line in [MIGRATION_16_17], which is the
         * whole point of the rule above: v17 had already been built and installed on a
         * dev device with seven `customLitert*` columns, and amending it left Room opening
         * a v17 database whose identity hash no longer matched a v17 schema —
         * `Room cannot verify the data integrity`, fatal on launch, with no way out but a
         * reinstall. A device already on the seven-column v17 lands here and gets the
         * eighth column; one still on v16 runs both in order.
         *
         * Registered on both Android and iOS factories so user data survives v17 → v18.
         */
        val MIGRATION_17_18: Migration = object : Migration(17, 18) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customLitertEnableThinking INTEGER").use { it.step() }
            }
        }

        /**
         * Adds the per-thread MLX overrides — iOS's on-device engine — alongside the
         * `customLitert*` columns [MIGRATION_16_17] added for Android's.
         *
         * Six columns, not seven: `customUseLiteRt` already answers "on device or remote"
         * for both platforms, and there is no accelerator to choose (MLX is always Metal).
         *
         * Both engines' columns live in one shared table because the schema is shared; each
         * platform writes only its own set. Registered on both Android and iOS factories so
         * user data survives v18 → v19 — either factory missing it falls back to a
         * destructive migration on that platform alone.
         */
        val MIGRATION_18_19: Migration = object : Migration(18, 19) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customMlxModel TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customMlxTopK TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customMlxTopP TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customMlxTemperature TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customMlxMaxTokens TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customMlxEnableThinking INTEGER").use { it.step() }
            }
        }

        /**
         * Drops `isProtected` from `message_threads` and `roles` — there are no protected
         * threads or roles any more. The default "Roles Witch" thread is editable and
         * deletable like any other, and its role is offered as a template like the rest.
         * Existing rows keep their data, minus the now-meaningless flag (0 on every row).
         *
         * Same create/copy/drop/rename dance as [MIGRATION_12_13] / [MIGRATION_15_16]:
         * `ALTER TABLE ... DROP COLUMN` needs SQLite 3.35+, above this `minSdk`.
         *
         * Registered on both Android and iOS factories so user data survives v19 → v20.
         */
        val MIGRATION_19_20: Migration = object : Migration(19, 20) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    """
                    CREATE TABLE IF NOT EXISTS `message_threads_new` (
                        `threadId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `avatarBytes` BLOB,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `useCustomSettings` INTEGER NOT NULL,
                        `customApiUrl` TEXT,
                        `customApiKey` TEXT,
                        `customModel` TEXT,
                        `customEnableVision` INTEGER,
                        `customMessageLimit` INTEGER,
                        `customTemperature` TEXT,
                        `customTopP` TEXT,
                        `customN` TEXT,
                        `customMaxCompletionTokens` TEXT,
                        `customPresencePenalty` TEXT,
                        `customFrequencyPenalty` TEXT,
                        `customReasoningEffort` TEXT,
                        `customLanguage` TEXT,
                        `customVoice` TEXT,
                        `customVoiceSpeed` REAL,
                        `customRequireOnDeviceVoice` INTEGER,
                        `customUseVosk` INTEGER,
                        `customAllowInterruptions` INTEGER,
                        `customEmbedTime` INTEGER,
                        `customKeepLastAttachment` INTEGER,
                        `customUseSystemTts` INTEGER,
                        `customToolIds` TEXT,
                        `customUseLiteRt` INTEGER,
                        `customLitertModel` TEXT,
                        `customLitertTopK` TEXT,
                        `customLitertTopP` TEXT,
                        `customLitertTemperature` TEXT,
                        `customLitertMaxTokens` TEXT,
                        `customLitertAccelerator` TEXT,
                        `customLitertEnableThinking` INTEGER,
                        `customMlxModel` TEXT,
                        `customMlxTopK` TEXT,
                        `customMlxTopP` TEXT,
                        `customMlxTemperature` TEXT,
                        `customMlxMaxTokens` TEXT,
                        `customMlxEnableThinking` INTEGER,
                        PRIMARY KEY(`threadId`)
                    )
                    """.trimIndent()
                ).use { it.step() }

                connection.prepare(
                    """
                    INSERT INTO `message_threads_new` (
                        `threadId`, `title`, `prompt`, `avatarBytes`, `createdAt`,
                        `updatedAt`, `useCustomSettings`, `customApiUrl`, `customApiKey`,
                        `customModel`, `customEnableVision`, `customMessageLimit`,
                        `customTemperature`, `customTopP`, `customN`, `customMaxCompletionTokens`,
                        `customPresencePenalty`, `customFrequencyPenalty`, `customReasoningEffort`,
                        `customLanguage`, `customVoice`, `customVoiceSpeed`,
                        `customRequireOnDeviceVoice`, `customUseVosk`, `customAllowInterruptions`,
                        `customEmbedTime`, `customKeepLastAttachment`, `customUseSystemTts`,
                        `customToolIds`, `customUseLiteRt`, `customLitertModel`, `customLitertTopK`,
                        `customLitertTopP`, `customLitertTemperature`, `customLitertMaxTokens`,
                        `customLitertAccelerator`, `customLitertEnableThinking`, `customMlxModel`,
                        `customMlxTopK`, `customMlxTopP`, `customMlxTemperature`,
                        `customMlxMaxTokens`, `customMlxEnableThinking`
                    )
                    SELECT
                        `threadId`, `title`, `prompt`, `avatarBytes`, `createdAt`,
                        `updatedAt`, `useCustomSettings`, `customApiUrl`, `customApiKey`,
                        `customModel`, `customEnableVision`, `customMessageLimit`,
                        `customTemperature`, `customTopP`, `customN`, `customMaxCompletionTokens`,
                        `customPresencePenalty`, `customFrequencyPenalty`, `customReasoningEffort`,
                        `customLanguage`, `customVoice`, `customVoiceSpeed`,
                        `customRequireOnDeviceVoice`, `customUseVosk`, `customAllowInterruptions`,
                        `customEmbedTime`, `customKeepLastAttachment`, `customUseSystemTts`,
                        `customToolIds`, `customUseLiteRt`, `customLitertModel`, `customLitertTopK`,
                        `customLitertTopP`, `customLitertTemperature`, `customLitertMaxTokens`,
                        `customLitertAccelerator`, `customLitertEnableThinking`, `customMlxModel`,
                        `customMlxTopK`, `customMlxTopP`, `customMlxTemperature`,
                        `customMlxMaxTokens`, `customMlxEnableThinking`
                    FROM `message_threads`
                    """.trimIndent()
                ).use { it.step() }

                connection.prepare("DROP TABLE `message_threads`").use { it.step() }
                connection.prepare(
                    "ALTER TABLE `message_threads_new` RENAME TO `message_threads`"
                ).use { it.step() }

                connection.prepare(
                    """
                    CREATE TABLE IF NOT EXISTS `roles_new` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `greeting` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                ).use { it.step() }

                connection.prepare(
                    """
                    INSERT INTO `roles_new` (`id`, `name`, `prompt`, `summary`, `greeting`, `createdAt`)
                    SELECT `id`, `name`, `prompt`, `summary`, `greeting`, `createdAt` FROM `roles`
                    """.trimIndent()
                ).use { it.step() }

                connection.prepare("DROP TABLE `roles`").use { it.step() }
                connection.prepare("ALTER TABLE `roles_new` RENAME TO `roles`").use { it.step() }
            }
        }

        /**
         * Drops every role template but the default one. The role picker is gone — creating
         * a conversation now opens the sheet pre-filled with the Virtual Character default
         * (`NewThreadDefaults`) — so the extra seeded rows (`virtual_friend`, and any older
         * role a version in between seeded) would only ever sit unused in the table. Data-only:
         * the schema is unchanged, so the identity hash is untouched; the version moves purely
         * to run the cleanup.
         *
         * Idempotent in practice — an install that never gained the extra rows deletes nothing.
         *
         * Registered on both Android and iOS factories so existing installs converge on the
         * single-role state on v20 → v21.
         */
        val MIGRATION_20_21: Migration = object : Migration(20, 21) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare("DELETE FROM `roles` WHERE `id` != 'roles_witch'").use { it.step() }
            }
        }

        /**
         * Drops the six per-thread `customMlx*` columns (`customMlxModel`, `customMlxTopK`,
         * `customMlxTopP`, `customMlxTemperature`, `customMlxMaxTokens`,
         * `customMlxEnableThinking`) from `message_threads`.
         *
         * MLX was removed from iOS in favour of LiteRT-LM. The columns are no longer read or
         * written; they were added by [MIGRATION_18_19] and this is their teardown.
         *
         * Same create/copy/drop/rename dance as [MIGRATION_19_20]: `ALTER TABLE ... DROP
         * COLUMN` needs SQLite 3.35+, above this `minSdk`.
         *
         * Registered on both Android and iOS factories so user data survives v21 → v22.
         */
        val MIGRATION_21_22: Migration = object : Migration(21, 22) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    """
                    CREATE TABLE IF NOT EXISTS `message_threads_new` (
                        `threadId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `avatarBytes` BLOB,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `useCustomSettings` INTEGER NOT NULL,
                        `customApiUrl` TEXT,
                        `customApiKey` TEXT,
                        `customModel` TEXT,
                        `customEnableVision` INTEGER,
                        `customMessageLimit` INTEGER,
                        `customTemperature` TEXT,
                        `customTopP` TEXT,
                        `customN` TEXT,
                        `customMaxCompletionTokens` TEXT,
                        `customPresencePenalty` TEXT,
                        `customFrequencyPenalty` TEXT,
                        `customReasoningEffort` TEXT,
                        `customLanguage` TEXT,
                        `customVoice` TEXT,
                        `customVoiceSpeed` REAL,
                        `customRequireOnDeviceVoice` INTEGER,
                        `customUseVosk` INTEGER,
                        `customAllowInterruptions` INTEGER,
                        `customEmbedTime` INTEGER,
                        `customKeepLastAttachment` INTEGER,
                        `customUseSystemTts` INTEGER,
                        `customToolIds` TEXT,
                        `customUseLiteRt` INTEGER,
                        `customLitertModel` TEXT,
                        `customLitertTopK` TEXT,
                        `customLitertTopP` TEXT,
                        `customLitertTemperature` TEXT,
                        `customLitertMaxTokens` TEXT,
                        `customLitertAccelerator` TEXT,
                        `customLitertEnableThinking` INTEGER,
                        PRIMARY KEY(`threadId`)
                    )
                    """.trimIndent()
                ).use { it.step() }

                connection.prepare(
                    """
                    INSERT INTO `message_threads_new` (
                        `threadId`, `title`, `prompt`, `avatarBytes`, `createdAt`,
                        `updatedAt`, `useCustomSettings`, `customApiUrl`, `customApiKey`,
                        `customModel`, `customEnableVision`, `customMessageLimit`,
                        `customTemperature`, `customTopP`, `customN`, `customMaxCompletionTokens`,
                        `customPresencePenalty`, `customFrequencyPenalty`, `customReasoningEffort`,
                        `customLanguage`, `customVoice`, `customVoiceSpeed`,
                        `customRequireOnDeviceVoice`, `customUseVosk`, `customAllowInterruptions`,
                        `customEmbedTime`, `customKeepLastAttachment`, `customUseSystemTts`,
                        `customToolIds`, `customUseLiteRt`, `customLitertModel`, `customLitertTopK`,
                        `customLitertTopP`, `customLitertTemperature`, `customLitertMaxTokens`,
                        `customLitertAccelerator`, `customLitertEnableThinking`
                    )
                    SELECT
                        `threadId`, `title`, `prompt`, `avatarBytes`, `createdAt`,
                        `updatedAt`, `useCustomSettings`, `customApiUrl`, `customApiKey`,
                        `customModel`, `customEnableVision`, `customMessageLimit`,
                        `customTemperature`, `customTopP`, `customN`, `customMaxCompletionTokens`,
                        `customPresencePenalty`, `customFrequencyPenalty`, `customReasoningEffort`,
                        `customLanguage`, `customVoice`, `customVoiceSpeed`,
                        `customRequireOnDeviceVoice`, `customUseVosk`, `customAllowInterruptions`,
                        `customEmbedTime`, `customKeepLastAttachment`, `customUseSystemTts`,
                        `customToolIds`, `customUseLiteRt`, `customLitertModel`, `customLitertTopK`,
                        `customLitertTopP`, `customLitertTemperature`, `customLitertMaxTokens`,
                        `customLitertAccelerator`, `customLitertEnableThinking`
                    FROM `message_threads`
                    """.trimIndent()
                ).use { it.step() }

                connection.prepare("DROP TABLE `message_threads`").use { it.step() }
                connection.prepare(
                    "ALTER TABLE `message_threads_new` RENAME TO `message_threads`"
                ).use { it.step() }
            }
        }

        /**
         * Adds the per-thread `customStreamingMode` override column to `message_threads`.
         * "Streaming mode" (see `SettingsManager.streamingMode`) can be overridden per chat
         * like every other global: null/blank inherits the global value, which is exactly
         * what an existing row gets from this migration — no backfill needed.
         *
         * A plain `ADD COLUMN`, like the other single-column migrations: the nullable column
         * defaults to NULL for every existing row and the entity default (`null`) matches.
         *
         * Registered on both Android and iOS factories so user data survives v22 → v23.
         */
        val MIGRATION_22_23: Migration = object : Migration(22, 23) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE message_threads ADD COLUMN customStreamingMode INTEGER"
                ).use { it.step() }
            }
        }

        /**
         * Adds the `documents` JSON column to `messages`. Attached documents are stored as
         * their extracted text plus metadata (see [DocumentAttachment]), serialized to JSON
         * by [Converters.fromDocumentList]. The column default is the empty string — the
         * converter's "no documents" value — so existing rows read back as an empty list,
         * exactly like `imageData`'s empty-String convention.
         *
         * Registered on both Android and iOS factories so user data survives v23 → v24.
         */
        val MIGRATION_23_24: Migration = object : Migration(23, 24) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE messages ADD COLUMN documents TEXT NOT NULL DEFAULT ''"
                ).use { it.step() }
            }
        }

        /**
         * Adds the per-thread external-speech overrides to `message_threads`:
         * `customUseExternalTts` selects the OpenAI-compatible `/v1/audio/speech` engine for
         * the thread, `customExternalTtsVoice` its server-side voice name. Null means
         * "inherit global", like every other `custom*` column, so existing rows need no
         * backfill. A plain `ADD COLUMN`, exactly like [MIGRATION_22_23].
         *
         * Registered on both Android and iOS factories so user data survives v24 → v25.
         */
        val MIGRATION_24_25: Migration = object : Migration(24, 25) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customUseExternalTts INTEGER").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customExternalTtsVoice TEXT").use { it.step() }
            }
        }

        /**
         * Adds the per-thread external-speech *server* overrides to `message_threads`:
         * `customExternalTtsAPI` + `customExternalTtsKey` select the OpenAI-compatible
         * `/v1/audio/speech` endpoint for this chat, separate from the inference server
         * (`customApiUrl`/`customApiKey`) — a thread may run its voice through a different
         * host than its chat. Null means "inherit global", like every other `custom*`
         * column, so existing rows need no backfill. A plain `ADD COLUMN`, exactly like
         * [MIGRATION_24_25]; the external engine flag and voice already ship, this adds the
         * server half of that pair.
         *
         * Registered on both Android and iOS factories so user data survives v25 → v26.
         */
        val MIGRATION_25_26: Migration = object : Migration(25, 26) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customExternalTtsAPI TEXT").use { it.step() }
                connection.prepare("ALTER TABLE message_threads ADD COLUMN customExternalTtsKey TEXT").use { it.step() }
            }
        }

        /**
         * Drops `customVoiceSpeed` from `message_threads` — the per-thread "Voice Speed"
         * override. The feature is gone outright: neither engine takes a rate any more
         * (both render at their own default), so there is nothing left for the column to
         * control, globally or per-thread.
         *
         * Added by [MIGRATION_7_8] alongside the rest of the `custom*` block; this is its
         * teardown. Same create/copy/drop/rename dance as [MIGRATION_21_22]:
         * `ALTER TABLE ... DROP COLUMN` needs SQLite 3.35+, above this `minSdk`.
         *
         * Registered on both Android and iOS factories so user data survives v26 → v27.
         */
        val MIGRATION_26_27: Migration = object : Migration(26, 27) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    """
                    CREATE TABLE IF NOT EXISTS `message_threads_new` (
                        `threadId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `avatarBytes` BLOB,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `useCustomSettings` INTEGER NOT NULL,
                        `customApiUrl` TEXT,
                        `customApiKey` TEXT,
                        `customModel` TEXT,
                        `customEnableVision` INTEGER,
                        `customMessageLimit` INTEGER,
                        `customTemperature` TEXT,
                        `customTopP` TEXT,
                        `customN` TEXT,
                        `customMaxCompletionTokens` TEXT,
                        `customPresencePenalty` TEXT,
                        `customFrequencyPenalty` TEXT,
                        `customReasoningEffort` TEXT,
                        `customLanguage` TEXT,
                        `customVoice` TEXT,
                        `customRequireOnDeviceVoice` INTEGER,
                        `customUseVosk` INTEGER,
                        `customAllowInterruptions` INTEGER,
                        `customEmbedTime` INTEGER,
                        `customKeepLastAttachment` INTEGER,
                        `customUseSystemTts` INTEGER,
                        `customUseExternalTts` INTEGER,
                        `customExternalTtsVoice` TEXT,
                        `customExternalTtsAPI` TEXT,
                        `customExternalTtsKey` TEXT,
                        `customStreamingMode` INTEGER,
                        `customToolIds` TEXT,
                        `customUseLiteRt` INTEGER,
                        `customLitertModel` TEXT,
                        `customLitertTopK` TEXT,
                        `customLitertTopP` TEXT,
                        `customLitertTemperature` TEXT,
                        `customLitertMaxTokens` TEXT,
                        `customLitertAccelerator` TEXT,
                        `customLitertEnableThinking` INTEGER,
                        PRIMARY KEY(`threadId`)
                    )
                    """.trimIndent()
                ).use { it.step() }

                connection.prepare(
                    """
                    INSERT INTO `message_threads_new` (
                        `threadId`, `title`, `prompt`, `avatarBytes`, `createdAt`,
                        `updatedAt`, `useCustomSettings`, `customApiUrl`, `customApiKey`,
                        `customModel`, `customEnableVision`, `customMessageLimit`,
                        `customTemperature`, `customTopP`, `customN`, `customMaxCompletionTokens`,
                        `customPresencePenalty`, `customFrequencyPenalty`, `customReasoningEffort`,
                        `customLanguage`, `customVoice`, `customRequireOnDeviceVoice`,
                        `customUseVosk`, `customAllowInterruptions`, `customEmbedTime`,
                        `customKeepLastAttachment`, `customUseSystemTts`, `customUseExternalTts`,
                        `customExternalTtsVoice`, `customExternalTtsAPI`, `customExternalTtsKey`,
                        `customStreamingMode`, `customToolIds`, `customUseLiteRt`,
                        `customLitertModel`, `customLitertTopK`, `customLitertTopP`,
                        `customLitertTemperature`, `customLitertMaxTokens`,
                        `customLitertAccelerator`, `customLitertEnableThinking`
                    )
                    SELECT
                        `threadId`, `title`, `prompt`, `avatarBytes`, `createdAt`,
                        `updatedAt`, `useCustomSettings`, `customApiUrl`, `customApiKey`,
                        `customModel`, `customEnableVision`, `customMessageLimit`,
                        `customTemperature`, `customTopP`, `customN`, `customMaxCompletionTokens`,
                        `customPresencePenalty`, `customFrequencyPenalty`, `customReasoningEffort`,
                        `customLanguage`, `customVoice`, `customRequireOnDeviceVoice`,
                        `customUseVosk`, `customAllowInterruptions`, `customEmbedTime`,
                        `customKeepLastAttachment`, `customUseSystemTts`, `customUseExternalTts`,
                        `customExternalTtsVoice`, `customExternalTtsAPI`, `customExternalTtsKey`,
                        `customStreamingMode`, `customToolIds`, `customUseLiteRt`,
                        `customLitertModel`, `customLitertTopK`, `customLitertTopP`,
                        `customLitertTemperature`, `customLitertMaxTokens`,
                        `customLitertAccelerator`, `customLitertEnableThinking`
                    FROM `message_threads`
                    """.trimIndent()
                ).use { it.step() }

                connection.prepare("DROP TABLE `message_threads`").use { it.step() }
                connection.prepare(
                    "ALTER TABLE `message_threads_new` RENAME TO `message_threads`"
                ).use { it.step() }
            }
        }

        /**
         * Adds the per-thread `cubismId` column to `message_threads` — the id of the
         * bundled Live2D character this chat presents itself through on a call. Null
         * (what every existing row gets) means "use the avatar photo", which is exactly
         * the pre-feature behaviour; non-null means the call screen renders the model.
         * Android-only feature, but the schema is shared, so the column exists on both
         * platforms and simply stays null on iOS.
         *
         * A plain `ADD COLUMN`, like the other nullable single-column migrations.
         * Registered on both Android and iOS factories so user data survives v27 → v28.
         */
        val MIGRATION_27_28: Migration = object : Migration(27, 28) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare("ALTER TABLE message_threads ADD COLUMN cubismId TEXT").use { it.step() }
            }
        }
    }
}

// KSP generates the actual class on each target. The expect class below is
// realized by Room's processor; the no-op body is required by Kotlin/Native.
@Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
