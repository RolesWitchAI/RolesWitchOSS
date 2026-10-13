//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import ai.roleswitch.android.data.model.Role

@Dao
interface RoleDao {
    @Query("SELECT * FROM roles ORDER BY createdAt ASC")
    suspend fun getAllRoles(): List<Role>

    @Insert
    suspend fun insert(role: Role)

    @Query("DELETE FROM roles")
    suspend fun deleteAll()
}
