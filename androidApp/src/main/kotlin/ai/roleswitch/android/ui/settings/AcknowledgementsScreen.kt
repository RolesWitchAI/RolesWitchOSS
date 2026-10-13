//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.roleswitch.android.R
import ai.roleswitch.android.ui.util.openUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val APACHE_ASSET = "licenses/apache-2.0.txt"
private const val MIT_OPENAI_ASSET = "licenses/mit-openai-kotlin.txt"
private const val MIT_MARKDOWN_ASSET = "licenses/mit-compose-markdown.txt"

/**
 * Where a component's licence text comes from.
 *
 * [Bundled] entries ship in `assets/licenses/` and open in-app. That is not a stylistic
 * choice: Apache 2.0 §4(a) obliges a redistributor to give recipients *a copy* of the
 * licence, and the MIT terms require the notice to travel with the software — a
 * hyperlink to apache.org satisfies neither if the device is offline.
 *
 * [External] is for terms of service that are not redistributable licences at all
 * (Google's), where pointing at the canonical, always-current document is correct.
 */
private sealed interface LicenseRef {
    data class Bundled(val assetPath: String) : LicenseRef
    data class External(val url: String) : LicenseRef
}

private data class ThirdPartyLibrary(
    val name: String,
    val owner: String,
    val license: String,
    val ref: LicenseRef
)

/**
 * Hand-maintained because there is no licence-collector plugin in the build. Every
 * entry was checked against the artifact's own POM or its repository, not assumed —
 * ML Kit and Play services in particular are *not* open source, and filing them under
 * Apache 2.0 here would be a false notice. Keep in step with `androidApp/build.gradle.kts`.
 */
private val thirdPartyLibraries = listOf(
    ThirdPartyLibrary(
        "Android Jetpack (Compose, Room, CameraX, DataStore, Navigation)",
        "Google", "Apache 2.0", LicenseRef.Bundled(APACHE_ASSET)
    ),
    ThirdPartyLibrary(
        "Kotlin, Coroutines, Serialization, DateTime",
        "JetBrains", "Apache 2.0", LicenseRef.Bundled(APACHE_ASSET)
    ),
    ThirdPartyLibrary("Ktor", "JetBrains", "Apache 2.0", LicenseRef.Bundled(APACHE_ASSET)),
    ThirdPartyLibrary(
        "OpenAI Kotlin client",
        "Mouaad Aallam", "MIT", LicenseRef.Bundled(MIT_OPENAI_ASSET)
    ),
    ThirdPartyLibrary(
        "compose-markdown",
        "Jeziel Lago", "MIT", LicenseRef.Bundled(MIT_MARKDOWN_ASSET)
    ),
    ThirdPartyLibrary("Android Image Cropper", "CanHub", "Apache 2.0", LicenseRef.Bundled(APACHE_ASSET)),
    ThirdPartyLibrary(
        "LiteRT-LM (on-device inference)",
        "Google", "Apache 2.0", LicenseRef.Bundled(APACHE_ASSET)
    ),
    ThirdPartyLibrary("Vosk", "Alpha Cephei", "Apache 2.0", LicenseRef.Bundled(APACHE_ASSET))
)

/**
 * Third-party attribution screen, reached from the Settings overflow menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcknowledgementsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var licenseAsset by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.acknowledgements)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                stringResource(R.string.acknowledgements_intro),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            SettingsCard {
                thirdPartyLibraries.forEachIndexed { index, library ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    SettingsRow(
                        title = library.name,
                        value = library.license,
                        subtitle = library.owner,
                        onClick = {
                            when (val ref = library.ref) {
                                is LicenseRef.Bundled -> licenseAsset = ref.assetPath
                                is LicenseRef.External -> openUrl(context, ref.url)
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    licenseAsset?.let { asset ->
        LicenseSheet(assetPath = asset, onDismiss = { licenseAsset = null })
    }
}

/**
 * Shows a licence shipped in `assets/`. The read is done off the main thread — the
 * Apache text is ~11 KB, small enough that it would probably go unnoticed, but there is
 * no reason to block a frame on file I/O.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseSheet(assetPath: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text by produceState(initialValue = "", assetPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(assetPath).bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                stringResource(R.string.ack_license_text),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}
