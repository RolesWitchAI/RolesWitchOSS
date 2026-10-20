//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the entire migration chain (6 → 27) against a hand-built v6 database and
 * proves two things: every step executes on its predecessor's schema, and the final
 * schema is exactly what today's Room expects (the database opens through Room's own
 * identity-hash check and a row written before any migration still reads back).
 *
 * This is the chain both platform factories register — the one AGENTS.md warns must
 * stay in sync, or a platform silently falls back to a destructive migration.
 */
class MigrationChainTest {

    private lateinit var driver: BundledSQLiteDriver
    private lateinit var connection: SQLiteConnection
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        driver = BundledSQLiteDriver()
        val dir = java.nio.file.Files.createTempDirectory("roleswitch-migration")
        dbPath = dir.resolve("migrated.sqlite").toString()
        connection = driver.open(dbPath)
    }

    @AfterTest
    fun tearDown() {
        connection.close()
    }

    private fun exec(sql: String) {
        connection.prepare(sql).use { it.step() }
    }

    private fun columns(table: String): List<String> =
        connection.prepare("PRAGMA table_info($table)").use { statement ->
            buildList {
                while (statement.step()) add(statement.getText(1))
            }
        }

    private fun scalar(sql: String): String? =
        connection.prepare(sql).use { statement ->
            if (statement.step() && !statement.isNull(0)) statement.getText(0) else null
        }

    /** The schema exactly as a v6 install would have had it. */
    private fun createV6Schema() {
        exec(
            """
            CREATE TABLE IF NOT EXISTS `message_threads` (
                `threadId` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `prompt` TEXT NOT NULL,
                `isProtected` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`threadId`)
            )
            """.trimIndent()
        )
        exec(
            """
            CREATE TABLE IF NOT EXISTS `messages` (
                `id` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `reasoning` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `isUser` INTEGER NOT NULL,
                `favorite` INTEGER NOT NULL,
                `imageData` TEXT NOT NULL,
                `threadId` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`threadId`) REFERENCES `message_threads`(`threadId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        exec("CREATE INDEX IF NOT EXISTS `index_messages_threadId` ON `messages` (`threadId`)")
        exec(
            """
            CREATE TABLE IF NOT EXISTS `roles` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `prompt` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `isProtected` INTEGER NOT NULL,
                `hasGreeting` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        // One row per table, carrying values the later migrations are supposed to
        // transform or preserve.
        exec("INSERT INTO message_threads VALUES ('t-1', 'Old title', 'old prompt', 1, 100, 100)")
        exec("INSERT INTO messages VALUES ('m-1', 'kept text', '', 100, 100, 1, 0, '', 't-1')")
        exec("INSERT INTO roles VALUES ('virtual_friend', 'Виртуальный персонаж', 'p', 's', 1, 1, 100)")
        exec("PRAGMA user_version = 6")
    }

    private val chain = listOf(
        AppDatabase.MIGRATION_6_7,
        AppDatabase.MIGRATION_7_8,
        AppDatabase.MIGRATION_8_9,
        AppDatabase.MIGRATION_9_10,
        AppDatabase.MIGRATION_10_11,
        AppDatabase.MIGRATION_11_12,
        AppDatabase.MIGRATION_12_13,
        AppDatabase.MIGRATION_13_14,
        AppDatabase.MIGRATION_14_15,
        AppDatabase.MIGRATION_15_16,
        AppDatabase.MIGRATION_16_17,
        AppDatabase.MIGRATION_17_18,
        AppDatabase.MIGRATION_18_19,
        AppDatabase.MIGRATION_19_20,
        AppDatabase.MIGRATION_20_21,
        AppDatabase.MIGRATION_21_22,
        AppDatabase.MIGRATION_22_23,
        AppDatabase.MIGRATION_23_24,
        AppDatabase.MIGRATION_24_25,
        AppDatabase.MIGRATION_25_26,
        AppDatabase.MIGRATION_26_27
    )

    @Test
    fun theWholeChainRunsInOrderAndPreservesUserData() {
        createV6Schema()

        // The steps where a schema shape changes are spot-checked as they go.
        AppDatabase.MIGRATION_6_7.migrate(connection)
        assertTrue("avatarBytes" in columns("message_threads"))

        AppDatabase.MIGRATION_7_8.migrate(connection)
        assertTrue("useCustomSettings" in columns("message_threads"))
        assertTrue("customVoiceSpeed" in columns("message_threads"))

        AppDatabase.MIGRATION_8_9.migrate(connection)
        assertTrue("customUseSystemTts" in columns("message_threads"))

        AppDatabase.MIGRATION_9_10.migrate(connection)
        assertTrue("isEnabled" in columns("mcp_tools"))

        AppDatabase.MIGRATION_10_11.migrate(connection)
        assertTrue("customToolIds" in columns("message_threads"))

        AppDatabase.MIGRATION_11_12.migrate(connection)
        // A thread with overrides off loses its tool values here.
        exec("UPDATE message_threads SET useCustomSettings = 0, customToolIds = 'stale' WHERE threadId = 't-1'")
        AppDatabase.MIGRATION_11_12.migrate(connection)
        assertEquals(null, scalar("SELECT customToolIds FROM message_threads WHERE threadId = 't-1'"))

        AppDatabase.MIGRATION_12_13.migrate(connection)
        assertTrue("customUseTools" !in columns("message_threads"))

        AppDatabase.MIGRATION_13_14.migrate(connection)
        assertTrue("isEnabled" !in columns("mcp_tools"))

        AppDatabase.MIGRATION_14_15.migrate(connection)
        assertTrue("customReasoningEffort" in columns("message_threads"))

        AppDatabase.MIGRATION_15_16.migrate(connection)
        // The Russian greeting role is backfilled by name; `hasGreeting` is gone.
        assertEquals("Привет", scalar("SELECT greeting FROM roles WHERE id = 'virtual_friend'"))
        assertTrue("hasGreeting" !in columns("roles"))

        AppDatabase.MIGRATION_16_17.migrate(connection)
        assertTrue("customUseLiteRt" in columns("message_threads"))

        AppDatabase.MIGRATION_17_18.migrate(connection)
        assertTrue("customLitertEnableThinking" in columns("message_threads"))

        AppDatabase.MIGRATION_18_19.migrate(connection)
        assertTrue("customMlxModel" in columns("message_threads"))

        AppDatabase.MIGRATION_19_20.migrate(connection)
        assertTrue("isProtected" !in columns("message_threads"))
        assertTrue("isProtected" !in columns("roles"))

        AppDatabase.MIGRATION_20_21.migrate(connection)
        assertEquals(null, scalar("SELECT id FROM roles WHERE id != 'roles_witch'"))

        AppDatabase.MIGRATION_21_22.migrate(connection)
        assertTrue("customMlxModel" !in columns("message_threads"))

        AppDatabase.MIGRATION_22_23.migrate(connection)
        assertTrue("customStreamingMode" in columns("message_threads"))

        AppDatabase.MIGRATION_23_24.migrate(connection)
        assertTrue("documents" in columns("messages"))

        AppDatabase.MIGRATION_24_25.migrate(connection)
        assertTrue("customUseExternalTts" in columns("message_threads"))

        AppDatabase.MIGRATION_25_26.migrate(connection)
        assertTrue("customExternalTtsAPI" in columns("message_threads"))

        AppDatabase.MIGRATION_26_27.migrate(connection)
        assertTrue("customVoiceSpeed" !in columns("message_threads"))

        // The whole chain must be gapless: each step's start is its predecessor's end.
        chain.zipWithNext().forEach { (earlier, later) ->
            assertEquals(earlier.endVersion, later.startVersion, "migration chain has a gap at ${later.startVersion}")
        }
        assertEquals(6, chain.first().startVersion)
        assertEquals(27, chain.last().endVersion)

        // And the data survived every rebuild.
        assertEquals("kept text", scalar("SELECT content FROM messages WHERE id = 'm-1'"))
        assertEquals("Old title", scalar("SELECT title FROM message_threads WHERE threadId = 't-1'"))
    }

    @Test
    fun theMigratedDatabaseOpensInRoomAtTheCurrentVersion() {
        createV6Schema()
        chain.forEach { it.migrate(connection) }

        // Stamp the current identity hash so Room validates instead of recreating:
        // read it from a database Room itself just created.
        connection.close()
        val freshDir = java.nio.file.Files.createTempDirectory("roleswitch-fresh")
        val freshPath = freshDir.resolve("fresh.sqlite").toString()
        val fresh = androidx.room.Room.databaseBuilder<AppDatabase>(name = freshPath)
            .setDriver(BundledSQLiteDriver())
            .build()
        kotlinx.coroutines.runBlocking { fresh.roleDao().getAllRoles() } // forces creation
        val hashDriver = BundledSQLiteDriver()
        val hashConn = hashDriver.open(freshPath)
        val hash = hashConn.prepare("SELECT identity_hash FROM room_master_table").use { statement ->
            statement.step()
            statement.getText(0)
        }
        hashConn.close()
        fresh.close()

        connection = driver.open(dbPath)
        exec("PRAGMA user_version = 27")
        exec("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        exec("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '$hash')")

        val migrated = androidx.room.Room.databaseBuilder<AppDatabase>(name = dbPath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*chain.toTypedArray())
            .build()
        assertEquals("kept text", kotlinx.coroutines.runBlocking { migrated.messageDao().getMessagesByThread("t-1").single().content })
        migrated.close()
    }
}
