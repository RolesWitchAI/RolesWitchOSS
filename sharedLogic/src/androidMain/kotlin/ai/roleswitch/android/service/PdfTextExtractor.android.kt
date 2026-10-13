//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

// pdfbox-android is a repackaged fork of Apache PDFBox: every class lives under
// `com.tom_roush.pdfbox.*` (the `org.apache.pdfbox` namespace is not shipped).
import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * One-time pdfbox-android bootstrap, required before any extraction.
 *
 * The fork loads its glyph lists / fonts / CMaps from Android **assets** through
 * [PDFBoxResourceLoader]. Until `init` is called it falls back to the classpath,
 * where those resources do not live, and the very first extraction dies in
 * `GlyphList.<clinit>` with `GlyphList '...glyphlist/glyphlist.txt' not found` —
 * the `ExceptionInInitializerError` Android crashes with on the first PDF send.
 * Called from `RolesWitchApp.onCreate`, before any turn can request extraction.
 */
fun initializePdfBox(context: Context) {
    PDFBoxResourceLoader.init(context.applicationContext)
}

/**
 * Android actual of [PdfTextExtractor]: Apache PDFBox (`pdfbox-android`), the
 * standard PDF text extraction library on Android. A fresh `PDFTextStripper`
 * per call is what makes the calls thread-safe; extraction runs on
 * `Dispatchers.Default` upstream in `LLMService.extractDocuments`, not on the
 * UI thread.
 */
internal actual object PdfTextExtractor {
    actual fun extractText(bytes: ByteArray): String? = try {
        PDDocument.load(bytes).use { doc ->
            PDFTextStripper().getText(doc)
        }
    } catch (_: Exception) {
        // Unreadable/corrupt PDF, a scanned document with no text layer, or an
        // encoding PDFBox cannot handle — none of these are fatal to the turn.
        null
    }

    actual fun pageCount(bytes: ByteArray): Int = try {
        PDDocument.load(bytes).use { it.numberOfPages }
    } catch (_: Exception) {
        0
    }
}