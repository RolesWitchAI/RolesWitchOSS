//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import ai.roleswitch.android.storage.appDataDirectory
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import kotlin.concurrent.AtomicReference

private const val PREFERENCES_FILE_NAME = "roleswitch_settings.preferences_pb"

private val instanceRef = AtomicReference<DataStore<Preferences>?>(null)

@OptIn(ExperimentalForeignApi::class)
private fun createDataStore(): DataStore<Preferences> {
    // Inside appDataDirectory so the API key in this store is excluded from
    // iCloud backup along with everything else the app persists.
    val storeDir = "${appDataDirectory()}/datastore"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = storeDir,
        withIntermediateDirectories = true,
        attributes = null,
        error = null
    )
    return PreferenceDataStoreFactory.create(
        storage = OkioStorage(
            fileSystem = FileSystem.SYSTEM,
            serializer = PreferencesSerializer,
            producePath = { "$storeDir/$PREFERENCES_FILE_NAME".toPath() }
        )
    )
}

fun getSettingsDataStore(): DataStore<Preferences> {
    instanceRef.value?.let { return it }
    val created = createDataStore()
    return if (instanceRef.compareAndSet(null, created)) created else instanceRef.value!!
}

fun getSettingsManager(): SettingsManager = SettingsManager(getSettingsDataStore())
