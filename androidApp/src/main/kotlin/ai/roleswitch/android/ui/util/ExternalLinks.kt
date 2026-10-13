//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

const val PRIVACY_POLICY_URL = "https://roleswitch.ai/privacy-policy.html"
const val TERMS_URL = "https://roleswitch.ai/terms-and-conditions.html"
const val SUPPORT_EMAIL = "hello@roleswitch.ai"
const val VOSK_URL = "https://alphacephei.com/vosk/"

/**
 * Hands [url] to whatever the user has set as their browser.
 *
 * Silently does nothing if no activity can handle the intent. A device with no
 * browser is unusual but possible (locked-down enterprise images, bare emulators),
 * and none of the call sites — legal links, the local-server guide — are important
 * enough to crash the app over.
 */
fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}

/**
 * Opens the user's mail client with a message pre-filled.
 *
 * [subject] and [body] are percent-encoded into the `mailto:` URI *and* duplicated into
 * `EXTRA_SUBJECT`/`EXTRA_TEXT`. Both are needed: under `ACTION_SENDTO` the URI is the
 * canonical description of the draft (RFC 6068), and several clients — Gmail included —
 * build the compose window from it alone and drop `EXTRA_TEXT`, which silently loses the
 * body. Other clients read only the extras. Sending both is what makes this work
 * everywhere.
 *
 * `ACTION_SENDTO` throws [android.content.ActivityNotFoundException] when nothing is
 * registered for `mailto:` — the default state of most emulator images and of tablets
 * shipped without a mail app — so the launch is guarded.
 */
fun sendMail(context: Context, to: String, subject: String, body: String? = null) {
    runCatching {
        // `to` is left unencoded: it is a bare addr-spec, and percent-encoding its "@"
        // confuses clients that split the recipient on it.
        val mailto = buildString {
            append("mailto:").append(to)
            append("?subject=").append(Uri.encode(subject))
            if (body != null) append("&body=").append(Uri.encode(body))
        }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = mailto.toUri()
            putExtra(Intent.EXTRA_SUBJECT, subject)
            if (body != null) putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(intent)
    }
}
