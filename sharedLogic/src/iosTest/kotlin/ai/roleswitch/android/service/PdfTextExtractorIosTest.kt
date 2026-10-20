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
 * The iOS actual of [PdfTextExtractor] against the system PDFKit: a real PDF, a
 * corrupt blob, and empty input. Runs on the simulator via iosSimulatorArm64Test.
 */
class PdfTextExtractorIosTest {

    @Test
    fun aValidPdfYieldsItsTextAndPageCount() {
        val pdf = buildSinglePagePdf("Hello from PDFKit")
        val text = PdfTextExtractor.extractText(pdf)
        assertTrue(text != null && text.contains("Hello from PDFKit"), "text was: $text")
        assertEquals(1, PdfTextExtractor.pageCount(pdf))
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
