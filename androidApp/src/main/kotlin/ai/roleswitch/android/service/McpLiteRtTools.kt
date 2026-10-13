//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import ai.roleswitch.android.service.mcp.McpToolSpec
import android.util.Log
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "McpLiteRtTools"

/**
 * Exposes every configured MCP endpoint as its **own** declarative LiteRT-LM tool.
 *
 * LiteRT-LM can declare functions without `@Tool` reflection: an [OpenApiTool] is a
 * JSON-Schema description plus a plain `execute` callback, wrapped in a [ToolProvider]
 * by `tool(...)`. The runtime's automatic tool calling stops answering after the first
 * function call and keeps the loop inside the runtime, so the model sees each MCP
 * function under its real name with its real schema — the one `run_mcp_tool`
 * meta-dispatcher and the catalogue it used to need in the system prompt are both gone.
 */
object McpLiteRtTools {

    /** One [ToolProvider] per MCP spec, in the same order. Empty list when there are none. */
    fun providers(
        specs: List<McpToolSpec>,
        onToolCall: ((Boolean) -> Unit)?,
        onInvocation: (ToolInvocation) -> Unit,
        callTool: suspend (String, String) -> String
    ): List<ToolProvider> = specs.map { spec ->
        tool(
            object : OpenApiTool {
                override fun getToolDescriptionJsonString(): String =
                    buildJsonObject {
                        put("name", spec.functionName)
                        put("description", spec.description)
                        put("parameters", spec.parametersSchema)
                    }.toString()

                // The runtime invokes this synchronously off its own thread, so the
                // suspending MCP call has to be bridged. `McpSession.call` reports
                // failures as text rather than throwing, so there is nothing to catch on
                // the happy path; the result is handed back to the model verbatim, the
                // same way `OAIService` feeds a tool's text back on the remote engine.
                override fun execute(paramsJsonString: String): String {
                    onToolCall?.invoke(true)
                    return try {
                        val result = runBlocking(Dispatchers.IO) { callTool(spec.functionName, paramsJsonString) }
                        onInvocation(
                            ToolInvocation(
                                label = spec.functionName,
                                arguments = paramsJsonString,
                                result = result
                            )
                        )
                        result
                    } catch (e: Exception) {
                        Log.e(TAG, "MCP call failed for ${spec.functionName}", e)
                        val message = e.message ?: "Unknown error"
                        onInvocation(
                            ToolInvocation(
                                label = spec.functionName,
                                arguments = paramsJsonString,
                                result = "Error: $message"
                            )
                        )
                        "Error: tool call failed ($message)"
                    } finally {
                        onToolCall?.invoke(false)
                    }
                }
            }
        )
    }
}