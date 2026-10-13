//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.model

import kotlinx.serialization.Serializable

/**
 * A document attached to a user message. Only the *extracted text* is persisted
 * alongside the file's metadata — the raw bytes never reach the database or the
 * LLM. [extractedText] is what the engine prompt sees, injected by
 * `LLMService.consolidateMessages` on the newest doc-bearing message only.
 *
 * Serialized to a JSON string by `Converters` into the `messages.documents`
 * column; `mimeType` is always PDF today because both pickers are filtered to
 * PDFs at the source.
 */
@Serializable
data class DocumentAttachment(
    val fileName: String,
    val mimeType: String = "application/pdf",
    val sizeBytes: Long,
    val pageCount: Int = 0,
    val extractedText: String = ""
)