//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.seed

/**
 * What a brand-new conversation starts as, on both platforms.
 *
 * The role template picker is gone — the "+" / compose control opens the create sheet
 * directly — and this is the one template left: the Virtual Character seed role, moved out
 * of [RoleSeedData] because that list now contains only `roles_witch` (the default thread's
 * assistant). It still ships `en` + `ru` translations like the seed roles did, and each
 * platform resolves the language exactly the way the seed does ([resolveSeedLocale]) so the
 * empty-thread defaults cannot drift in language from the seeded assistant.
 */
object NewThreadDefaults {
    class Content(val name: String, val prompt: String, val greeting: String)

    private val en = Content(
        name = "Virtual Character",
        prompt = """
            You're a close friend. Your name is Virtual Character.
            Answer 2-3 sentences.
        """.trimIndent(),
        greeting = "Hello"
    )

    private val ru = Content(
        name = "Виртуальный персонаж",
        prompt = """
            Ты близкий друг. Тебя зовут Виртуальный персонаж.
            Отвечай 2-3 предложениями.
        """.trimIndent(),
        greeting = "Привет"
    )

    fun forLanguage(locale: String): Content = if (locale == "ru") ru else en
}