//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import kotlinx.coroutines.flow.StateFlow

/**
 * Speech-to-text contract implemented by [STTService] (platform recognizer) and
 * [VoskService] (offline). Android-only: iOS has its own native recognizer and
 * deliberately does not implement this, which is why it lives here rather than
 * in `:sharedLogic`.
 */
interface SpeechService {
    val isRecording: StateFlow<Boolean>
    val isListening: StateFlow<Boolean>
    val humanMessage: StateFlow<String>
    val finalMessage: StateFlow<String>
    val isTimedOut: StateFlow<Boolean>
    var onDevice: Boolean
    fun startRecording(locale: String)
    fun stopRecording()
    fun pauseListening()
    fun resumeListening()
    fun clearHumanMessage()

    /**
     * Releases the engine and any native handles. Implementations are
     * process-lifetime singletons held by `RolesWitchApp`, so in normal
     * operation the process dies first and nothing calls this — it exists for
     * teardown paths that own an instance (e.g. tests).
     */
    fun destroy()
}
