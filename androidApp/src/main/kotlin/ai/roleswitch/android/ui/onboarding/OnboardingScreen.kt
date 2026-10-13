//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import ai.roleswitch.android.ui.util.TERMS_URL
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NoAccounts
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import ai.roleswitch.android.ui.theme.FeatureAccentsDark
import ai.roleswitch.android.ui.theme.FeatureAccentsLight
import ai.roleswitch.android.R
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.data.resolveSeedLocale
import ai.roleswitch.android.data.seedDefaults
import ai.roleswitch.android.service.onDeviceInferenceSupported
import ai.roleswitch.android.settings.SettingsManager
import ai.roleswitch.android.ui.util.openUrl
import kotlinx.coroutines.launch

/**
 * Welcome and privacy — the same two steps as `OnboardingView.swift`. It collects
 * nothing: the endpoint/token/model step Android used to show is gone, and
 * `openaiAPI`, `openaiKey` and `selectedModel` stay at their empty defaults until
 * the user fills them in under Settings. The chat surfaces already refuse to send
 * until *something* is configured (`EffectiveSettings.isConfigured`).
 *
 * Finishing pre-selects the on-device engine where this phone can run it at all
 * (`onDeviceInferenceSupported`, Android 12+), the same write iOS's
 * `completeOnboarding()` makes; below that the option is not offered (RolesWitchApp
 * passes `onDeviceEngine = null`) and the remote endpoint stays the engine.
 */
enum class OnboardingStep { WELCOME, PRIVACY }

@Composable
fun OnboardingScreen(app: RolesWitchApp) {
    val settingsManager = app.settingsManager
    var currentStep by remember { mutableIntStateOf(0) }
    val steps = OnboardingStep.entries

    val scope = rememberCoroutineScope()

    // Seed roles on first appear
    LaunchedEffect(Unit) {
        settingsManager.setLanguage("en")
        app.seedDefaults()
    }

    val completeOnboarding: () -> Unit = {
        scope.launch {
            // Same resolver as the seeding above: these were two separate
            // literal lists and had drifted on "pt", so a Portuguese device
            // seeded an English prompt but ran the app in Portuguese.
            val language = resolveSeedLocale()
            settingsManager.setLanguage(language)
            val sttLocale = SettingsManager.localeForSTT[language]
            // Onboarding only auto-enables Vosk for the bundled locales
            // (en-US, ru-RU). Downloadable locales require the user to
            // explicitly trigger the download from Settings.
            if (sttLocale != null && ai.roleswitch.android.service.VoskModels.get(sttLocale)?.bundled == true) {
                settingsManager.setUseVosk(true)
            } else {
                settingsManager.setUseVosk(false)
            }
            // Only the engine — no model is picked here. Below Android 12 nothing
            // is written: the stored false is right, and on-device is not available.
            if (onDeviceInferenceSupported) {
                settingsManager.setUseOnDevice(true)
            }
            settingsManager.setHasCompletedOnboarding(true)
        }
    }

    // Everything — step content *and* the navigation buttons — lives in one scroll
    // container, so whatever the keyboard covers can simply be scrolled to.
    // `safeDrawing` includes the IME inset, so `maxHeight` here is the real viewport:
    // it shrinks when the keyboard opens and the column starts scrolling. Reading it
    // via BoxWithConstraints is what lets the column be "at least one screen tall"
    // without weights — `Modifier.weight` resolves to zero inside a verticalScroll,
    // since the column is measured with an unbounded max height.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // 20dp at the sides, matching where `OnboardingView.swift` puts its cards;
            // the extra 4dp here made every row read wider-margined than on iOS.
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            AnimatedContent(targetState = steps[currentStep], label = "onboarding") { step ->
                when (step) {
                    OnboardingStep.WELCOME -> WelcomeStep()
                    OnboardingStep.PRIVACY -> PrivacyStep()
                }
            }

            // Navigation buttons. SpaceBetween drops these at the bottom edge while the
            // content is short; the top padding is the minimum gap for when it isn't and
            // there is no free space left to distribute.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (currentStep > 0) {
                    OutlinedButton(
                        onClick = { currentStep-- },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(stringResource(R.string.back), modifier = Modifier.padding(vertical = 8.dp))
                    }
                }

                Button(
                    onClick = {
                        if (currentStep < steps.size - 1) {
                            currentStep++
                        } else {
                            completeOnboarding()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        if (currentStep < steps.size - 1) stringResource(R.string.next)
                        else stringResource(R.string.finish_setup),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Image(
            painter = painterResource(R.drawable.profile),
            contentDescription = null,
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )

        // 12 / 12 / 32, the spacings `OnboardingView.swift` gets from its two nested
        // VStacks. Android ran 32 / 8 / 24, which put most of the gap directly under the
        // icon — the one place the difference is easiest to see.
        Spacer(Modifier.height(12.dp))

        Text(
            stringResource(R.string.hello_roles_witch),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            style = LocalTextStyle.current.copy(lineHeightStyle = TrimmedLineHeight),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.your_voice_assistant),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = LocalTextStyle.current.copy(lineHeightStyle = TrimmedLineHeight),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureRow(
                icon = "device",
                title = stringResource(R.string.on_device_ai),
                description = stringResource(R.string.on_device_ai_desc)
            )
            FeatureRow(icon = "voice", title = stringResource(R.string.voice_conversations), description = stringResource(R.string.voice_conversations_desc))
            FeatureRow(icon = "roles", title = stringResource(R.string.multiple_roles), description = stringResource(R.string.multiple_roles_desc))
        }
    }
}

