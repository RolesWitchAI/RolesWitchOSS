//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service.mcp

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration

private val log = Logger.withTag("McpClient")

/** The MCP revision we advertise. Servers echo their own; we keep whatever they answer. */
private const val PROTOCOL_VERSION = "2025-06-18"

/** Header carrying the server-assigned session, per the Streamable HTTP transport. */
private const val HEADER_SESSION_ID = "Mcp-Session-Id"
private const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"

/** Guards against a `tools/list` that paginates forever. */
private const val MAX_LIST_PAGES = 20

/** How much of an error body is worth putting in front of a user. */
private const val ERROR_BODY_LIMIT = 200

/** One tool as advertised by a server's `tools/list`. */
internal data class RemoteTool(
    val name: String,
    val description: String?,
    /** Raw JSON-Schema object straight off the wire — re-advertised to the model as-is. */
    val inputSchema: JsonObject
)

/** The outcome of one `tools/call`. [isError] is the server's own flag, not a transport failure. */
internal data class CallResult(
    val content: List<JsonObject>,
    val structuredContent: JsonObject?,
    val isError: Boolean
)

/**
 * A minimal MCP client speaking the Streamable HTTP transport (protocol [PROTOCOL_VERSION]).
 *
 * Deliberately hand-written rather than taken from `io.modelcontextprotocol:kotlin-sdk-client`:
 * that SDK forces Ktor to 3.5.x and coroutines to 1.11, and Ktor ≥3.5 dispatches streamed
 * responses on `Dispatchers.IO`, which breaks openai-client's `flow { }`-based chat stream.
 * We use four methods of the protocol, so the client is cheaper than the upgrade.
 *
 * What is implemented, and nothing else:
 * - `initialize` + the `notifications/initialized` acknowledgement,
 * - `tools/list` (following `nextCursor`),
 * - `tools/call`,
 * - session teardown via HTTP `DELETE`.
 *
 * Not implemented, because the tool loop has no use for them: the optional server→client
 * `GET` listening stream, resources, prompts, sampling, and the deprecated 2024-11-05
 * HTTP+SSE transport. A server offering only the old transport fails at [initialize].
 *
 * One instance is one session; instances are not reusable after [close].
 */
