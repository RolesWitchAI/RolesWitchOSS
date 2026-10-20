//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import ai.roleswitch.android.service.mcp.McpToolSpec
import ai.roleswitch.android.settings.EffectiveSettings
import co.touchlab.kermit.Logger
import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.Effort
import com.aallam.openai.api.chat.FunctionCall
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolChoice
import com.aallam.openai.api.chat.ToolId
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.api.exception.OpenAIAPIException
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds

private const val TAG = "OAIService"
private val log = Logger.withTag(TAG)

/**
 * How many times the model may ask for tools before we stop feeding results back.
 * A model that loops (calling the same failing tool forever) would otherwise burn
 * the user's tokens indefinitely. On the last round tools are still advertised but
 * any further request is answered with a note telling the model to conclude.
 */
private const val MAX_TOOL_ROUNDS = 8

/**
 * One completed tool call, kept for the trace shown under the assistant message.
 *
 * Public (like the rest of the [InferenceEngine] seam) so the Android-only
 * on-device engine in `:androidApp` can produce one.
 */
data class ToolInvocation(
    val label: String,
    val arguments: String,
    val result: String
)

data class GenerationResult(
    val content: String,
    val reasoning: String,
    val toolCalls: List<ToolInvocation> = emptyList()
)

internal data class OAIModelConfig(
    val endpoint: String,
    val token: String,
    val modelName: String,
    val enableVision: Boolean,
    val temperature: Double? = null,
    val topP: Double? = null,
    val n: Int? = null,
    val maxCompletionTokens: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    /**
     * `reasoning_effort` as the wire value — `"none"`, `"low"`, `"medium"`, `"high"`.
     *
     * Null omits the field entirely rather than sending a null: openai-client serializes
     * with `encodeDefaults = false`, so an absent [Effort] never reaches the body and a
     * server that does not know the parameter sees the request unchanged. Kept as a String
     * here so [Effort] stays inside this file with the rest of the openai-client types.
     */
    val reasoningEffort: String? = null
)

sealed class OAIError : Exception() {
    internal data object InvalidURL : OAIError()
    internal data object TransientError : OAIError()
    data class InvalidResponse(val reason: String, val statusCode: Int? = null) : OAIError() {
        override val message: String
            get() = if (statusCode != null) "LLM Error (Status $statusCode): $reason" else "LLM Error: $reason"
    }

    val isSilent: Boolean
        get() = this is TransientError
}

data class LLMMessage(
    val role: LLMRole,
    val content: String,
    val imageData: List<ByteArray> = emptyList()
) {
    enum class LLMRole { USER, ASSISTANT }
}

