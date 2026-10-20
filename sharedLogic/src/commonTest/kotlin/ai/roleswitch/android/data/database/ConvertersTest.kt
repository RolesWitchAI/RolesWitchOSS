//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.database

import ai.roleswitch.android.data.model.DocumentAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun imageListRoundTrips() {
        // A trailing empty image is the one shape base64 cannot preserve (it encodes to
        // the empty string), so the fixture uses non-empty images like real photos.
        val photos = listOf(byteArrayOf(1, 2, 3), byteArrayOf(255.toByte(), 0.toByte(), 128.toByte()))
        val decoded = converters.toImageDataList(converters.fromImageDataList(photos))
        assertEquals(photos.size, decoded.size)
        photos.zip(decoded).forEach { (original, restored) ->
            assertTrue(original.contentEquals(restored))
        }
    }

    @Test
    fun emptyImageListEncodesAsEmptyString() {
        assertEquals("", converters.fromImageDataList(emptyList()))
    }

    @Test
    fun emptyStringDecodesToNoImages() {
        assertEquals(emptyList(), converters.toImageDataList(""))
    }

    @Test
    fun documentsRoundTrip() {
        val documents = listOf(
            DocumentAttachment(
                fileName = "notes.pdf",
                sizeBytes = 12_345,
                pageCount = 7,
                extractedText = "extracted text"
            ),
            DocumentAttachment(
                fileName = "empty.pdf",
                mimeType = "application/pdf",
                sizeBytes = 1,
                pageCount = 0,
                extractedText = ""
            )
        )
        val decoded = converters.toDocumentList(converters.fromDocumentList(documents))
        assertEquals(documents, decoded)
    }

    @Test
    fun emptyStringDecodesToNoDocuments() {
        assertEquals("", converters.fromDocumentList(emptyList()))
        assertEquals(emptyList(), converters.toDocumentList(""))
    }
}
