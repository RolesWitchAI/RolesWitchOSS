//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Clock

@Entity(tableName = "roles")
data class Role(
    @PrimaryKey val id: String,
    val name: String,
    val prompt: String,
    val summary: String = "",
    /**
     * Opening line for a thread created from this role, in the language the role was
     * seeded in. Empty means the thread starts with no messages.
     *
     * Replaces the old `hasGreeting` flag: the text is the flag. Keeping both would let
     * them disagree — a role marked as greeting with nothing to say.
     */
    val greeting: String = "",
    val createdAt: Long = Clock.System.now().toEpochMilliseconds()
)
