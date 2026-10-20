//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service.mcp

import ai.roleswitch.android.data.model.McpTool
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.seconds

private const val TAG = "McpService"
private val log = Logger.withTag(TAG)

/**
 * A single callable function discovered on an MCP endpoint, in the shape the chat
 * completions API wants: a name, a description and a JSON-Schema parameter object.
 */
data class McpToolSpec(
    /** Namespaced + sanitized function name, unique across every connected endpoint. */
    val functionName: String,
    val description: String,
    val parametersSchema: JsonObject,
    /** The label of the `mcp_tools` row this came from, for the UI trace. */
    val serverName: String
)

/**
 * A function name resolved back to the endpoint and real tool name that own it.
 * Internal rather than private-in-file: it appears in [McpService.McpSession]'s
 * internal constructor.
 */
internal data class Binding(
    val client: McpClient,
    val toolName: String,
    val serverName: String
)

/**
 * Result summaries are fed straight back into the model's context. An unbounded one
 * can blow the window (or the bill) on a single chatty tool, so every result is cut
 * to this many characters.
 */
private const val MAX_RESULT_CHARS = 8_000

/** Per-tool-call ceiling. Long enough for a slow scrape, short enough not to hang a turn. */
private val CALL_TIMEOUT = 60.seconds

/**
 * Ceiling for "Test connection". Shorter than [CALL_TIMEOUT] because someone is watching
 * a spinner while they check an address they just typed.
 */
private val DISCOVERY_TIMEOUT = 20.seconds

/** Bound on the handshake and on `tools/list` when opening a turn's session. */
private val CONNECT_TIMEOUT = 30.seconds

/**
 * A tool endpoint could not be reached, or did not answer as an MCP server.
 *
 * Carries a message worth showing: the underlying failures are platform-specific
 * (`DarwinHttpRequestException`, `UnknownHostException`, …) and reach Swift as an opaque
 * NSError otherwise.
 */
class McpError(override val message: String) : Exception(message)

/**
 * The on-device MCP client.
 *
 * The whole loop runs locally: we connect to each enabled endpoint over the Streamable
 * HTTP transport, advertise its `tools/list` to the model as OpenAI function tools, and
 * execute `tools/call` here when the model asks for one. Nothing is delegated to the
 * LLM provider.
 *
 * The protocol itself lives in [McpClient] — hand-written on the Ktor the app already
 * ships, rather than pulled in with the official SDK, which would force Ktor 3.5 and
 * break openai-client's streaming.
 */
