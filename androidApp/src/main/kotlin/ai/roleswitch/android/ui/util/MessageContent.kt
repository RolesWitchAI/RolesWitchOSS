//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.util

/**
 * Placeholder body stored for an image-only user message (the chat UI hides it
 * and renders just the attachment). Written by the send path and matched by the
 * bubble renderer, so both sides must agree on the exact literal.
 */
const val IMAGE_ONLY_MESSAGE = "[Image]"

/**
 * Same contract for a document-only user message: the bubble hides the placeholder
 * and renders the attached document's chips instead. Mirrored on iOS by
 * `MessagesView.documentOnlyMessage` — the two must agree on the exact literal.
 */
const val DOCUMENT_ONLY_MESSAGE = "[Document]"
