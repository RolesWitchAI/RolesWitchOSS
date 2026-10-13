//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.util

object MarkdownProcessor {

    fun stripMarkdown(text: String): String {
        var result = text
        // Remove code blocks (```...``` or ~~~...~~~)
        result = result.replace(Regex("```[\\s\\S]*?```|~~~[\\s\\S]*?~~~"), "")
        // Remove inline code
        result = result.replace(Regex("`([^`]+)`"), "$1")
        // Remove images (keep alt text)
        result = result.replace(Regex("!\\[(.*?)]\\(.*?\\)"), "$1")
        // Remove links (keep link text)
        result = result.replace(Regex("\\[(.*?)]\\(.*?\\)"), "$1")
        // Remove bold/italic markers (***, **, *, __, _, ~~)
        result = result.replace(Regex("(\\*\\*\\*|\\*\\*|__)(.*?)\\1"), "$2")
        result = result.replace(Regex("(\\*|_|~~)(.*?)\\1"), "$2")
        // Remove heading markers
        result = result.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
        // Remove horizontal rules
        result = result.replace(Regex("^[-*_]{3,}\\s*$", RegexOption.MULTILINE), "")
        // Remove blockquote markers
        result = result.replace(Regex("^>\\s+?", RegexOption.MULTILINE), "")
        // Remove list markers
        result = result.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
        result = result.replace(Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE), "")
        // Remove HTML tags
        result = result.replace(Regex("<[^>]*>"), "")
        // Remove leftover standalone asterisks/underscores (single chars not part of pairs)
        result = result.replace(Regex("(?<!\\w)[*_](?!\\w)"), "")
        // Clean up multiple consecutive spaces/newlines
        result = result.replace(Regex("[\\s&&[^\\n]]{2,}"), " ")
        result = result.replace(Regex("\\n{3,}"), "\n\n")
        return result.trim()
    }
}
