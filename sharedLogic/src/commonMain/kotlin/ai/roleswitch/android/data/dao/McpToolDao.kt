//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import ai.roleswitch.android.data.model.McpTool
import kotlinx.coroutines.flow.Flow

@Dao
interface McpToolDao {
    @Query("SELECT * FROM mcp_tools ORDER BY createdAt ASC")
    fun observeAllTools(): Flow<List<McpTool>>

    @Query("SELECT * FROM mcp_tools ORDER BY createdAt ASC")
    suspend fun getAllTools(): List<McpTool>

    @Query("SELECT * FROM mcp_tools WHERE id = :id")
    suspend fun getToolById(id: String): McpTool?

    @Insert
    suspend fun insert(tool: McpTool)

    @Update
    suspend fun update(tool: McpTool)

    @Delete
    suspend fun delete(tool: McpTool)

    @Query("DELETE FROM mcp_tools")
    suspend fun deleteAll()
}
