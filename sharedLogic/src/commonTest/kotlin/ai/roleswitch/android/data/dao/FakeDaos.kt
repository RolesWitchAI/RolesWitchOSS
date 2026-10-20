//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.dao

import ai.roleswitch.android.data.model.McpTool
import ai.roleswitch.android.data.model.Message
import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.data.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory DAO implementations for the service-layer tests: same query semantics as
 * the Room DAOs (ordering, per-thread filtering, newest-last-message-per-thread) but
 * with no database behind them, so `LLMService` can run whole turns against fakes.
 */
class InMemoryMessageDao : MessageDao {
    val messages = mutableListOf<Message>()
    private val changes = MutableStateFlow(0)

    private fun bump() {
        changes.value += 1
    }

    private fun byThread(threadId: String): List<Message> =
        messages.filter { it.threadId == threadId }.sortedBy { it.createdAt }

    override fun observeMessagesByThread(threadId: String): Flow<List<Message>> =
        changes.map { byThread(threadId) }

    override suspend fun getMessagesByThread(threadId: String): List<Message> = byThread(threadId)

    override fun observeFavoriteMessages(): Flow<List<Message>> =
        changes.map { messages.filter { it.favorite }.sortedByDescending { it.createdAt } }

    override fun observeLastMessagePerThread(): Flow<List<Message>> =
        changes.map {
            messages
                .groupBy { it.threadId }
                .map { (_, rows) -> rows.maxBy { m -> m.createdAt } }
        }

    override suspend fun countByThread(threadId: String): Int = messages.count { it.threadId == threadId }

    override suspend fun searchMessages(
        query: String,
        queryLower: String,
        queryUpper: String,
        queryCapitalized: String,
        limit: Int
    ): List<Message> = messages
        .filter { row -> listOf(query, queryLower, queryUpper, queryCapitalized).any { row.content.contains(it, ignoreCase = true) } }
        .sortedByDescending { it.createdAt }
        .take(limit)

    override suspend fun insert(message: Message) {
        messages += message
        bump()
    }

    override suspend fun insertAll(newMessages: List<Message>) {
        messages += newMessages
        bump()
    }

    override suspend fun update(message: Message) {
        val index = messages.indexOfFirst { it.id == message.id }
        if (index >= 0) messages[index] = message
        bump()
    }

    override suspend fun delete(message: Message) {
        messages.removeAll { it.id == message.id }
        bump()
    }

    override suspend fun deleteByThread(threadId: String) {
        messages.removeAll { it.threadId == threadId }
        bump()
    }

    override suspend fun deleteAll() {
        messages.clear()
        bump()
    }
}

class InMemoryMessageThreadDao : MessageThreadDao {
    val threads = mutableListOf<MessageThread>()
    private val changes = MutableStateFlow(0)

    private fun bump() {
        changes.value += 1
    }

    override fun observeAllThreads(): Flow<List<MessageThread>> =
        changes.map { threads.sortedBy { it.createdAt } }

    override fun observeThreadById(threadId: String): Flow<MessageThread?> =
        changes.map { threads.firstOrNull { it.threadId == threadId } }

    override suspend fun getThreadById(threadId: String): MessageThread? =
        threads.firstOrNull { it.threadId == threadId }

    override suspend fun insert(thread: MessageThread) {
        threads += thread
        bump()
    }

    override suspend fun update(thread: MessageThread) {
        val index = threads.indexOfFirst { it.threadId == thread.threadId }
        if (index >= 0) threads[index] = thread
        bump()
    }

    override suspend fun delete(thread: MessageThread) {
        threads.removeAll { it.threadId == thread.threadId }
        bump()
    }

    override suspend fun deleteAll() {
        threads.clear()
        bump()
    }
}

class InMemoryMcpToolDao : McpToolDao {
    val tools = mutableListOf<McpTool>()
    private val changes = MutableStateFlow(0)

    private fun bump() {
        changes.value += 1
    }

    override fun observeAllTools(): Flow<List<McpTool>> =
        changes.map { tools.sortedBy { it.createdAt } }

    override suspend fun getAllTools(): List<McpTool> = tools.sortedBy { it.createdAt }

    override suspend fun getToolById(id: String): McpTool? = tools.firstOrNull { it.id == id }

    override suspend fun insert(tool: McpTool) {
        tools += tool
        bump()
    }

    override suspend fun update(tool: McpTool) {
        val index = tools.indexOfFirst { it.id == tool.id }
        if (index >= 0) tools[index] = tool
        bump()
    }

    override suspend fun delete(tool: McpTool) {
        tools.removeAll { it.id == tool.id }
        bump()
    }

    override suspend fun deleteAll() {
        tools.clear()
        bump()
    }
}

class InMemoryRoleDao : RoleDao {
    val roles = mutableListOf<Role>()

    override suspend fun getAllRoles(): List<Role> = roles.sortedBy { it.createdAt }

    override suspend fun insert(role: Role) {
        roles += role
    }

    override suspend fun deleteAll() {
        roles.clear()
    }
}
