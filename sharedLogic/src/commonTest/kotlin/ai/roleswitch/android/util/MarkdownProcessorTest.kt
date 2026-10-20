//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.util

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownProcessorTest {

    @Test
    fun plainTextPassesThroughUntouched() {
        assertEquals("Hello, world!", MarkdownProcessor.stripMarkdown("Hello, world!"))
    }

    @Test
    fun fencedCodeBlocksAreRemovedEntirely() {
        val input = "before\n```kotlin\nval x = 1\n```\nafter"
        assertEquals("before\n\nafter", MarkdownProcessor.stripMarkdown(input))
    }

    @Test
    fun tildeCodeBlocksAreRemovedToo() {
        val input = "before\n~~~\ncode\n~~~\nafter"
        assertEquals("before\n\nafter", MarkdownProcessor.stripMarkdown(input))
    }

    @Test
    fun inlineCodeKeepsOnlyTheCode() {
        assertEquals("use val x = 1 here", MarkdownProcessor.stripMarkdown("use `val x = 1` here"))
    }

    @Test
    fun imagesKeepTheirAltText() {
        assertEquals("a chart", MarkdownProcessor.stripMarkdown("![a chart](https://example.com/p.png)"))
    }

    @Test
    fun linksKeepTheirLabelText() {
        assertEquals("see Roles Witch", MarkdownProcessor.stripMarkdown("see [Roles Witch](https://roleswitch.ai)"))
    }

    @Test
    fun boldAndItalicMarkersAreStripped() {
        assertEquals("bold", MarkdownProcessor.stripMarkdown("**bold**"))
        assertEquals("bold", MarkdownProcessor.stripMarkdown("__bold__"))
        assertEquals("italic", MarkdownProcessor.stripMarkdown("*italic*"))
        assertEquals("italic", MarkdownProcessor.stripMarkdown("_italic_"))
        assertEquals("both", MarkdownProcessor.stripMarkdown("***both***"))
        assertEquals("strike", MarkdownProcessor.stripMarkdown("~~strike~~"))
        assertEquals("bold and italic", MarkdownProcessor.stripMarkdown("**bold** and *italic*"))
    }

    @Test
    fun headingsLoseTheirMarkers() {
        assertEquals("Title", MarkdownProcessor.stripMarkdown("# Title"))
        assertEquals("Subtitle", MarkdownProcessor.stripMarkdown("## Subtitle"))
        assertEquals("Deep", MarkdownProcessor.stripMarkdown("###### Deep"))
    }

    @Test
    fun blockquotesLoseTheirMarkers() {
        assertEquals("quoted text", MarkdownProcessor.stripMarkdown("> quoted text"))
    }

    @Test
    fun listMarkersAreStripped() {
        assertEquals("one\ntwo", MarkdownProcessor.stripMarkdown("- one\n- two"))
        assertEquals("one\ntwo", MarkdownProcessor.stripMarkdown("* one\n* two"))
        assertEquals("one\ntwo", MarkdownProcessor.stripMarkdown("+ one\n+ two"))
        assertEquals("first\nsecond", MarkdownProcessor.stripMarkdown("1. first\n2. second"))
    }

    @Test
    fun horizontalRulesDisappear() {
        assertEquals("", MarkdownProcessor.stripMarkdown("---"))
        assertEquals("", MarkdownProcessor.stripMarkdown("***"))
    }

    @Test
    fun htmlTagsAreRemoved() {
        assertEquals("bold", MarkdownProcessor.stripMarkdown("<b>bold</b>"))
        assertEquals("x", MarkdownProcessor.stripMarkdown("<div>x</div>"))
    }

    @Test
    fun nestedFormattingIsFlattened() {
        assertEquals("link", MarkdownProcessor.stripMarkdown("**[link](https://example.com)**"))
    }

    @Test
    fun multipleSpacesCollapseToOne() {
        assertEquals("a b c", MarkdownProcessor.stripMarkdown("a    b \t c"))
    }

    @Test
    fun threeOrMoreNewlinesBecomeTwo() {
        assertEquals("a\n\nb", MarkdownProcessor.stripMarkdown("a\n\n\n\nb"))
    }

    @Test
    fun resultIsTrimmed() {
        assertEquals("text", MarkdownProcessor.stripMarkdown("   text   "))
    }
}
