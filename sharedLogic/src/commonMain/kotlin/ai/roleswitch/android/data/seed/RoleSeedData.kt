//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.seed

import ai.roleswitch.android.data.model.Role

internal data class RoleTranslation(
    val name: String,
    val summary: String,
    val prompt: String,
    /**
     * The opening line a thread created from this role starts with; empty means the
     * thread starts silent.
     *
     * Lives here, beside the name and prompt, because it has to be frozen in the same
     * language as they are. It used to be read from a platform string at the moment the
     * thread was created, so changing the system language after the roles were seeded
     * produced a Russian character greeting in English.
     */
    val greeting: String = ""
)

internal data class LocalizedRole(
    val id: String,
    val translations: Map<String, RoleTranslation>
)

internal object RoleSeedData {
    const val DEFAULT_THREAD_ID = "00000000-0000-0000-0000-000000000000"

    val seedRoles = listOf(
        LocalizedRole(
            id = "roles_witch",
            translations = mapOf(
                "en" to RoleTranslation(
                    name = "Roles Witch",
                    summary = "Assistant that can solve math and other problems.",
                    prompt = """
                        Just answer don't describe images.
                        Be polite, solve math or any issues.
                        Your name is Roles Witch and you're good in math and other problems.
                    """.trimIndent(),
                    // The default thread's first message. Was R.string.welcome_message
                    // / SeedContent.welcome; it belongs with the prompt it introduces.
                    greeting = "Hello, I'm Roles Witch and I'm your smart assistant."
                ),
                "ru" to RoleTranslation(
                    name = "Roles Witch",
                    summary = "Ассистент, который решает математические и другие задачи.",
                    prompt = """
                        Ты решатель задач по математике. Просто давай ответ.
                        Будь прямым и кратким. Показывай решение только если прямо попросят.
                    """.trimIndent(),
                    greeting = "Привет, я Roles Witch и я твой умный ассистент."
                )
            )
        ),
    )

    fun getTranslation(role: LocalizedRole, locale: String): RoleTranslation? {
        return role.translations[locale] ?: role.translations["en"]
    }

    fun toRoles(locale: String): List<Role> {
        return seedRoles.mapNotNull { localizedRole ->
            getTranslation(localizedRole, locale)?.let { translation ->
                Role(
                    id = localizedRole.id,
                    name = translation.name,
                    prompt = translation.prompt,
                    summary = translation.summary,
                    greeting = translation.greeting
                )
            }
        }
    }
}
