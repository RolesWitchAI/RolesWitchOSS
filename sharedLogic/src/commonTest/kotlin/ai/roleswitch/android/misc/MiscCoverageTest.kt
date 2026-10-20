//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.misc

import ai.roleswitch.android.data.model.DocumentAttachment
import ai.roleswitch.android.data.model.McpTool
import ai.roleswitch.android.data.model.Role
import ai.roleswitch.android.data.seed.RoleSeedData
import ai.roleswitch.android.data.seed.RoleTranslation
import ai.roleswitch.android.service.CompletedResponse
import ai.roleswitch.android.service.GenerationResult
import ai.roleswitch.android.service.OAIService
import ai.roleswitch.android.service.OAIError
import ai.roleswitch.android.service.ToolInvocation
import ai.roleswitch.android.service.decodeCustomLitertModels
import ai.roleswitch.android.service.mcp.McpService
import ai.roleswitch.android.service.remoteEngineSettings
import ai.roleswitch.android.service.respondSse
import ai.roleswitch.android.settings.SettingsManager
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Small surfaces the bigger suites never touch: error-message formatting, DTO
 * defaults and generated members, the no-arg service constructors, and the settings
 * companion tables the platform UIs read.
 */
class MiscCoverageTest {

    @Test
    fun invalidResponseMessagesCarryTheStatusWhenThereIsOne() {
        assertEquals("LLM Error (Status 500): model overloaded", OAIError.InvalidResponse("model overloaded", 500).message)
        assertEquals("LLM Error: something else", OAIError.InvalidResponse("something else").message)
    }

    @Test
    fun transientErrorsAreSilentAndHardOnesAreNot() {
        assertTrue(OAIError.TransientError.isSilent)
        assertTrue(!OAIError.InvalidResponse("x").isSilent)
        assertTrue(!OAIError.InvalidURL.isSilent)
    }

    @Test
    fun toolInvocationsAndGenerationResultsRenderAndCopy() {
        val invocation = ToolInvocation(label = "srv__fn", arguments = "{}", result = "42")
        assertTrue(invocation.toString().contains("srv__fn"))
        assertEquals(invocation, invocation.copy())

        val result = GenerationResult(content = "a", reasoning = "r", toolCalls = listOf(invocation))
        assertEquals(result, result.copy(content = "a"))
        assertTrue(result.toString().contains("content=a"))
        // Destructuring (the generated componentN members).
        val (label, arguments, toolResult) = invocation
        assertEquals("srv__fn", label)
        assertEquals("{}", arguments)
        assertEquals("42", toolResult)
        val (content, reasoning, calls) = result
        assertEquals("a", content)
        assertEquals("r", reasoning)
        assertEquals(listOf(invocation), calls)
    }

    @Test
    fun completedResponsesRender() {
        val completed = CompletedResponse(threadId = "t", content = "c")
        assertTrue(completed.toString().contains("threadId=t"))
        assertEquals(completed, completed.copy())
        val (threadId, content) = completed
        assertEquals("t", threadId)
        assertEquals("c", content)
    }

    @Test
    fun mcpToolsAndRolesCopyAndRender() {
        val tool = McpTool(name = "n", url = "u", token = "t", createdAt = 1L, updatedAt = 2L)
        assertEquals(tool.copy(name = "n2").name, "n2")
        assertTrue(tool.toString().contains("url=u"))
        val (id, name, url, token, createdAt, updatedAt) = tool
        assertEquals("u", url)
        assertEquals(1L, createdAt)
        assertEquals(2L, updatedAt)

        val role = Role(id = "r", name = "n", prompt = "p", summary = "s", greeting = "g", createdAt = 1L)
        assertEquals("g2", role.copy(greeting = "g2").greeting)
        assertTrue(role.toString().contains("id=r"))
        val (roleId, roleName, prompt, summary, greeting) = role
        assertEquals("r", roleId)
        assertEquals("g", greeting)
    }

    @Test
    fun documentAttachmentsRenderAndCopy() {
        val doc = DocumentAttachment(fileName = "a.pdf", sizeBytes = 1, pageCount = 2, extractedText = "t")
        assertEquals("b.pdf", doc.copy(fileName = "b.pdf").fileName)
        assertTrue(doc.toString().contains("a.pdf"))
        val (fileName, mimeType, sizeBytes, pageCount, extractedText) = doc
        assertEquals("a.pdf", fileName)
        assertEquals("application/pdf", mimeType)
        assertEquals(1L, sizeBytes)
        assertEquals(2, pageCount)
        assertEquals("t", extractedText)
    }

    @Test
    fun litertModelsDestructure() {
        val model = ai.roleswitch.android.service.testLitertModel(id = "custom:o/m")
        val (id, displayName, repoId, fileName) = model
        assertEquals("custom:o/m", id)
        assertEquals("m", displayName)
        assertEquals("o/m", repoId)
        assertEquals("model.litertlm", fileName)
    }

