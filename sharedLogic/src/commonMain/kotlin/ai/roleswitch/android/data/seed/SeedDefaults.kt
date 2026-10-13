//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.seed

import ai.roleswitch.android.data.database.AppDatabase
import ai.roleswitch.android.settings.SettingsManager

/**
 * TEST SEED — canned messages to drop into the welcome thread on first seed, so the chat
 * can be exercised with a history long enough to show scrolling cost and memory use.
 *
 * Each platform passes this to [seedDefaults] in debug builds and `0` in release, since
 * only the platform knows which kind of build it is (`BuildConfig.DEBUG` / `#if DEBUG`).
 * A real user must never meet the fake conversation.
 */
const val STRESS_SEED_MESSAGE_COUNT = 1000

/**
 * The device language, narrowed to one the app actually supports.
 *
 * Derived from [SettingsManager.defaultLanguages] rather than a literal list: the two used
 * to be spelled out separately and had drifted apart on `pt`, so a Portuguese device seeded
 * an English prompt while the app language was set to Portuguese — and the seeded assistant
 * prompt asks the model to reply in the user's language.
 *
 * Note this is the *app* language; the seed data itself only ships `en` and `ru`
 * translations and falls back to `en` for the rest (`RoleSeedData.getTranslation`).
 *
 * @param deviceLanguage a bare language code from the platform (`Locale.getDefault().language`
 *   on Android, `Locale.current.language.languageCode` on iOS).
 */
fun resolveSeedLocale(deviceLanguage: String): String =
    if (deviceLanguage in SettingsManager.defaultLanguages) deviceLanguage else "en"

/**
 * Re-creates the default "Roles Witch" thread and the seed roles if they are missing, and
 * repairs the thread's prompt if a previous seed left it empty.
 *
 * [defaultAvatarBytes] seeds the default thread's avatar with the platform's bundled profile
 * image; each platform encodes its own asset (Android's `R.drawable.profile` JPEG, iOS's
 * `Profile` UIImage) because the seed runs in shared code with no access to either.
 *
 * **Onboarding is the only caller by design.** The thread can be deleted, so seeding
 * anywhere else (on launch, say) would bring it back uninvited. Settings → Reset used to
 * call this too; it no longer does, because [SettingsManager.reset] sets
 * `hasCompletedOnboarding` to false on a `StateFlow` both platforms observe, so a reset
 * always lands on onboarding and would seed twice at once — see the crash documented on
 * `AppDatabase.seedMutex`.
 *
 * Being the only caller is not enough on its own, hence the guard: the onboarding screen
 * only has to be *composed* for a frame — before the completion flag has been read back and
 * the root has switched away from it — for its launch effect to run. On iOS that happens on
 * every launch, because the flag reaches SwiftUI one main-loop hop after the view first
 * renders, so a user who deleted the welcome thread found it waiting for them on the next
 * start. `hasCompletedOnboarding` is safe to read synchronously here: [SettingsManager]
 * loads its snapshot with `runBlocking` in its constructor, so `.value` is never a stale
 * default. Onboarding itself runs before the flag is set, so a genuine first run still
 * seeds, and a reset clears the flag and lands back on onboarding, so that still seeds too.
 *
 * Shared rather than written twice: the guard, the locale narrowing and the stress count are
 * the parts that must not drift between platforms. What is left on each side is only what
 * that platform alone can answer — its locale API and whether this is a debug build.
 *
 * [AppDatabase.seedDefaultThread] seeds the roles inside its own lock, so there is no
 * separate `seedRolesIfEmpty` call to make here.
 */
suspend fun seedDefaults(
    database: AppDatabase,
    settingsManager: SettingsManager,
    deviceLanguage: String,
    stressMessageCount: Int = 0,
    defaultAvatarBytes: ByteArray? = null
) {
    if (settingsManager.hasCompletedOnboarding.value) return
    database.seedDefaultThread(
        locale = resolveSeedLocale(deviceLanguage),
        defaultAvatarBytes = defaultAvatarBytes
    )
    if (stressMessageCount > 0) {
        database.seedStressMessagesInDefaultThread(stressMessageCount)
    }
}
