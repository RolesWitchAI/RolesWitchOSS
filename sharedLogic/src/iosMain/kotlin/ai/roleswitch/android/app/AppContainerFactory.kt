//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.app

import ai.roleswitch.android.data.database.getAppDatabase
import ai.roleswitch.android.service.InferenceEngine
import ai.roleswitch.android.service.LLMService
import ai.roleswitch.android.service.OAIService
import ai.roleswitch.android.service.mcp.McpService
import ai.roleswitch.android.settings.getSettingsManager

private var containerInstance: AppContainer? = null

/**
 * Builds the one [AppContainer] the iOS app uses, or returns the one already built.
 *
 * [onDeviceEngine] is the Swift `MLXInferenceEngine` — Swift can conform to the exported
 * `InferenceEngine` protocol directly, the same way `ClosureFlowCollector` conforms to
 * `FlowCollector`, so no Kotlin-side adapter is needed. Passing null leaves
 * `EffectiveSettings.useOnDevice` inert and every turn falls through to [OAIService].
 *
 * Note the caching: the engine argument is read only on the first call. The Swift caller
 * builds the engine before the container for exactly that reason.
 */
fun createAppContainer(onDeviceEngine: InferenceEngine? = null): AppContainer {
    containerInstance?.let { return it }
    val database = getAppDatabase()
    val settingsManager = getSettingsManager()
    val oaiService = OAIService()
    val mcpService = McpService()
    val llmService = LLMService(
        oaiService = oaiService,
        settingsManager = settingsManager,
        messageDao = database.messageDao(),
        threadDao = database.messageThreadDao(),
        mcpToolDao = database.mcpToolDao(),
        mcpService = mcpService,
        onDeviceEngine = onDeviceEngine
    )
    return AppContainer(
        database = database,
        settingsManager = settingsManager,
        oaiService = oaiService,
        llmService = llmService,
        mcpService = mcpService
    ).also { containerInstance = it }
}
