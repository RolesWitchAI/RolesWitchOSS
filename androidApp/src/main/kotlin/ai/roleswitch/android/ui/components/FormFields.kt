//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app's placeholder color, used by every text field through the helpers below.
 *
 * `onSurfaceVariant` is Material's own choice for placeholder and supporting text, and it
 * is the right one: a placeholder is the least important text in a field — a hint that
 * disappears as soon as you type — so it should not wear an accent. The API fields were
 * tinted `tertiary` for a while, which read as an already-entered value (their placeholder
 * is an example URL) and, with dynamic color on, changed hue with the user's wallpaper.
 *
 * Defined once so this stays a single decision rather than a per-screen habit.
 */
private val placeholderColor
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

/**
 * Colors for every [androidx.compose.material3.OutlinedTextField] in the app. The container
 * stays transparent, so a field takes on whatever is behind it.
 *
 * There is deliberately only one of these. A second helper used to pin the fill to `surface`,
 * on the theory that it kept a field looking the same on a screen and in a sheet. It did the
 * opposite: screens *are* `surface`, so the pin was invisible there, while a sheet is
 * `surfaceContainerLow`, so the same pin painted an off-colour box on every sheet it appeared
 * in — and because the two tokens invert between themes (light: `surface` is the lighter of
 * the pair; dark: the darker), that box read as a bright panel in one theme and a dark hole in
 * the other. Staying transparent is what actually makes a field match its background, in both
 * places and both themes.
 */
@Composable
fun appOutlinedTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedPlaceholderColor = placeholderColor,
    unfocusedPlaceholderColor = placeholderColor
)

/**
 * Colors for the chat composer — the one field in the app that is not a form field, and the
 * only filled [androidx.compose.material3.TextField] left.
 *
 * A border around the input bar reads as a form row dropped into the middle of a conversation,
 * so the composer keeps Material's filled treatment and clears the indicator line under it.
 * Every other field in the app is outlined; this is the deliberate exception.
 */
@Composable
fun composerTextFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedPlaceholderColor = placeholderColor,
    unfocusedPlaceholderColor = placeholderColor,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent
)
