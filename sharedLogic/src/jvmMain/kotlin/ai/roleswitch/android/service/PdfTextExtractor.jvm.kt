//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

/**
 * JVM actual of [PdfTextExtractor]: a stub. The JVM target exists so the shared tests
 * run fast and so Room can be exercised through the bundled SQLite driver — it has no
 * PDF stack, and pulling one in just for tests would test the library rather than the
 * app. The real actuals run on their own platforms (`iosTest` drives PDFKit, the
 * Android host test drives PDFBox); through this stub, the shared
 * `LLMService.extractDocuments` path is exercised with "nothing extractable".
 */
internal actual object PdfTextExtractor {
    actual fun extractText(bytes: ByteArray): String? = null

    actual fun pageCount(bytes: ByteArray): Int = 0
}
