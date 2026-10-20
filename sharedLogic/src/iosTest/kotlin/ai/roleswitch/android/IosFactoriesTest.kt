//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android

import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.storage.appDataDirectory
import platform.Foundation.NSFileManager
import ai.roleswitch.android.settings.getSettingsManager
import ai.roleswitch.android.data.database.getAppDatabase
import ai.roleswitch.android.app.createAppContainer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The iosMain factories run on the simulator: the DataStore and Room singletons back
 * onto NSFileManager paths inside the test container, exactly as they do in the app.
 * The database is *persistent* here, so rows use fresh ids to stay rerun-safe.
 */
class IosFactoriesTest {

    @Test
    fun theSettingsManagerRoundTripsThroughTheStore() = runBlocking {
        // The *store* is the singleton; getSettingsManager() wraps it in a fresh
        // manager per call. Each manager snapshots the store at construction, so the
        // read-back goes through the same instance that wrote.
        val manager = getSettingsManager()
        manager.setSelectedModel("ios-model")
        assertEquals("ios-model", manager.selectedModel.value)
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(
                "${appDataDirectory()}/datastore/roleswitch_settings.preferences_pb"
            )
        )
    }

    @Test
    fun theDatabaseIsASingletonThatStoresThreads() = runBlocking {
        val db = getAppDatabase()
        assertEquals(db, getAppDatabase())
        val id = "factory-test-${kotlin.random.Random.nextInt(1_000_000)}"
        db.messageThreadDao().insert(
            MessageThread(threadId = id, title = "Factory test", prompt = "p")
        )
        val read = db.messageThreadDao().getThreadById(id)
        assertNotNull(read)
        assertEquals("Factory test", read.title)
    }

    @Test
    fun theAppContainerIsCachedAndReusesTheDatabaseSingleton() = runBlocking {
        val first = createAppContainer()
        assertEquals(first, createAppContainer())
        assertTrue(first.database === getAppDatabase())
        first.settingsManager.setVoice("ios-voice")
        assertEquals("ios-voice", first.settingsManager.voice.value)
    }
}
