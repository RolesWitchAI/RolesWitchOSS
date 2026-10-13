//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.util

/**
 * Text cleanup applied before speaking a reply aloud. The markdown rules live in
 * [MarkdownProcessor] (`:sharedLogic`, `commonMain`), which iOS speech shares; only
 * the emoji strip is Android-side.
 */
object SpeechText {

    private val EMOJI = Regex("[\\p{So}\\p{Cn}]")

    /** Drops emoji and other symbol codepoints a TTS engine would spell out, then strips markdown. */
    fun forSpeech(text: String): String =
        MarkdownProcessor.stripMarkdown(text.replace(EMOJI, ""))
}