class OAIService(
    /**
     * Explicit ktor engine for the OpenAI-compatible HTTP client. Production leaves it
     * null (the platform default engine resolves per target); tests inject a
     * `MockEngine` to serve canned `/v1/chat/completions` and `/v1/models` replies
     * without a network.
     */
    private val engine: HttpClientEngine? = null
) : InferenceEngine {
    private var generationJob: Job? = null

    /**
     * Maps the thread's resolved settings onto the request config. Blank numeric
     * fields mean "not set", and are omitted from the request so the server's own
     * defaults apply.
     */
    private fun configFor(settings: EffectiveSettings) = OAIModelConfig(
        endpoint = settings.openaiAPI,
        token = settings.openaiKey,
        modelName = settings.selectedModel,
        // `visionAvailable`, not `enableVision`: identical for this engine, but it
        // keeps every image decision in the app flowing through one property.
        enableVision = settings.visionAvailable,
        temperature = settings.temperature.takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
        topP = settings.topP.takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
        n = settings.n.takeIf { it.isNotEmpty() }?.toIntOrNull(),
        maxCompletionTokens = settings.maxCompletionTokens.takeIf { it.isNotEmpty() }?.toIntOrNull(),
        presencePenalty = settings.presencePenalty.takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
        frequencyPenalty = settings.frequencyPenalty.takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
        // No parsing to do — the wire values are the strings the picker stores.
        reasoningEffort = settings.reasoningEffort.takeIf { it.isNotEmpty() }
    )

    override fun cancel() {
        log.d { "Cancelling generation" }
        generationJob?.cancel()
        generationJob = null
    }

    /**
     * Streams one assistant turn, running the MCP tool loop on the way if [toolSpecs]
     * is non-empty.
     *
     * With no tools the request is byte-identical to what this sent before tool support
     * existed — `tools` / `tool_choice` are omitted entirely rather than sent empty, since
     * some OpenAI-compatible servers reject an empty array.
     *
     * @param toolSpecs functions to advertise; empty disables tool calling for this turn.
     * @param onToolCall invoked with true when a tool call starts and false once the
     *   round's calls are done, for the "Using a tool…" status. Called on the collecting
     *   coroutine — keep it cheap.
     * @param callTool executes a tool call and returns its result as text. Expected to
     *   report failures as text rather than throwing.
     */
    override suspend fun generate(
        messages: List<LLMMessage>,
        systemPrompt: String,
        settings: EffectiveSettings,
        onChunk: ((String) -> Unit)?,
        toolSpecs: List<McpToolSpec>,
        onToolCall: ((Boolean) -> Unit)?,
        callTool: (suspend (String, String) -> String)?
    ): GenerationResult {
        val config = configFor(settings)

        cancel()

        val (baseURL, proxyPath) = parseBaseUrlAndPath(config.endpoint)
            ?: throw OAIError.InvalidURL

        log.d { "Starting generation with ${messages.size} messages" }
        log.d { "Endpoint: $baseURL, Model: ${config.modelName}" }

        return try {
            coroutineScope {
                generationJob = coroutineContext[Job]
                val host = OpenAIHost(
                    baseUrl = buildHostUrl(baseURL, proxyPath)
                )
                val openAIConfig = OpenAIConfig(
                    token = config.token,
                    host = host,
                    timeout = Timeout(request = 600.seconds, connect = 30.seconds, socket = 600.seconds),
                    engine = engine
                )
                val openAI = OpenAI(openAIConfig)

                val chatMessages = buildList {
                    add(ChatMessage(role = ChatRole.System, content = systemPrompt))
                    messages.forEach { msg ->
                        val role = if (msg.role == LLMMessage.LLMRole.USER) ChatRole.User else ChatRole.Assistant
                        if (msg.imageData.isNotEmpty() && config.enableVision) {
                            val parts = buildList {
                                add(TextPart(msg.content))
                                msg.imageData.forEach { data ->
                                    val base64 = encodeBase64(data)
                                    add(ImagePart("data:image/jpeg;base64,$base64"))
                                }
                            }
                            add(ChatMessage(role = role, content = parts))
                        } else {
                            add(ChatMessage(role = role, content = msg.content))
                        }
                    }
                }

                // Remove assistant message right after system if present
                val conversation = chatMessages.toMutableList()
                if (conversation.size > 1 && conversation[1].role == ChatRole.Assistant) {
                    conversation.removeAt(1)
                }

                val tools = toolSpecs.takeIf { it.isNotEmpty() && callTool != null }?.map { spec ->
                    Tool.function(
                        name = spec.functionName,
                        description = spec.description,
                        parameters = Parameters(spec.parametersSchema)
                    )
                }

                // Only the *final* round's text is the answer. A model that narrates before
                // calling a tool ("Let me search for that…") emits that text alongside the
                // tool call; it already goes into `conversation` for the model's own benefit,
                // and the round that follows restates it — so accumulating every round left
                // the saved reply reading as a duplicated preamble.
                var answer = ""
                val fullReasoning = StringBuilder()
                val invocations = mutableListOf<ToolInvocation>()

                // Cleared once the round cap is hit, so the next request advertises no
                // tools at all. That is what guarantees termination: the model cannot ask
                // for another call, so the loop exits on the following pass.
                var activeTools = tools
                var round = 0
                while (true) {
                    val request = ChatCompletionRequest(
                        model = ModelId(config.modelName),
                        messages = conversation,
                        temperature = config.temperature,
                        topP = config.topP,
                        n = config.n,
                        maxCompletionTokens = config.maxCompletionTokens,
                        presencePenalty = config.presencePenalty,
                        frequencyPenalty = config.frequencyPenalty,
                        reasoningEffort = config.reasoningEffort?.let { Effort(it) },
                        tools = activeTools,
                        toolChoice = activeTools?.let { ToolChoice.Auto }
                    )

                    // Tool-call deltas arrive fragmented — the id and name land in one
                    // chunk and the arguments dribble in across many, all keyed by index.
                    val pending = mutableMapOf<Int, PartialToolCall>()
                    val roundContent = StringBuilder()

                    // NOTE if Ktor is ever bumped past 3.4: from 3.5 it dispatches streamed
                    // responses to Dispatchers.IO, so the emission context stops matching the
                    // collection context and openai-client's plain `flow { }` rejects the
                    // stream outright ("Flow invariant is violated … use 'flowOn' instead").
                    // The fix is `.flowOn(Dispatchers.IO)` here — which needs an expect/actual,
                    // as Dispatchers.IO is not in the common coroutines API.
                    val stream: Flow<ChatCompletionChunk> = openAI.chatCompletions(request)
                    stream.collect { chunk ->
                        val delta = chunk.choices.firstOrNull()?.delta
                        delta?.reasoningContent?.let { reasoning ->
                            fullReasoning.append(reasoning)
                        }
                        delta?.content?.let { content ->
                            roundContent.append(content)
                            onChunk?.invoke(content)
                        }
                        delta?.toolCalls?.forEach { part ->
                            val slot = pending.getOrPut(part.index) { PartialToolCall() }
                            part.id?.let { slot.id = it.id }
                            part.function?.nameOrNull?.let { slot.name.append(it) }
                            part.function?.argumentsOrNull?.let { slot.arguments.append(it) }
                        }
                    }

                    val requested = pending.entries
                        .sortedBy { it.key }
                        .mapNotNull { (_, slot) -> slot.toToolCall() }

                    // This round produced no tool call, so its text is the answer.
                    if (requested.isEmpty() || callTool == null) {
                        answer = roundContent.toString()
                        break
                    }

                    round++
                    if (round > MAX_TOOL_ROUNDS) {
                        log.w { "Tool loop hit the $MAX_TOOL_ROUNDS-round cap; asking the model to conclude" }
                        conversation += ChatMessage.Assistant(
                            content = roundContent.toString().ifEmpty { null },
                            toolCalls = requested
                        )
                        requested.forEach { call ->
                            conversation += ChatMessage.Tool(
                                content = "Error: tool-call limit reached. Answer with what you already have.",
                                toolCallId = call.id
                            )
                        }
                        activeTools = null
                        onToolCall?.invoke(false)
                        continue
                    }

                    conversation += ChatMessage.Assistant(
                        content = roundContent.toString().ifEmpty { null },
                        toolCalls = requested
                    )

                    for (call in requested) {
                        val name = call.function.nameOrNull.orEmpty()
                        val arguments = call.function.argumentsOrNull.orEmpty()
                        onToolCall?.invoke(true)
                        log.d { "Calling tool '$name'" }
                        val result = callTool(name, arguments)
                        invocations += ToolInvocation(
                            label = name,
                            arguments = arguments,
                            result = result
                        )
                        conversation += ChatMessage.Tool(content = result, toolCallId = call.id)
                    }
                    onToolCall?.invoke(false)
                }

                log.d { "Generation complete, length: ${answer.length}, tool calls: ${invocations.size}" }
                GenerationResult(
                    content = cleanResponse(answer),
                    reasoning = fullReasoning.toString(),
                    toolCalls = invocations
                )
            }
        } catch (e: CancellationException) {
            log.d { "Request cancelled" }
            throw OAIError.TransientError
        } catch (e: Exception) {
            log.e(throwable = e, tag = TAG) { "Generation error: ${e.message}" }
            throw asInvalidResponse(e)
        } finally {
            generationJob = null
        }
    }

    /**
     * The openai client maps every non-2xx response to an [OpenAIAPIException] subclass and
     * often leaves the Kotlin `message` null — the body a proxy sends back (Cloudflare 403,
     * a gateway's own JSON) is usually not OpenAI-shaped, so nothing decodes into
     * `error.message`. The status code always exists, so it is the floor of the reason and
     * always accompanies it; without this a rejected turn reads as "Unknown error" and the
     * user cannot tell a bad token from a bad address.
     */
    private fun asInvalidResponse(e: Exception): OAIError.InvalidResponse {
        if (e is OpenAIAPIException) {
            val detail = e.error.detail
            return OAIError.InvalidResponse(
                reason = detail?.message
                    ?: detail?.code
                    ?: e.message
                    ?: e::class.simpleName
                    ?: "HTTP ${e.statusCode}",
                statusCode = e.statusCode
            )
        }
        return OAIError.InvalidResponse(
            reason = e.message?.takeIf { it.isNotBlank() }
                ?: e::class.simpleName
                ?: "Unknown error"
        )
    }

    @Throws(Exception::class)
    suspend fun fetchModels(endpoint: String, token: String): List<String> {
        return try {
            val (baseURL, proxyPath) = parseBaseUrlAndPath(endpoint) ?: throw OAIError.InvalidURL
            val host = OpenAIHost(baseUrl = buildHostUrl(baseURL, proxyPath))
            val config = OpenAIConfig(
                token = token,
                host = host,
                timeout = Timeout(request = 15.seconds, connect = 15.seconds, socket = 15.seconds),
                // One attempt, no retries. OpenAIConfig defaults to
                // RetryStrategy(maxRetries = 3, base = 2.0, maxDelay = 60.seconds), which
                // openai-client turns into a Ktor HttpRequestRetry — so a wrong address was
                // tried four times with exponential backoff and took 30-60s to surface an
                // error despite the 15s timeout above. The user is sitting in front of this
                // field waiting to be told the address is bad; retrying a typo is pointless.
                retry = RetryStrategy(maxRetries = 0),
                engine = engine
            )
            val openAI = OpenAI(config)
            val models = openAI.models()
            models.map { it.id.id }
        } catch (e: CancellationException) {
            // Never swallow cancellation — let it propagate to the coroutine machinery.
            throw e
        } catch (e: OAIError) {
            log.w { "fetchModels failed: ${e.message}" }
            throw e
        } catch (e: Throwable) {
            // Wrap arbitrary Throwables (network timeouts, JSON parse errors, etc.)
            // as OAIError so the Swift bridge always receives a clean NSError
            // instead of crashing the runtime.
            log.w { "fetchModels failed: ${e::class.simpleName}: ${e.message}" }
            throw OAIError.InvalidResponse(reason = e.message ?: "Network error")
        }
    }

    private fun cleanResponse(content: String): String {
        val unwantedPatterns = listOf(
            "\\[END_TOOL_REQUEST\\]",
            "\\[TOOL_REQUEST\\]",
            "\\[/TOOL_REQUEST\\]",
            "\\[END_RESPONSE\\]",
            "<END_TOOL_REQUEST>",
            "</TOOL_REQUEST>",
            // The RESULT family only started showing up once tool results were
            // actually placed in the conversation: models that were fine-tuned on a
            // textual tool protocol echo the closing marker after summarising a
            // result. Observed from gemma-3n via LM Studio.
            "\\[END_TOOL_RESULT\\]",
            "\\[TOOL_RESULT\\]",
            "\\[/TOOL_RESULT\\]",
            "<END_TOOL_RESULT>",
            "</TOOL_RESULT>"
        )
        var cleaned = content
        for (pattern in unwantedPatterns) {
            cleaned = cleaned.replace(Regex(pattern), "")
        }
        return cleaned.trim()
    }
}

