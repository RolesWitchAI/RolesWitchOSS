//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import ai.roleswitch.android.settings.EffectiveSettings
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Helpers shared by the MockEngine-driven service tests. */

/**
 * An `EffectiveSettings` with the exact call shape `OAIService.generate` accepts —
 * every generation parameter populated, the way a configured remote thread resolves.
 */
fun remoteEngineSettings(
    openaiAPI: String = "http://mock.test/v1",
    openaiKey: String = "test-token",
    selectedModel: String = "test-model",
    enableVision: Boolean = false,
    temperature: String = "0.7",
    topP: String = "0.9",
    n: String = "2",
    maxCompletionTokens: String = "256",
    presencePenalty: String = "0.1",
    frequencyPenalty: String = "0.2",
    reasoningEffort: String = "low"
): EffectiveSettings = EffectiveSettings(
    openaiAPI = openaiAPI,
    openaiKey = openaiKey,
    selectedModel = selectedModel,
    enableVision = enableVision,
    messageLimit = 50,
    temperature = temperature,
    topP = topP,
    n = n,
    maxCompletionTokens = maxCompletionTokens,
    presencePenalty = presencePenalty,
    frequencyPenalty = frequencyPenalty,
    reasoningEffort = reasoningEffort,
    language = "en",
    voice = "",
    requireOnDeviceVoice = false,
    useVosk = false,
    allowInterruptions = false,
    embedTime = false,
    keepLastAttachment = false,
    useSystemTts = false,
    useExternalTts = false,
    externalTtsVoice = "",
    externalTtsAPI = "",
    externalTtsKey = "",
    streamingMode = true,
    enabledToolIds = emptyList(),
    useOnDevice = false,
    litertModel = "",
    litertTopK = "",
    litertTopP = "",
    litertTemperature = "",
    litertMaxTokens = "",
    litertAccelerator = "",
    litertEnableThinking = false
)

/** One chat-completion stream chunk, in the wire shape openai-client parses. */
fun sseChunk(delta: String): String =
    """{"id":"1","object":"chat.completion.chunk","created":0,"model":"test-model","choices":[{"index":0,"delta":$delta}]}"""

/** A full SSE body from chunk deltas (with the terminating [DONE] sentinel). */
fun sseBody(vararg deltas: String): String =
    deltas.joinToString("") { "data: ${sseChunk(it)}\n\n" } + "data: [DONE]\n\n"

fun MockRequestHandleScope.respondSse(body: String): HttpResponseData = respond(
    body,
    HttpStatusCode.OK,
    headersOf(HttpHeaders.ContentType, "${ContentType.Text.EventStream}; charset=utf-8")
)

fun MockRequestHandleScope.respondJson(
    json: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    extraHeaders: Headers = headersOf()
): HttpResponseData = respond(
    json,
    status,
    Headers.build {
        append(HttpHeaders.ContentType, "${ContentType.Application.Json}; charset=utf-8")
        appendAll(extraHeaders)
    }
)

fun MockRequestHandleScope.respondErrorJson(status: HttpStatusCode, json: String): HttpResponseData =
    respondJson(json, status)

/** The JSON body a request carried, as a parsed object. */
fun HttpRequestData.jsonBody(): JsonElement =
    Json.parseToJsonElement((body as io.ktor.http.content.TextContent).text)

/** The JSON-RPC `method` of a posted envelope. */
fun HttpRequestData.rpcMethod(): String =
    jsonBody().jsonObject["method"]!!.jsonPrimitive.content

/** The JSON-RPC `id` of a posted envelope, as its wire string. */
fun HttpRequestData.rpcId(): String =
    jsonBody().jsonObject["id"]?.jsonPrimitive?.content ?: ""

fun HttpRequestData.rpcParams(): JsonElement =
    jsonBody().jsonObject["params"] ?: kotlinx.serialization.json.JsonNull

// ---- chat-completion stream deltas, built as real JSON so escaping is never hand-rolled --

fun contentDelta(text: String): String = buildJsonObject { put("content", text) }.toString()

fun reasoningDelta(text: String): String = buildJsonObject { put("reasoning_content", text) }.toString()

fun toolCallDelta(index: Int, id: String?, name: String?, arguments: String): String = buildJsonObject {
    put(
        "tool_calls",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("index", index)
                    if (id != null) {
                        put("id", id)
                        put("type", "function")
                    }
                    put(
                        "function",
                        buildJsonObject {
                            if (name != null) put("name", name)
                            put("arguments", arguments)
                        }
                    )
                }
            )
        }
    )
}.toString()
