//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.seed

import ai.roleswitch.android.data.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeedDataTest {

    @Test
    fun seedLocaleNarrowsToALanguageTheAppSupports() {
        assertEquals("en", resolveSeedLocale("en"))
        assertEquals("ru", resolveSeedLocale("ru"))
        // `pt` is in defaultLanguages, so a Portuguese device keeps it…
        assertEquals("pt", resolveSeedLocale("pt"))
        // …while anything else falls back to English.
        assertEquals("en", resolveSeedLocale("ja"))
        assertEquals("en", resolveSeedLocale(""))
    }

    @Test
    fun theDefaultThreadKeepsItsFixedIdAcrossInstalls() {
        assertEquals("00000000-0000-0000-0000-000000000000", RoleSeedData.DEFAULT_THREAD_ID)
    }

    @Test
    fun everySeedRoleShipsEnglishAndRussianTranslations() {
        assertTrue(RoleSeedData.seedRoles.isNotEmpty())
        RoleSeedData.seedRoles.forEach { role ->
            val en = requireNotNull(role.translations["en"]) { "${role.id} has no en translation" }
            val ru = requireNotNull(role.translations["ru"]) { "${role.id} has no ru translation" }
            listOf(en, ru).forEach { translation ->
                assertTrue(translation.name.isNotBlank(), "${role.id} has a blank name")
                assertTrue(translation.summary.isNotBlank(), "${role.id} has a blank summary")
                assertTrue(translation.prompt.isNotBlank(), "${role.id} has a blank prompt")
                assertTrue(translation.greeting.isNotBlank(), "${role.id} has a blank greeting")
            }
        }
    }

    @Test
    fun toRolesUsesTheRequestedLocaleAndFallsBackToEnglish() {
        val en = RoleSeedData.toRoles("en")
        val ru = RoleSeedData.toRoles("ru")
        val fallback = RoleSeedData.toRoles("de")

        assertEquals(1, en.size)
        // `createdAt` is stamped per call, so equality is asserted field-wise.
        fun Role.withoutTimestamp() = listOf(id, name, prompt, summary, greeting)
        assertEquals(en[0].withoutTimestamp(), en[0].withoutTimestamp())
        assertEquals(en[0].withoutTimestamp(), fallback[0].withoutTimestamp())
        // A locale without a translation must resolve to the English row, not to nulls.
        assertTrue(ru[0].prompt != en[0].prompt, "ru and en prompts are expected to differ")
    }

    @Test
    fun newThreadDefaultsFollowTheLanguageWithAnEnglishFallback() {
        assertEquals("Привет", NewThreadDefaults.forLanguage("ru").greeting)
        assertEquals("Hello", NewThreadDefaults.forLanguage("en").greeting)
        // Anything unlisted is English, exactly like the seed roles.
        assertEquals(NewThreadDefaults.forLanguage("en"), NewThreadDefaults.forLanguage("fr"))
        assertTrue(NewThreadDefaults.forLanguage("ru").name.isNotBlank())
        assertTrue(NewThreadDefaults.forLanguage("ru").prompt.isNotBlank())
    }
}