/**
 * Accumulator for one streamed `tool_calls` entry.
 *
 * The API sends a tool call in pieces keyed by `index`: typically the id and function
 * name once, then the arguments as a long run of JSON fragments. Nothing is usable
 * until the stream ends.
 */
private class PartialToolCall {
    var id: String? = null
    val name = StringBuilder()
    val arguments = StringBuilder()

    /** Null when the stream never produced an id or a name — nothing callable. */
    fun toToolCall(): ToolCall.Function? {
        val callId = id ?: return null
        if (name.isEmpty()) return null
        return ToolCall.Function(
            id = ToolId(callId),
            function = FunctionCall(nameOrNull = name.toString(), argumentsOrNull = arguments.toString())
        )
    }
}

internal fun buildHostUrl(baseURL: String, proxyPath: String): String {
    val trimmedPath = proxyPath.trim('/')
    return if (trimmedPath.isEmpty()) {
        "$baseURL/"
    } else {
        "$baseURL/$trimmedPath/"
    }
}

internal fun parseBaseUrlAndPath(urlString: String): Pair<String, String>? {
    return try {
        val url = Url(urlString)
        val baseURL = buildString {
            append(url.protocol.name)
            append("://")
            append(url.host)
            val port = url.port
            if (port != 0 && port != url.protocol.defaultPort) append(":$port")
        }
        val proxyPath = url.encodedPath.trimStart('/')
        Pair(baseURL, proxyPath)
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun encodeBase64(bytes: ByteArray): String = Base64.encode(bytes)
