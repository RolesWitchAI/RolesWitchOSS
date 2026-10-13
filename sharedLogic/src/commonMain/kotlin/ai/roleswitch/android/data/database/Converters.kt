//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.database

import ai.roleswitch.android.data.model.DocumentAttachment
import androidx.room.TypeConverter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalEncodingApi::class)
class Converters {
    @TypeConverter
    fun fromImageDataList(value: List<ByteArray>): String {
        if (value.isEmpty()) return ""
        return value.joinToString(",") { Base64.encode(it) }
    }

    @TypeConverter
    fun toImageDataList(value: String): List<ByteArray> {
        if (value.isEmpty()) return emptyList()
        return value.split(",").map { Base64.decode(it) }
    }

    // Documents are stored as JSON in a TEXT column — same shape as the `imageData`
    // convention (empty string means "no documents"), which the MIGRATION_23_24
    // column default mirrors so existing rows decode straight to an empty list.
    @TypeConverter
    fun fromDocumentList(value: List<DocumentAttachment>): String {
        if (value.isEmpty()) return ""
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toDocumentList(value: String): List<DocumentAttachment> {
        if (value.isEmpty()) return emptyList()
        return Json.decodeFromString(value)
    }
}
