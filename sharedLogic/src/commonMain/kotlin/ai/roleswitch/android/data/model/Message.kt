//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = MessageThread::class,
            parentColumns = ["threadId"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("threadId")]
)
@OptIn(ExperimentalUuidApi::class)
data class Message(
    @PrimaryKey val id: String = Uuid.random().toString(),
    val content: String,
    val reasoning: String = "",
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    val updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
    val isUser: Boolean,
    val favorite: Boolean = false,
    val threadId: String? = null,
    val imageData: List<ByteArray> = emptyList(),
    val documents: List<DocumentAttachment> = emptyList()
)
