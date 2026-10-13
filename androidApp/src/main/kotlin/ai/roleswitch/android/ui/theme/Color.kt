//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's palette, ported from the SwiftUI build of Roles Witch
 * (`Breakthrough-Swift6`): blue as the identity, with purple / blue / orange as the
 * per-feature accents on the onboarding rows.
 *
 * These replace the Compose wizard's default purple, which was never a choice anyone
 * made. Tones follow the Material 3 convention so [lightScheme]/[darkScheme] stay
 * legible without hand-checking every pairing: in light, a role is tone 40 with its
 * container at tone 90; in dark, tone 80 with its container at tone 30, and the
 * `on*` colors flip to match.
 */

// Primary — blue. The identity colour: buttons, switches, links.
val Blue40 = Color(0xFF1660D8)
val Blue80 = Color(0xFFADC7FF)
val Blue90 = Color(0xFFDBE6FF)
val Blue10 = Color(0xFF001B44)
val Blue20 = Color(0xFF002E6B)
val Blue30 = Color(0xFF004397)

// Secondary — the blue's neutral companion, for quieter chrome.
val Slate40 = Color(0xFF565E71)
val Slate80 = Color(0xFFBEC6DC)
val Slate90 = Color(0xFFDAE2F9)
val Slate10 = Color(0xFF131C2B)
val Slate20 = Color(0xFF283041)
val Slate30 = Color(0xFF3E4759)

// Tertiary — warm orange, the third accent in the SwiftUI onboarding.
val Orange40 = Color(0xFF8B5000)
val Orange80 = Color(0xFFFFB77C)
val Orange90 = Color(0xFFFFDCC2)
val Orange10 = Color(0xFF2D1600)
val Orange20 = Color(0xFF4A2800)
val Orange30 = Color(0xFF6A3B00)

// Error — Material's own red; there is no reason to invent one.
val Red40 = Color(0xFFBA1A1A)
val Red80 = Color(0xFFFFB4AB)
val Red90 = Color(0xFFFFDAD6)
val Red10 = Color(0xFF410002)
val Red20 = Color(0xFF690005)
val Red30 = Color(0xFF93000A)

// Neutrals, very slightly cooled towards the blue so the surfaces sit with it rather
// than reading as a separate grey. The light ladder runs surface -> containerHighest
// darker; the dark ladder runs the other way, which is what lets a transparent field
// take its background in either theme (see FormFields.kt).
val LightSurface = Color(0xFFFAF9FD)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF4F3F7)
val LightSurfaceContainer = Color(0xFFEEEDF1)
val LightSurfaceContainerHigh = Color(0xFFE8E7EC)
val LightSurfaceContainerHighest = Color(0xFFE2E2E6)
val LightSurfaceVariant = Color(0xFFE1E2EC)
val LightOnSurface = Color(0xFF1A1B20)
val LightOnSurfaceVariant = Color(0xFF44474F)
val LightOutline = Color(0xFF74777F)
val LightOutlineVariant = Color(0xFFC4C6D0)
val LightInverseSurface = Color(0xFF2F3036)
val LightInverseOnSurface = Color(0xFFF1F0F4)

val DarkSurface = Color(0xFF121318)
val DarkSurfaceContainerLowest = Color(0xFF0D0E13)
val DarkSurfaceContainerLow = Color(0xFF1A1B20)
val DarkSurfaceContainer = Color(0xFF1E1F25)
val DarkSurfaceContainerHigh = Color(0xFF292A2F)
val DarkSurfaceContainerHighest = Color(0xFF34343A)
val DarkSurfaceVariant = Color(0xFF44474F)
val DarkOnSurface = Color(0xFFE2E2E6)
val DarkOnSurfaceVariant = Color(0xFFC4C6D0)
val DarkOutline = Color(0xFF8E9099)
val DarkOutlineVariant = Color(0xFF44474F)
val DarkInverseSurface = Color(0xFFE2E2E6)
val DarkInverseOnSurface = Color(0xFF2F3036)

/**
 * The onboarding feature accents, one per row, as in the SwiftUI build — purple,
 * blue, orange. Each is drawn saturated on a 15%-alpha circle of itself.
 *
 * Kept out of the colour scheme because they are decoration, not roles: nothing else
 * in the app should reach for "the purple one". Two sets because a single mid-tone
 * cannot stay legible against both #FAF9FD and #121318 — these lighten for dark.
 */
val FeatureAccentsLight = listOf(
    Color(0xFF6E41E2), // purple
    Color(0xFF1660D8), // blue, the primary
    Color(0xFFC2600A), // orange
    Color(0xFF1E7A3E)  // green — the on-device and privacy rows, as in the SwiftUI build
)

val FeatureAccentsDark = listOf(
    Color(0xFFB9A1FF),
    Color(0xFF9EC0FF),
    Color(0xFFFFB77C),
    Color(0xFF7ADFA0)
)
