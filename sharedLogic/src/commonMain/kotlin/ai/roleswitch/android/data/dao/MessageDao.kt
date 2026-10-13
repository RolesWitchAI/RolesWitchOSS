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
import ai.roleswitch.android.data.model.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY createdAt ASC")
    fun observeMessagesByThread(threadId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY createdAt ASC")
    suspend fun getMessagesByThread(threadId: String): List<Message>

    @Query("SELECT * FROM messages WHERE favorite = 1 ORDER BY createdAt DESC")
    fun observeFavoriteMessages(): Flow<List<Message>>

    @Query("""
        SELECT m.* FROM messages m
        INNER JOIN (
            SELECT threadId, MAX(createdAt) as maxCreated
            FROM messages GROUP BY threadId
        ) latest ON m.threadId = latest.threadId AND m.createdAt = latest.maxCreated
    """)
    fun observeLastMessagePerThread(): Flow<List<Message>>

    @Query("SELECT COUNT(*) FROM messages WHERE threadId = :threadId")
    suspend fun countByThread(threadId: String): Int

    /**
     * Messages whose body contains [query], newest first — the chat-wide search behind the
     * threads screen's search field.
     *
     * **Four cases of the same needle, because SQLite's `LIKE` only folds ASCII.** Without the
     * ICU extension `LIKE '%привет%'` will not match "Привет", so the caller passes the term as
     * typed plus its lowercase, uppercase and capitalized forms and any of them may hit. That
     * covers how Cyrillic actually appears in a sentence; ASCII needs none of it, since `LIKE`
     * folds it already. A genuinely Unicode-correct search means an FTS table with the
     * `unicode61` tokenizer, which is a schema migration this does not want to be.
     *
     * `ESCAPE '\'` makes `%` and `_` searchable as themselves — the caller escapes them, along
     * with the backslash itself, in every one of the four forms.
     *
     * [limit] is not optional: a bare substring search over every message in the database is
     * unbounded work on a phone, and no one reads past the first screenful of hits.
     */
    @Query("""
        SELECT * FROM messages
        WHERE content LIKE '%' || :query || '%' ESCAPE '\'
           OR content LIKE '%' || :queryLower || '%' ESCAPE '\'
           OR content LIKE '%' || :queryUpper || '%' ESCAPE '\'
           OR content LIKE '%' || :queryCapitalized || '%' ESCAPE '\'
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun searchMessages(
        query: String,
        queryLower: String,
        queryUpper: String,
        queryCapitalized: String,
        limit: Int
    ): List<Message>

    @Insert
    suspend fun insert(message: Message)

    /** Bulk insert in a single transaction. Used by the stress seed. */
    @Insert
    suspend fun insertAll(messages: List<Message>)

    @Update
    suspend fun update(message: Message)

    @Delete
    suspend fun delete(message: Message)

    @Query("DELETE FROM messages WHERE threadId = :threadId")
    suspend fun deleteByThread(threadId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