internal class McpClient(
    private val http: HttpClient,
    private val url: String,
    private val token: String,
    private val json: Json
) {
    /**
     * Serializes requests and the id counter.
     *
     * Each call is an independent POST that reads its own reply, so the protocol does not
     * require this — but it keeps `sessionId` / `protocolVersion` writes ordered against
     * concurrent reads, and the tool loop is sequential anyway.
     */
    private val mutex = Mutex()
    private var nextId = 1

    /** Assigned by the server on `initialize`; absent for stateless servers. */
    private var sessionId: String? = null

    /** The revision the server answered with. Echoed back on every later request. */
    private var protocolVersion: String = PROTOCOL_VERSION

    /**
     * Set once `initialize` has answered. Gates the `MCP-Protocol-Version` header: the spec
     * has the server *infer* the version for the initial request and the client echo the
     * negotiated one only afterwards, so sending it too early can be rejected outright.
     */
    private var handshakeDone = false

    /**
     * Performs the MCP handshake. Must succeed before any other call.
     *
     * Failures surface as [McpError] with a message worth showing: this is what the
     * "Test connection" button reports when an address is wrong.
     */
    suspend fun initialize(timeout: Duration) {
        val result = request(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "RolesWitch")
                    put("version", "1.0")
                }
            },
            timeout = timeout
        )

        (result["protocolVersion"] as? JsonPrimitive)?.contentOrNullSafe()?.let {
            protocolVersion = it
        }
        handshakeDone = true
        log.d { "MCP session at $url: protocol $protocolVersion, session ${sessionId ?: "(stateless)"}" }

        // Required by the spec before normal operation. It is a notification, so there is
        // no reply to wait for and a server that ignores it costs us nothing.
        notify("notifications/initialized", timeout)
    }

    /**
     * Lists every tool the server advertises, following `nextCursor` until exhausted.
     *
     * Tools whose entry is missing a name or an object `inputSchema` are skipped rather
     * than failing the whole listing — one malformed entry must not cost the user the
     * other tools on that server.
     */
    suspend fun listTools(timeout: Duration): List<RemoteTool> {
        val tools = mutableListOf<RemoteTool>()
        var cursor: String? = null
        var page = 0

        do {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = request("tools/list", params, timeout)

            (result["tools"] as? JsonArray)?.forEach { entry ->
                val tool = entry as? JsonObject ?: return@forEach
                val name = tool["name"]?.jsonPrimitiveOrNull()?.contentOrNullSafe()
                if (name.isNullOrBlank()) {
                    log.w { "Skipping a nameless tool from $url" }
                    return@forEach
                }
                tools += RemoteTool(
                    name = name,
                    description = tool["description"]?.jsonPrimitiveOrNull()?.contentOrNullSafe(),
                    inputSchema = tool["inputSchema"] as? JsonObject ?: EMPTY_OBJECT_SCHEMA
                )
            }

            cursor = result["nextCursor"]?.jsonPrimitiveOrNull()?.contentOrNullSafe()
            page++
        } while (cursor != null && page < MAX_LIST_PAGES)

        if (cursor != null) log.w { "tools/list from $url still paginating after $MAX_LIST_PAGES pages; stopping" }
        return tools
    }

    /** Invokes [name] with [arguments]. Protocol and transport failures throw [McpError]. */
    suspend fun callTool(name: String, arguments: JsonObject, timeout: Duration): CallResult {
        val result = request(
            method = "tools/call",
            params = buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
            timeout = timeout
        )
        return CallResult(
            content = (result["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject },
            structuredContent = result["structuredContent"] as? JsonObject,
            isError = (result["isError"] as? JsonPrimitive)?.booleanOrNullSafe() == true
        )
    }

    /**
     * Best-effort session teardown: a `DELETE` tells the server it may drop the session
     * now rather than waiting for its idle reaper.
     *
     * Never throws — this runs on the way out of a turn, and a server that refuses
     * (405 is explicitly allowed by the spec) or has already forgotten us changes nothing.
     */
    suspend fun close() {
        val id = sessionId ?: return
        runCatching {
            http.delete(url) {
                header(HEADER_SESSION_ID, id)
                header(HEADER_PROTOCOL_VERSION, protocolVersion)
                authorize()
            }
        }.onFailure { log.d { "MCP session close failed for $url: ${it.message}" } }
    }

    // ---- transport -------------------------------------------------------------------

    /**
     * Sends a JSON-RPC request and returns its `result` object.
     *
     * @throws McpError on a transport failure, a JSON-RPC `error`, or a reply that never
     *   carries the id we asked about.
     */
    private suspend fun request(method: String, params: JsonObject, timeout: Duration): JsonObject =
        mutex.withLock {
            val id = nextId++
            val envelope = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }

            val message = post(envelope, timeout, expectReplyTo = id)
                ?: throw McpError("The server closed the connection without answering '$method'.")

            message["error"]?.jsonObject?.let { error ->
                val code = error["code"]?.jsonPrimitiveOrNull()?.content.orEmpty()
                val text = error["message"]?.jsonPrimitiveOrNull()?.contentOrNullSafe()
                    ?: "the server reported an error"
                throw McpError(if (code.isEmpty()) text else "$text (code $code)")
            }

            message["result"] as? JsonObject
                ?: throw McpError("The server's reply to '$method' had no result object.")
        }

    /** Fire-and-forget JSON-RPC notification: no id, so no reply to correlate. */
    private suspend fun notify(method: String, timeout: Duration) {
        mutex.withLock {
            val envelope = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
            }
            post(envelope, timeout, expectReplyTo = null)
        }
    }

    /**
     * POSTs one JSON-RPC envelope and, when [expectReplyTo] is set, returns the message
     * carrying that id.
     *
     * The spec lets a server answer a POST either with a single JSON document or with an
     * SSE stream, and we have to accept both from the same request. Null is returned when
     * there was nothing to wait for (a notification, answered with 202) or when the stream
     * ended without our id.
     */
    private suspend fun post(
        envelope: JsonObject,
        timeout: Duration,
        expectReplyTo: Int?
    ): JsonObject? = http.preparePost(url) {
        contentType(ContentType.Application.Json)
        // Both are required: the server picks the response shape, and refusing one of
        // them makes spec-compliant servers reject the request outright.
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        sessionId?.let { header(HEADER_SESSION_ID, it) }
        if (handshakeDone) header(HEADER_PROTOCOL_VERSION, protocolVersion)
        authorize()
        timeout { requestTimeoutMillis = timeout.inWholeMilliseconds }
        setBody(envelope.toString())
    }.execute { response ->
        // Only `initialize` issues one, but reading it on every response costs nothing and
        // keeps us correct if a server (re)assigns one later.
        response.headers[HEADER_SESSION_ID]?.let { sessionId = it }

        if (!response.status.isSuccess()) throw response.toError()
        if (expectReplyTo == null || response.status == HttpStatusCode.Accepted) return@execute null

        if (response.isEventStream()) {
            response.readEventStreamFor(expectReplyTo)
        } else {
            response.bodyAsText().parseJsonRpc().firstWithId(expectReplyTo)
        }
    }

    /**
     * Reads an SSE body frame by frame and stops at the first message with [id].
     *
     * Returning early matters: the server is allowed to keep the stream open afterwards,
     * and draining it to the end would stall the turn until the request timeout. Anything
     * else on the stream (server→client requests, `ping`, notifications) is ignored — we
     * do not open the optional listening channel, so there is nowhere to answer it.
     */
    private suspend fun HttpResponse.readEventStreamFor(id: Int): JsonObject? {
        val channel = bodyAsChannel()
        val data = StringBuilder()

        while (true) {
            val line = channel.readUTF8Line() ?: break
            when {
                // Blank line terminates an event: dispatch whatever data accumulated.
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        data.toString().parseJsonRpc().firstWithId(id)?.let { return it }
                        data.clear()
                    }
                }
                // Comment / keep-alive.
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
                // `event:`, `id:`, `retry:` — nothing here needs them.
                else -> Unit
            }
        }

        // A stream that ends mid-event still carries a usable final frame.
        if (data.isNotEmpty()) return data.toString().parseJsonRpc().firstWithId(id)
        return null
    }

    /** Parses one JSON-RPC payload. A batch arrives as an array; a single message as an object. */
    private fun String.parseJsonRpc(): List<JsonObject> {
        if (isBlank()) return emptyList()
        val element = try {
            json.parseToJsonElement(this)
        } catch (e: Exception) {
            throw McpError("The server did not answer with JSON (${e.message}).")
        }
        return when (element) {
            is JsonObject -> listOf(element)
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
    }

    private fun List<JsonObject>.firstWithId(id: Int): JsonObject? =
        firstOrNull { it["id"]?.jsonPrimitiveOrNull()?.content == id.toString() }

    private fun HttpResponse.isEventStream(): Boolean =
        headers[HttpHeaders.ContentType]?.startsWith(ContentType.Text.EventStream.toString()) == true

    /**
     * Turns a failed response into an [McpError] worth showing.
     *
     * A 404 with a session in hand is called out separately: per the transport spec it means
     * the server dropped the session, which is a retry, not a bad address.
     */
    private suspend fun HttpResponse.toError(): McpError {
        val body = runCatching { bodyAsText() }.getOrDefault("").trim().take(ERROR_BODY_LIMIT)
        if (status == HttpStatusCode.NotFound && sessionId != null) {
            return McpError("The tool session expired (HTTP 404). Try again.")
        }
        val detail = if (body.isEmpty()) status.description else body
        return McpError("HTTP ${status.value}: $detail")
    }

    private fun HttpRequestBuilder.authorize() {
        if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
    }

    private companion object {
        /** Stand-in for a tool that advertises no schema; a function still needs a valid one. */
        val EMPTY_OBJECT_SCHEMA = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }
    }
}

/**
 * `JsonPrimitive.content` on a JSON null returns the string "null", and `.jsonPrimitive`
 * throws on a non-primitive. These keep a hostile payload from reading as real data.
 */
private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

private fun JsonPrimitive.booleanOrNullSafe(): Boolean? =
    if (this is JsonNull) null else content.toBooleanStrictOrNull()