class McpService(
    /**
     * The HTTP client every MCP session runs on. Injectable so tests can point the
     * whole client at a `MockEngine` speaking canned JSON-RPC; the default is the
     * production client (connect timeout only — per-request ceilings are applied by
     * [McpClient]).
     */
    private val http: HttpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 30.seconds.inWholeMilliseconds
        }
    }
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun connect(url: String, token: String): McpClient =
        McpClient(http, url, token, json).apply { initialize(CONNECT_TIMEOUT) }

    /**
     * Connects to [url] and returns the names of the tools it advertises. Used by the
     * "Test connection" action in the tool editor; the connection is closed again before
     * returning.
     *
     * Always fails as an [McpError]. `@Throws` and the catch-all are both load-bearing on
     * iOS: an exception that is not declared here does not reach Swift as an NSError, it
     * terminates the process — a typo'd host raising `DarwinHttpRequestException` from
     * inside the transport crashed the app. Same reasoning as `OAIService.fetchModels`.
     */
    @Throws(Exception::class)
    suspend fun discoverToolNames(url: String, token: String): List<String> {
        return try {
            // The client's connect timeout only covers establishing the socket. A host
            // that accepts the connection and then never speaks MCP — any wrong-but-live
            // address — would otherwise leave the caller spinning forever, so bound the
            // whole handshake-plus-list exchange.
            withTimeout(DISCOVERY_TIMEOUT) {
                val client = connect(url, token)
                try {
                    client.listTools(DISCOVERY_TIMEOUT).map { it.name }
                } finally {
                    // close() suspends, and on the timeout path this scope is already
                    // cancelled, so an unprotected call would abort before doing anything.
                    withContext(NonCancellable) { runCatching { client.close() } }
                }
            }
        } catch (e: TimeoutCancellationException) {
            log.w { "MCP discovery timed out for $url" }
            throw McpError("No response after ${DISCOVERY_TIMEOUT.inWholeSeconds}s. Check the address.")
        } catch (e: CancellationException) {
            // A real cancellation (the user left the screen) must stay cancellation.
            throw e
        } catch (e: Throwable) {
            log.w { "MCP discovery failed for $url: ${e::class.simpleName}: ${e.message}" }
            throw McpError(e.message ?: e::class.simpleName ?: "Could not connect")
        }
    }

    /**
     * Connects to every entry in [tools] and returns a session holding the union of their
     * callable functions.
     *
     * An endpoint that fails to connect or to list its tools is logged and skipped rather
     * than propagated: one unreachable server must not cost the user their whole turn.
     * The caller owns the returned session and must [McpSession.close] it.
     */
    suspend fun openSession(tools: List<McpTool>): McpSession {
        val bindings = mutableMapOf<String, Binding>()
        val specs = mutableListOf<McpToolSpec>()

        for (tool in tools) {
            val client = try {
                connect(tool.url, tool.token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w { "MCP connect failed for '${tool.name}' (${tool.url}): ${e.message}" }
                continue
            }

            val discovered = try {
                client.listTools(CONNECT_TIMEOUT)
            } catch (e: CancellationException) {
                runCatching { client.close() }
                throw e
            } catch (e: Throwable) {
                log.w { "MCP tools/list failed for '${tool.name}': ${e.message}" }
                runCatching { client.close() }
                continue
            }

            for (t in discovered) {
                val functionName = uniqueFunctionName(tool.name, t.name, bindings.keys)
                bindings[functionName] = Binding(client, t.name, tool.name)
                specs += McpToolSpec(
                    functionName = functionName,
                    description = t.description.orEmpty(),
                    parametersSchema = t.inputSchema.toParametersObject(),
                    serverName = tool.name
                )
            }
            log.d { "MCP '${tool.name}' exposed ${discovered.size} tool(s)" }
        }

        return McpSession(bindings, specs, json)
    }

    /**
     * Trims a tool's raw JSON Schema down to what a function's `parameters` may contain.
     *
     * `$schema` is dropped: it carries no information the model needs, and several
     * OpenAI-compatible servers reject unknown top-level keys in a function's parameters.
     * A missing `type` is filled in, because a function schema without one is invalid and
     * some servers hard-fail on that.
     */
    private fun JsonObject.toParametersObject(): JsonObject {
        val filtered = filterKeys { it != "\$schema" }
        return if (filtered.containsKey("type")) {
            JsonObject(filtered)
        } else {
            JsonObject(filtered + ("type" to JsonPrimitive("object")))
        }
    }

    /**
     * Builds an OpenAI-legal function name for [toolName] on server [serverName].
     *
     * Names must match `[a-zA-Z0-9_-]{1,64}`, and two servers may well both expose a
     * `search`. Namespacing on the server label keeps them apart; the collision counter
     * covers labels that sanitize down to the same string.
     */
    private fun uniqueFunctionName(serverName: String, toolName: String, taken: Set<String>): String {
        val prefix = serverName.sanitizeForFunctionName().take(24).ifEmpty { "mcp" }
        val suffix = toolName.sanitizeForFunctionName().ifEmpty { "tool" }
        val base = "${prefix}__$suffix".take(64)
        if (base !in taken) return base
        var i = 2
        while (true) {
            val candidate = base.take(64 - "_$i".length) + "_$i"
            if (candidate !in taken) return candidate
            i++
        }
    }

    private fun String.sanitizeForFunctionName(): String =
        map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .trim('_')

    /** Releases the shared HTTP client. Only for a full teardown; sessions close themselves. */
    fun shutdown() {
        runCatching { http.close() }
    }

    /**
     * The tools available for one LLM turn, plus the connections backing them.
     *
     * Scoped to a turn rather than kept alive for the process: MCP sessions are stateful
     * and an idle one can be reaped by the server, which would surface as a failure in the
     * middle of a user's request instead of at connect time.
     */
    class McpSession internal constructor(
        private val bindings: Map<String, Binding>,
        val specs: List<McpToolSpec>,
        private val json: Json
    ) {
        val isEmpty: Boolean get() = specs.isEmpty()

        /** Human-readable server + tool for [functionName], used in the trace. */
        fun describe(functionName: String): String =
            bindings[functionName]?.let { "${it.serverName}/${it.toolName}" } ?: functionName

        /**
         * Executes [functionName] with the model-generated [argumentsJson].
         *
         * Never throws: a failure is returned as text so the model can see what went
         * wrong and recover (retry with different arguments, or explain it to the user).
         * That mirrors MCP's own convention of reporting tool failures as results with
         * `isError`, not as protocol errors.
         */
        suspend fun call(functionName: String, argumentsJson: String): String {
            val binding = bindings[functionName]
                ?: return "Error: unknown tool '$functionName'."

            val arguments = try {
                if (argumentsJson.isBlank()) buildJsonObject { }
                else json.parseToJsonElement(argumentsJson).jsonObject
            } catch (e: Throwable) {
                return "Error: arguments were not a JSON object (${e.message})."
            }

            return try {
                val result = binding.client.callTool(binding.toolName, arguments, CALL_TIMEOUT)
                val text = result.content
                    .filter { it["type"]?.stringOrNull() == "text" }
                    .mapNotNull { it["text"]?.stringOrNull() }
                    .joinToString("\n")
                    .ifBlank {
                        // Non-text blocks (images, resources) have no textual form we can
                        // hand back through a chat message; fall back to the structured
                        // payload when the server sent one.
                        result.structuredContent?.toString() ?: "(tool returned no text content)"
                    }
                val body = text.truncateForModel()
                if (result.isError) "Error: $body" else body
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w { "MCP tools/call '$functionName' failed: ${e.message}" }
                "Error: tool call failed (${e.message ?: e::class.simpleName})."
            }
        }

        suspend fun close() {
            bindings.values.map { it.client }.distinct().forEach { runCatching { it.close() } }
        }

        private fun String.truncateForModel(): String =
            if (length <= MAX_RESULT_CHARS) this
            else take(MAX_RESULT_CHARS) + "\n…[truncated, ${length - MAX_RESULT_CHARS} more characters]"
    }
}

/**
 * The value of a JSON string field, or null for anything else.
 *
 * `isString` is what keeps a JSON `null` (also a `JsonPrimitive`) or a number from being
 * read back as the text `"null"` / `"42"` and handed to the model as a tool result.
 */
private fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
