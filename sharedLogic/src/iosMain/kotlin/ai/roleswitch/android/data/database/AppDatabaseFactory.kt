//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ai.roleswitch.android.storage.appDataDirectory
import kotlin.concurrent.AtomicReference

private val instanceRef = AtomicReference<AppDatabase?>(null)

private fun createDatabase(): AppDatabase {
    // appDataDirectory rather than Documents/ directly: it is excluded from iCloud
    // backup, and covers the -wal/-shm siblings SQLite creates next to this file.
    val dbFilePath = "${appDataDirectory()}/${AppDatabase.DATABASE_NAME}.sqlite"
    return Room.databaseBuilder<AppDatabase>(name = dbFilePath)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(
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
        .fallbackToDestructiveMigration(true)
        .build()
}

fun getAppDatabase(): AppDatabase {
    instanceRef.value?.let { return it }
    val created = createDatabase()
    return if (instanceRef.compareAndSet(null, created)) created else instanceRef.value!!
}