    @Test
    fun dataClassesHashAndEqualSensibly() {
        // The generated equals/hashCode of the wire and row types: equal copies must
        // agree, and a changed field must disagree.
        val tool = McpTool(name = "n", url = "u", token = "t", createdAt = 1L, updatedAt = 1L)
        assertEquals(tool.hashCode(), tool.copy().hashCode())
        assertNotEquals(tool.hashCode(), tool.copy(url = "other").hashCode())
        assertEquals(tool, tool.copy())
        assertNotEquals(tool, tool.copy(url = "other"))
        assertTrue(tool.equals(tool))
        assertTrue(!tool.equals(null))

        val completed = CompletedResponse(threadId = "t", content = "c")
        assertEquals(completed.hashCode(), completed.copy().hashCode())
        assertNotEquals(completed.hashCode(), completed.copy(content = "x").hashCode())

        val doc = DocumentAttachment(fileName = "a.pdf", sizeBytes = 1)
        assertEquals(doc.hashCode(), doc.copy().hashCode())
        assertNotEquals(doc.hashCode(), doc.copy(sizeBytes = 2).hashCode())

        val role = Role(id = "r", name = "n", prompt = "p", summary = "s", greeting = "g", createdAt = 1L)
        assertEquals(role.hashCode(), role.copy().hashCode())
        assertNotEquals(role.hashCode(), role.copy(prompt = "x").hashCode())

        val model = ai.roleswitch.android.service.testLitertModel(id = "custom:o/m")
        assertEquals(model.hashCode(), model.copy().hashCode())
        assertNotEquals(model.hashCode(), model.copy(fileName = "other.litertlm").hashCode())

        val invocation = ToolInvocation("l", "a", "r")
        assertEquals(invocation.hashCode(), invocation.copy().hashCode())
        assertNotEquals(invocation.hashCode(), invocation.copy(label = "x").hashCode())

        val result = GenerationResult("c", "r")
        assertEquals(result.hashCode(), result.copy().hashCode())
        assertNotEquals(result.hashCode(), result.copy(reasoning = "x").hashCode())

        val invalid = OAIError.InvalidResponse("r", 500)
        assertEquals(invalid, invalid.copy(reason = "r"))
        assertNotEquals(invalid, invalid.copy(statusCode = 404))
    }

    @Test
    fun aRoleTranslationWithoutAGreetingDefaultsToSilence() {
        val translation = RoleTranslation(name = "n", summary = "s", prompt = "p")
        assertEquals("", translation.greeting)
        assertEquals("g", translation.copy(greeting = "g").greeting)
    }

    @Test
    fun theSeedCompanionExposesBothRoleSurfaces() {
        assertEquals(1, RoleSeedData.seedRoles.size)
        // The translation lookup honours locale and falls back to English.
        val en = RoleSeedData.getTranslation(RoleSeedData.seedRoles.first(), "de")
        assertEquals(RoleSeedData.seedRoles.first().translations["en"], en)
    }

    @Test
    fun decodingASparseCustomModelFillsTheDocumentedDefaults() {
        val decoded = decodeCustomLitertModels(
            """[{"id":"custom:o/m","displayName":"m","repoId":"o/m","fileName":"m.litertlm","commitHash":"c"}]"""
        ).single()
        assertEquals(0L, decoded.sizeBytes)
        assertEquals(8, decoded.minDeviceMemoryGb)
        assertEquals(false, decoded.supportsImage)
        assertEquals(true, decoded.supportsThinking)
        assertEquals(false, decoded.gated)
        assertEquals("", decoded.licenseName)
        assertEquals("", decoded.licenseUrl)
    }

    @Test
    fun theSettingsCompanionTablesAreWhatTheUIsRead() {
        assertEquals("Русский", SettingsManager.defaultLanguages["ru"])
        assertEquals("ru-RU", SettingsManager.localeForSTT["ru"])
        assertEquals(6, SettingsManager.defaultLanguages.size)
        assertEquals(SettingsManager.defaultLanguages.keys, SettingsManager.localeForSTT.keys)
    }

    @Test
    fun theAcceptedLicenseListIsObservable() {
        val sm = SettingsManager(ai.roleswitch.android.settings.InMemoryPreferenceDataStore())
        sm.acceptLicense("gemma-4-E2B-it")
        assertEquals("gemma-4-E2B-it", sm.litertAcceptedLicenses.value)
    }

    @Test
    fun servicesConstructWithNoArguments() {
        // The production constructors: default engine, default client.
        ai.roleswitch.android.service.ExternalTtsClient()
        McpService()
    }

    @Test
    fun generateHonoursItsDefaultArguments() = runTest {
        // toolSpecs / onToolCall / callTool omitted — the interface's default-value
        // bridge is the production call shape for a plain turn.
        val oai = OAIService(
            MockEngine { _ ->
                respondSse(ai.roleswitch.android.service.sseBody(ai.roleswitch.android.service.contentDelta("ok")))
            }
        )
        val result = oai.generate(
            messages = listOf(ai.roleswitch.android.service.LLMMessage(ai.roleswitch.android.service.LLMMessage.LLMRole.USER, "hi")),
            systemPrompt = "sys",
            settings = remoteEngineSettings(),
            onChunk = null
        )
        assertEquals("ok", result.content)

        // And through the interface type, which is how LLMService holds it.
        val engine: ai.roleswitch.android.service.InferenceEngine = oai
        val viaInterface = engine.generate(
            messages = listOf(ai.roleswitch.android.service.LLMMessage(ai.roleswitch.android.service.LLMMessage.LLMRole.USER, "hi")),
            systemPrompt = "sys",
            settings = remoteEngineSettings(),
            onChunk = null
        )
        assertEquals("ok", viaInterface.content)
    }
}
