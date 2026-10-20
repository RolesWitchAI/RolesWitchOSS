//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import co.touchlab.kermit.Logger
import com.aallam.openai.api.audio.SpeechRequest
import com.aallam.openai.api.audio.SpeechResponseFormat
import com.aallam.openai.api.audio.Voice
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.seconds

private const val TAG = "ExternalTtsClient"
private val log = Logger.withTag(TAG)

/**
 * Raised when the external speech server is not configured at all — the endpoint string
 * is blank. Call sites surface this as "Voice is not configured"; every other failure of
 * [ExternalTtsClient.synthesize] (bad URL, network, HTTP status) is a server-side problem
 * and reads as "Server error".
 *
 * Public because `:androidApp` builds against `:sharedLogic` as a separate Gradle module —
 * the same reason the [InferenceEngine] seam is public.
 */
class ExternalTtsNotConfiguredException : Exception("External TTS server is not configured")

/**
 * Synthesizes speech through the OpenAI-compatible `POST /v1/audio/speech` endpoint —
 * the very same server line the chat already targets, reached through [OAIService]'s
 * host-URL helpers so this client and the LLM client can never disagree about how an
 * endpoint string maps to a base URL.
 *
 * Shares the config gate the server settings use: the endpoint
 * ([EffectiveSettings.externalTtsAPI]) and token ([EffectiveSettings.externalTtsKey]).
 * Those are the *speech* settings, deliberately distinct from the inference
 * `openaiAPI`/`openaiKey` — the user may run the chat and the voice on different
 * servers. One call, AAC out — the only container
 * Android's MediaPlayer plays natively with zero third-party decoders.
 *
 * Deliberately thin: request in, `ByteArray` out, errors as exceptions. Callers own the
 * "not configured" vs "server error" mapping, and `@Throws` keeps it bridgeable to
 * iOS (where a Swift `AVPlayer` plays the same bytes — ADTS AAC, which the strict
 * `AACAudioFile` parser behind `AVAudioPlayer` rejects) without any Kotlin-side adapter.
 */
class ExternalTtsClient(
    /**
     * Explicit ktor engine for the speech server client — the same test seam
     * [OAIService] carries. Null in production (platform default engine); tests
     * inject a `MockEngine` serving canned `/v1/audio/speech` replies.
     *
     * No default value on purpose: Kotlin default arguments do not export to
     * ObjC/Swift, and the iOS app builds this client with a plain `ExternalTtsClient()`.
     * The production no-argument shape is the secondary constructor below.
     */
    private val engine: HttpClientEngine?
) {

    /** The production entry point (and the initializer Swift sees). */
    constructor() : this(null)


    /**
     * @param endpoint the OpenAI-compatible base URL, e.g. `http://0.0.0.0:9000/v1`.
     * @param token the API key (may be empty — local servers often need none).
     * @param input the text to speak.
     * @param voice the server-side voice id (e.g. `alloy`); null/blank lets the server
     *   pick its default voice.
     * @throws ExternalTtsNotConfiguredException when [endpoint] is blank.
     * @throws Exception on anything else — an unreachable server, a non-2xx status, a
     *   malformed URL, a timeout. `CancellationException` propagates untouched.
     */
    @Throws(Exception::class)
    suspend fun synthesize(endpoint: String, token: String, input: String, voice: String?): ByteArray {
        if (endpoint.isBlank()) throw ExternalTtsNotConfiguredException()

        val (baseURL, proxyPath) = parseBaseUrlAndPath(endpoint)
            ?: throw IllegalStateException("Invalid TTS server URL")
        val host = OpenAIHost(baseUrl = buildHostUrl(baseURL, proxyPath))
        val config = OpenAIConfig(
            token = token,
            host = host,
            timeout = Timeout(request = 60.seconds, connect = 10.seconds, socket = 60.seconds),
            // One attempt, no retries, exactly like OAIService.fetchModels: the failure
            // modes here (server down, 401, bad voice) are not transient, and retrying
            // them merely multiplies the wait before the error is shown.
            retry = RetryStrategy(maxRetries = 0),
            engine = engine
        )
        val openAI = OpenAI(config)

        log.d { "Synthesizing ${input.length} chars via $baseURL voice=${voice ?: "<server default>"}" }
        return try {
            openAI.speech(
                SpeechRequest(
                    model = ModelId("tts-1"),
                    input = input,
                    voice = voice?.takeIf { it.isNotBlank() }?.let { Voice(it) },
                    responseFormat = SpeechResponseFormat.Aac
                )
            )
        } catch (e: CancellationException) {
            // Never swallow cancellation — let it propagate to the coroutine machinery.
            throw e
        } catch (e: Exception) {
            log.e(throwable = e, tag = TAG) { "Speech synthesis failed: ${e.message}" }
            throw e
        }
    }
}