//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One configured MCP endpoint.
 *
 * The user-facing name for a row is "tool", but a single endpoint typically exposes
 * *several* callable functions — the ones `tools/list` returns. Those are discovered
 * at request time and never stored; only the connection details live here.
 *
 * This table is a *registry* and nothing more — a row's existence says the endpoint is
 * configured, not that anything may call it. Which chats may call it is said once, in each
 * thread's [MessageThread.customToolIds]. There is deliberately no enabled flag here: with
 * one on each row *and* a per-thread list, the two could disagree.
 */
@Entity(tableName = "mcp_tools")
@OptIn(ExperimentalUuidApi::class)
data class McpTool(
    @PrimaryKey val id: String = Uuid.random().toString(),
    /** User-chosen label, shown in Settings → Tools. */
    val name: String,
    /** Streamable-HTTP endpoint of the MCP server. */
    val url: String,
    /** Sent as `Authorization: Bearer <token>`. Empty when the server needs no auth. */
    val token: String = "",
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    val updatedAt: Long = Clock.System.now().toEpochMilliseconds()
)
