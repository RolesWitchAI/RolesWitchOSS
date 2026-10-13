//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val PREFERENCES_FILE_NAME = "roleswitch_settings.preferences_pb"
private const val DATASTORE_DIR = "datastore"

private var instance: DataStore<Preferences>? = null
private val instanceLock = ReentrantLock()

private fun createDataStore(context: Context): DataStore<Preferences> {
    val storeDir = File(context.filesDir, DATASTORE_DIR).apply { mkdirs() }
    return PreferenceDataStoreFactory.create(
        produceFile = { File(storeDir, PREFERENCES_FILE_NAME) }
    )
}

fun getSettingsDataStore(context: Context): DataStore<Preferences> =
    instance ?: instanceLock.withLock {
        instance ?: createDataStore(context.applicationContext).also { instance = it }
    }

fun getSettingsManager(context: Context): SettingsManager =
    SettingsManager(getSettingsDataStore(context))