/**
 * Privacy, ported from `OnboardingView.swift`'s second step — now the last one.
 */
@Composable
private fun PrivacyStep() {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            stringResource(R.string.before_we_begin),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            style = LocalTextStyle.current.copy(lineHeightStyle = TrimmedLineHeight),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.respects_your_privacy),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = LocalTextStyle.current.copy(lineHeightStyle = TrimmedLineHeight),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureRow(
                icon = "anonymous",
                title = stringResource(R.string.no_signup),
                description = stringResource(R.string.no_signup_desc)
            )
            FeatureRow(
                icon = "tracking",
                title = stringResource(R.string.no_tracking),
                description = stringResource(R.string.no_tracking_desc)
            )
            FeatureRow(
                icon = "local",
                title = stringResource(R.string.local_processing),
                description = stringResource(R.string.local_processing_desc)
            )
        }

        Spacer(Modifier.height(16.dp))

        TextButton(onClick = { openUrl(context, TERMS_URL) }) {
            Text(stringResource(R.string.terms_and_conditions), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * One feature, as a card — the shape `OnboardingView.swift` gives its rows, down to the
 * 14dp corner, the 20/12 inner padding and the 50dp icon well. It used to be a bare Row
 * on a plain background, which read as a list where iOS read as a stack of cards.
 *
 * The fill is the app's own card colour (`SettingsCard`), the closest thing here to the
 * secondary background iOS fills with.
 */
/**
 * Trims the font's own leading above the first line and below the last, and centres what
 * is left. Compose reserves that space by default where SF does not, which is what made a
 * wrapped card subtitle look loosely set next to the SwiftUI build's.
 */
private val TrimmedLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both
)

@Composable
private fun FeatureRow(icon: String, title: String, description: String) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // One accent per row — purple, blue, orange — drawn saturated on a 15%-alpha
            // circle of itself, as in the SwiftUI build. The accent carries both, so the two
            // can never drift into the low-contrast pairing this used to have.
            val accents = if (isSystemInDarkTheme()) FeatureAccentsDark else FeatureAccentsLight
            // One accent per row *within a step*, so no two rows on screen share a colour:
            // welcome runs purple/blue/orange, privacy green/purple/orange — the same
            // assignments `OnboardingView.swift` gives its rows.
            val accent = when (icon) {
                "device", "tracking" -> accents[0]
                "voice" -> accents[1]
                "roles", "local" -> accents[2]
                "anonymous" -> accents[3]
                else -> accents[0]
            }
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (icon) {
                        // A chip, not a phone: the SwiftUI build's `cpu.fill`. The phone glyph
                        // belongs to the privacy step's engine row, which is about *where* the
                        // work happens rather than about running without a network.
                        "device" -> Icons.Default.Memory
                        // Privacy rows, in the same shape as the welcome ones.
                        "anonymous" -> Icons.Default.NoAccounts
                        "tracking" -> Icons.Default.VisibilityOff
                        "local" -> Icons.Default.PhoneAndroid
                        // A waveform rather than a microphone, and theatre masks rather than a
                        // person — the SwiftUI build's waveform.circle.fill and theatermasks.fill.
                        "voice" -> Icons.Default.GraphicEq
                        "roles" -> Icons.Default.TheaterComedy
                        else -> Icons.Default.PhoneAndroid
                    },
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    style = LocalTextStyle.current.copy(lineHeightStyle = TrimmedLineHeight)
                )
                Text(
                    description,
                    fontSize = 13.sp,
                    // ~1.23x, where SF sets 13pt at about 1.19x. Compose's default here is
                    // nearer 1.4x, so a two-line subtitle gained a visible extra gap.
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = LocalTextStyle.current.copy(lineHeightStyle = TrimmedLineHeight)
                )
            }
        }
    }
}
