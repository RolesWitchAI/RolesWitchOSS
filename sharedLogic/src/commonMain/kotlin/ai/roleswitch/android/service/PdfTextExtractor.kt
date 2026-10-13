//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

/**
 * Platform seam for PDF text extraction. Android actual: Apache PDFBox
 * (`com.tom-roush:pdfbox-android`). iOS actual: the system PDFKit framework.
 * Both implement the same functions so the shared service layer extracts
 * documents without knowing the platform — the one place a platform's native
 * PDF stack is allowed to leak into common code.
 */
internal expect object PdfTextExtractor {
    /** Extracts the document's text, or null when [bytes] are not a readable PDF. */
    fun extractText(bytes: ByteArray): String?

    /** Number of pages, or 0 when [bytes] are not a readable PDF. */
    fun pageCount(bytes: ByteArray): Int
}