//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Minimal in-memory `DataStore<Preferences>` for tests: `updateData` swaps the current
 * value synchronously, so [SettingsManager]'s queued writes and its `runBlocking`
 * constructor read both work without a filesystem. Each test builds its own instance,
 * which also isolates the process-wide `LitertModels.setCustom` push the constructor's
 * `init` block performs.
 */
class InMemoryPreferenceDataStore(
    initial: Preferences = emptyPreferences()
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
