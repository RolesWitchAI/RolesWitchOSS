//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data

import ai.roleswitch.android.BuildConfig
import ai.roleswitch.android.R
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.data.seed.STRESS_SEED_MESSAGE_COUNT
import ai.roleswitch.android.data.seed.resolveSeedLocale
import ai.roleswitch.android.data.seed.seedDefaults as sharedSeedDefaults
import java.util.Locale

/** The device's bare language code, as the shared seed helpers want it. */
fun deviceLanguage(): String = Locale.getDefault().language

/** @see ai.roleswitch.android.data.seed.resolveSeedLocale */
fun resolveSeedLocale(): String = resolveSeedLocale(deviceLanguage())

/**
 * Android's half of the shared [ai.roleswitch.android.data.seed.seedDefaults]: supplies the
 * things only this platform can answer — the device locale, whether this is a debug build,
 * and the bundled profile image that seeds the default thread's avatar — and lets shared
 * code decide everything else, including whether to seed at all.
 *
 * `R.drawable.profile` is a `.jpg`, so `openRawResource` yields the original JPEG bytes
 * without a decode/re-encode round trip.
 *
 * Onboarding is the only caller; the shared function documents why, and guards it.
 */
suspend fun RolesWitchApp.seedDefaults() {
    val defaultAvatarBytes = resources.openRawResource(R.drawable.profile).use { it.readBytes() }
    sharedSeedDefaults(
        database = database,
        settingsManager = settingsManager,
        deviceLanguage = deviceLanguage(),
        // Debug builds only, so a real user never sees the fake conversation.
        stressMessageCount = if (BuildConfig.DEBUG) STRESS_SEED_MESSAGE_COUNT else 0,
        defaultAvatarBytes = defaultAvatarBytes
    )
}
