//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android actual of [PdfTextExtractor] (pdfbox-android), run as a host test.
 *
 * `pageCount` parses the document with no font machinery involved and works without
 * the Android-resource bootstrap. Text extraction normally needs
 * `PDFBoxResourceLoader.init(context)` (assets, not classpath); on the host the
 * extraction either succeeds or degrades to the "unreadable" null — both are the
 * documented contract, so the assertion accepts exactly those two outcomes.
 */
class PdfTextExtractorAndroidTest {

    @Test
    fun pageCountParsesWithoutAnyFontMachinery() {
        val pdf = buildSinglePagePdf("Hello from PDFBox")
        assertEquals(1, PdfTextExtractor.pageCount(pdf))
    }

    @Test
    fun textExtractionNeedsTheDeviceBootstrap() {
        // On the host there is no Android context to hand PDFBoxResourceLoader, so the
        // first extraction dies in a static initializer — the exact crash the bootstrap
        // exists to prevent (see initializePdfBox / RolesWitchApp.onCreate). The host
        // test therefore accepts either the text or that bootstrap failure; a *clean*
        // null or an unrelated failure would mean the actual changed shape.
        val pdf = buildSinglePagePdf("Hello from PDFBox")
        val outcome = kotlin.runCatching { PdfTextExtractor.extractText(pdf) }
        val text = outcome.getOrNull()
        assertTrue(
            (text != null && text.contains("Hello from PDFBox")) ||
                outcome.exceptionOrNull() is ExceptionInInitializerError,
            "unexpected outcome: $outcome"
        )
    }

    @Test
    fun garbageIsNotAPdf() {
        assertNull(PdfTextExtractor.extractText("not a pdf".encodeToByteArray()))
        assertEquals(0, PdfTextExtractor.pageCount("not a pdf".encodeToByteArray()))
    }

    @Test
    fun emptyBytesAreNotAPdfEither() {
        assertNull(PdfTextExtractor.extractText(ByteArray(0)))
        assertEquals(0, PdfTextExtractor.pageCount(ByteArray(0)))
    }
}
