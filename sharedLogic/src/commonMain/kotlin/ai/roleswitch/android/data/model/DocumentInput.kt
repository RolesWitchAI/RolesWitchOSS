//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.model

/**
 * A picked document before extraction: the raw file bytes plus its display name.
 * Constructed by the platform UI straight from the Files picker and consumed by
 * `LLMService.extractDocuments`, which turns it into a persisted
 * [DocumentAttachment]. The bytes are never stored or sent anywhere.
 */
class DocumentInput(
    val fileName: String,
    val data: ByteArray
)