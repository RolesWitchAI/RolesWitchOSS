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
import ai.roleswitch.android.data.model.MessageThread
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageThreadDao {
    @Query("SELECT * FROM message_threads ORDER BY createdAt ASC")
    fun observeAllThreads(): Flow<List<MessageThread>>

    @Query("SELECT * FROM message_threads WHERE threadId = :threadId")
    fun observeThreadById(threadId: String): Flow<MessageThread?>

    @Query("SELECT * FROM message_threads WHERE threadId = :threadId")
    suspend fun getThreadById(threadId: String): MessageThread?

    @Insert
    suspend fun insert(thread: MessageThread)

    @Update
    suspend fun update(thread: MessageThread)

    @Delete
    suspend fun delete(thread: MessageThread)

    @Query("DELETE FROM message_threads")
    suspend fun deleteAll()

}
