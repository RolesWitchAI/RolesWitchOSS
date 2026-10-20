//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.PDFKit.PDFDocument

/**
 * iOS actual of [PdfTextExtractor]: the system PDFKit framework. No extra
 * binaries — the framework is part of the OS. `PDFDocument(data:)` is nil for
 * anything that is not a parseable PDF; a scanned document parses but its
 * `string` is empty, and nil vs empty is deliberately preserved so the caller
 * can tell "not a PDF" apart from "a PDF with no text".
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object PdfTextExtractor {
    actual fun extractText(bytes: ByteArray): String? {
        val data = toNSData(bytes) ?: return null
        val document = documentFor(data) ?: return null
        return document.string
    }

    actual fun pageCount(bytes: ByteArray): Int {
        val data = toNSData(bytes) ?: return 0
        val document = documentFor(data) ?: return 0
        return document.pageCount.toInt()
    }

    /**
     * `PDFDocument(data:)` is a *failable* initializer, but Kotlin maps it as a
     * non-null constructor — for unparsable bytes the call itself throws an NPE
     * instead of returning nil, so the `?.` at the call sites could never fire.
     * `runCatching` is what turns "not a PDF" back into the null this seam promises.
     */
    private fun documentFor(data: NSData): PDFDocument? =
        runCatching { PDFDocument(data = data) }.getOrNull()

    @OptIn(ExperimentalForeignApi::class)
    private fun toNSData(bytes: ByteArray): NSData? =
        if (bytes.isEmpty()) null
        else bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
}