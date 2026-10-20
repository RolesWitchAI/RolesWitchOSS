//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

/**
 * A minimal, byte-correct single-page PDF built in code, for exercising the platform
 * `PdfTextExtractor` actuals with a real document. The xref offsets are computed, so
 * the output parses in both PDFKit and PDFBox without repair.
 */
fun buildSinglePagePdf(text: String): ByteArray {
    val stream = "BT /F1 24 Tf 72 720 Td ($text) Tj ET"
    val objects = listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        "<< /Length ${stream.length} >>\nstream\n$stream\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    )

    val out = StringBuilder()
    val offsets = mutableListOf<Int>()
    out.append("%PDF-1.4\n")
    objects.forEachIndexed { index, body ->
        offsets += out.length
        out.append("${index + 1} 0 obj\n$body\nendobj\n")
    }
    val startxref = out.length
    out.append("xref\n0 ${objects.size + 1}\n")
    out.append("0000000000 65535 f \n")
    offsets.forEach { offset ->
        out.append(offset.toString().padStart(10, '0') + " 00000 n \n")
    }
    out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$startxref\n%%EOF\n")
    return out.toString().encodeToByteArray()
}
