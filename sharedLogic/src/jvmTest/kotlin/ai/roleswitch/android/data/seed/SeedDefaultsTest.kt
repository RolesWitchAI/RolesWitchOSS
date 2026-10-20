//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.seed

import ai.roleswitch.android.data.database.AppDatabase
import ai.roleswitch.android.settings.InMemoryPreferenceDataStore
import ai.roleswitch.android.settings.SettingsManager
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeedDefaultsTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsManager

    @BeforeTest
    fun setUp() {
        val dir = java.nio.file.Files.createTempDirectory("roleswitch-seed")
        db = Room.databaseBuilder<AppDatabase>(name = dir.resolve("test.sqlite").toString())
            .setDriver(BundledSQLiteDriver())
            .build()
        settings = SettingsManager(InMemoryPreferenceDataStore())
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun aFreshInstallSeeds() = runTest {
        seedDefaults(db, settings, deviceLanguage = "ru", stressMessageCount = 10)
        assertEquals(1, db.getAllThreads().size)
        assertEquals(11, db.messageDao().countByThread(RoleSeedData.DEFAULT_THREAD_ID))
    }

    @Test
    fun onboardingAlreadyDoneMeansNoSeed() = runTest {
        settings.setHasCompletedOnboarding(true)
        seedDefaults(db, settings, deviceLanguage = "en")
        assertTrue(db.getAllThreads().isEmpty())
    }

    @Test
    fun anUnsupportedDeviceLanguageSeedsInEnglish() = runTest {
        seedDefaults(db, settings, deviceLanguage = "ja")
        val role = db.roleDao().getAllRoles().single()
        assertEquals("Hello, I'm Roles Witch and I'm your smart assistant.", role.greeting)
    }
}
