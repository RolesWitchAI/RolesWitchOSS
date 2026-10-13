//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import ai.roleswitch.android.service.mcp.McpToolSpec
import ai.roleswitch.android.settings.EffectiveSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "LiteRtService"

/**
 * Whether this phone can run the on-device engine at all (Android 12+, API 31).
 * Below that, 2018-2020-era hardware cannot be assumed to carry a model, so the engine
 * is unavailable: the settings picker hides the option, and a stored/onboarded
 * on-device choice resolves to the remote endpoint instead of being attempted.
 */
val onDeviceInferenceSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Channel name LiteRT-LM uses for a model's reasoning tokens. */
private const val THOUGHT_CHANNEL = "thought"

/** Extra-context key switching a thinking-capable checkpoint's reasoning on for a turn. */
private const val ENABLE_THINKING_KEY = "enable_thinking"

/**
 * Streamed pieces beginning with this are the model's internal control tokens, not
 * text for the user. The reference gallery filters them the same way.
 */
private const val CONTROL_TOKEN_PREFIX = "<ctrl"

/**
 * Runs a [LitertModel] on the device via LiteRT-LM, as an alternative to [OAIService].
 *
 * Android-only by nature (the runtime ships as an Android AAR), so it lives here rather
 * than in `:sharedLogic`, next to the other on-device service, [VoskService].
 *
 * ### Engine lifetime
 * Loading a multi-gigabyte model takes tens of seconds, so exactly one [Engine] is kept
 * alive across turns, chat screens and calls, and is reused unchanged whenever the next
 * thread asks for the same model — switching between two threads on the same model costs
 * nothing. It is rebuilt only when something it was configured from changes ([EngineKey]).
 *
 * It is unloaded as eagerly as it is loaded, but only ever on something the *user* did:
 * [syncTo] and [releaseIfNotFor] release it on a screen bound to the remote engine or to
 * a different model, [ensureEngine] swaps it when a send asks for another one, and
 * [deleteModel] drops it with its weights. Gigabytes of native memory held for a
 * conversation that will never use them is what gets the process killed in the background.
 *
 * **Nothing unloads it on backgrounding or on a system memory callback.** The app is
 * backgrounded every time the user takes a photo, picks one from the gallery, or answers
 * a permission dialog — all three of them steps *towards* the next turn — and dropping
 * the weights there bought a multi-second reload on return and nothing else. An
 * `onTrimMemory` override used to do exactly that: `TRIM_MEMORY_UI_HIDDEN` is 20, a pure
 * visibility signal delivered on every one of those trips, and it passed the `>=
 * TRIM_MEMORY_RUNNING_LOW` (10) test that was meant to catch real pressure. There is no
 * honest replacement to reach for either — the platform marks every pressure level, and
 * `onLowMemory`, "apps are not notified of this level since API level 34", leaving only
 * visibility. So the callback is gone rather than narrowed, which is also what Google's
 * AI Edge Gallery does with this runtime: it overrides neither, and reclaims memory by
 * letting the process be killed. That is the contract for an allocation this size.
 *
 * ### Conversation lifetime
 * A fresh [Conversation] is created per turn, seeded with the trimmed history that
 * `LLMService.consolidateMessages` produced. LiteRT's conversation is stateful while the
 * rest of the app is not — messages can be edited, deleted, or fall outside the history
 * limit between turns — and rebuilding is the only way the two stay in agreement.
 * Only the conversation is rebuilt; the engine (and its loaded weights) is reused.
 */
class LiteRtService(private val context: Context) : InferenceEngine {

    sealed interface InitState {
        data object Idle : InitState
        data class Initializing(val displayName: String) : InitState
        data class Ready(val displayName: String) : InitState
        data class Failed(val message: String) : InitState
    }

    private val _initState = MutableStateFlow<InitState>(InitState.Idle)
    val initState: StateFlow<InitState> = _initState.asStateFlow()

    /**
     * Everything the loaded engine was built from. A change in any of these means the
     * weights have to be reloaded; anything else (sampler settings, tools, the system
     * prompt) only affects the per-turn conversation and is free to change.
     */
    private data class EngineKey(
        val modelPath: String,
        val accelerator: String,
        val maxTokens: Int,
        val vision: Boolean
    )

    private class Loaded(val key: EngineKey, val engine: Engine)

    private val engineMutex = Mutex()
    private var loaded: Loaded? = null

    /**
     * Number of generations currently using the engine. Guards [release] — see its
     * docs. A counter rather than a flag because chat and a voice call share one
     * service instance.
     */
    private val inFlightTurns = AtomicInteger(0)

    @Volatile
    private var activeConversation: Conversation? = null

    /**
     * Carries [cancel] off the caller's thread. Its own scope rather than the turn's:
     * cancelling has to outlive the coroutine it is cancelling.
     */
    private val cancelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Serialises `cancelProcess()` against `close()` on the same [Conversation].
     *
     * Both are native calls on the same object, and once [cancel] stopped running inline
     * they became concurrent: the tap queues a cancel, the turn unwinds and closes the
     * conversation, and the queued cancel then reaches freed memory. A plain monitor
     * rather than a `Mutex` because one of the two holders — [closeLoadedLocked], via
     * [release] — is not a suspending function.
     */
    private val conversationLock = Any()

    /**
     * Whether the loaded engine actually has a vision tower.
     *
     * Not the same as [LitertModel.supportsImage]: a vision model whose required GPU
     * backend is unavailable is loaded text-only rather than not at all, and sending it
     * an image after that is an error the user never sees the cause of. This is what the
     * turn builder checks — the registry flag is only what the model *could* do.
     */
    private val _visionActive = MutableStateFlow(false)
    val visionActive: StateFlow<Boolean> = _visionActive.asStateFlow()

    // ---------------------------------------------------------------- model files

    fun isDownloaded(model: LitertModel): Boolean = LitertModelStore.isDownloaded(context, model)

    /** Deletes the model and drops the engine if that model was the one loaded. */
    suspend fun deleteModel(model: LitertModel) {
        val path = LitertModelStore.fileFor(context, model).absolutePath
        engineMutex.withLock {
            if (loaded?.key?.modelPath == path) closeLoadedLocked()
        }
        LitertModelStore.delete(context, model)
    }

    // ---------------------------------------------------------------- engine

    /**
     * Brings the loaded engine in line with [settings], which is the *only* entry point
     * screens should use.
     *
     * Loading is eager so the wait happens while the user is reading a thread rather
     * than after they hit send — but the mirror case matters just as much: opening a
     * thread that uses the remote engine, or a different on-device model, **unloads**
     * what is resident. A model is gigabytes of native memory, and holding it for a
     * conversation that will never use it is the difference between the app surviving
     * in the background and being killed.
     *
     * Returns false when there is nothing loaded afterwards.
     */
    suspend fun syncTo(settings: EffectiveSettings): Boolean {
        val model = settings.litertModelSpec
        if (!settings.useOnDevice || model == null) {
            release()
            return false
        }
        if (!isDownloaded(model)) {
            release()
            _initState.value = InitState.Failed("Model not downloaded")
            return false
        }
        // A mismatched model/accelerator is unloaded by ensureEngine itself before the
        // new one is built, so switching models never holds two sets of weights at once.
        return runCatching { ensureEngine(model, settings) }.isSuccess
    }

    /**
     * @param ownTurns how many of [inFlightTurns] belong to this caller — 1 from the
     *   generation path, which claims its turn before resolving the engine, and 0 from
     *   [syncTo], which is only warming up. Anything above that count is another turn
     *   streaming on the engine that is already loaded, and closing it under that turn
     *   is a native use-after-free rather than an exception anyone can catch.
     */
    /** The engine [settings] would need for [model]; the identity a resident engine is matched on. */
    private fun keyFor(model: LitertModel, settings: EffectiveSettings) = EngineKey(
        modelPath = LitertModelStore.fileFor(context, model).absolutePath,
        accelerator = settings.litertAccelerator.ifBlank { model.defaultAccelerator },
        maxTokens = settings.litertMaxTokens.toIntOrNull() ?: model.defaultMaxTokens,
        // The user's toggle, not the registry flag: a checkpoint added by URL has no
        // known answer, and the only way to find out is to try. A tower that fails to
        // build drops the attempt to text-only rather than failing the load.
        vision = settings.visionAvailable
    )

    /**
     * Frees the resident model unless it is the one [settings] would use.
     *
     * The unload half of [syncTo], without the load. A chat screen calls this on the way
     * in: opening a thread is not a decision to run anything — the user may be flicking
     * through conversations to read them — so nothing is pulled into memory until they
     * actually send. What *is* worth doing eagerly is letting go of a model this thread
     * cannot use, rather than holding gigabytes for a conversation that will never
     * touch them.
     */
    fun releaseIfNotFor(settings: EffectiveSettings) {
        val model = settings.litertModelSpec
        if (settings.useOnDevice && model != null && loaded?.key == keyFor(model, settings)) return
        release()
    }

    private suspend fun ensureEngine(
        model: LitertModel,
        settings: EffectiveSettings,
        ownTurns: Int = 0
    ): Engine {
        val file = LitertModelStore.fileFor(context, model)
        val accelerator = settings.litertAccelerator.ifBlank { model.defaultAccelerator }
        val key = keyFor(model, settings)
        val maxTokens = key.maxTokens

        return engineMutex.withLock {
            loaded?.let { if (it.key == key) return@withLock it.engine }
            // Swapping engines means freeing the resident one first. Safe only when no
            // other turn is using it.
            if (inFlightTurns.get() > ownTurns) {
                loaded?.let { current ->
                    // Warming up: keep what is loaded and let the next sync try again.
                    if (ownTurns == 0) return@withLock current.engine
                    throw LiteRtException("The model is busy answering — try again in a moment.")
                }
            }
            closeLoadedLocked()

            // Re-check on every load: a storage cleaner or a sideways `adb` delete can
            // remove the file after it was downloaded, and handing a missing path to the
            // native runtime crashes the process instead of raising an error.
            if (!file.isFile || file.length() != model.sizeBytes) {
                _initState.value = InitState.Failed("Model file is missing or incomplete")
                throw LiteRtException("Model file is missing or incomplete — download it again.")
            }

            _initState.value = InitState.Initializing(model.displayName)
            // Uncancellable: the native initialize() cannot be interrupted, and abandoning
            // the coroutine mid-load would leak the partially-built engine.
            withContext(Dispatchers.IO + NonCancellable) {
                // Each attempt is a (text backend, vision enabled) pair, tried in order.
                //
                // The vision backend is **not** the text backend: a checkpoint declares
                // which accelerators its vision tower accepts, and the Gemma family
                // accepts only GPU. Deriving it from the text backend — as this used to —
                // hands CPU vision to a GPU-only model, which refuses to load with
                // "Vision backend constraint mismatch".
                //
                // So the last resort for a vision model is to drop vision rather than to
                // move it to CPU: on a device whose GPU LiteRT cannot use, a text-only
                // Gemma is worth more than no Gemma.
                val textAttempts = if (accelerator.equals("cpu", ignoreCase = true)) {
                    listOf("cpu")
                } else {
                    // GPU first, CPU as a fallback. LiteRT's GPU path needs OpenCL; where
                    // it is unavailable the runtime drops to an OpenGL backend that is not
                    // implemented and initialize() fails outright. That is a property of
                    // the device, not a mistake the user made, so it should degrade to a
                    // slower answer rather than to no assistant at all.
                    listOf("gpu", "cpu")
                }
                val wantsVision = key.vision
                val attempts = buildList {
                    textAttempts.forEach { add(it to wantsVision) }
                    if (wantsVision) add(textAttempts.last() to false)
                }

                // The *first* failure is the one worth reporting: it is the configuration
                // the user actually asked for. Reporting the last one meant every failure
                // read as the final fallback's error, so changing the accelerator appeared
                // to do nothing at all.
                var firstError: Throwable? = null
                for ((attempt, withVision) in attempts) {
                    try {
                        val engine = Engine(
                            EngineConfig(
                                modelPath = file.absolutePath,
                                backend = backendFor(attempt),
                                // Non-null enables the vision tower, on the accelerator
                                // the checkpoint itself demands.
                                visionBackend = if (withVision) {
                                    backendFor(model.visionAccelerator)
                                } else {
                                    null
                                },
                                // Audio input is not wired up in this app.
                                audioBackend = null,
                                maxNumTokens = maxTokens,
                                maxNumImages = if (withVision) MAX_IMAGES_PER_TURN else null,
                                // Null makes LiteRT cache compiled weights beside the model
                                // file, inside the per-model directory. Pointing this at
                                // `context.cacheDir` would let Android evict it under
                                // storage pressure, mid-session.
                                cacheDir = null
                            )
                        )
                        engine.initialize()
                        if (attempt != accelerator || !withVision) {
                            Log.w(
                                TAG,
                                "Loaded ${model.id} on $attempt with vision=$withVision " +
                                    "(requested $accelerator, vision=${model.supportsImage})"
                            )
                        }
                        _visionActive.value = withVision
                        // Keyed on what was *requested*, not on what loaded. Storing the
                        // fallback would make every subsequent turn compare "cpu" against
                        // the requested "gpu", miss, and reload the whole model.
                        loaded = Loaded(key, engine)
                        _initState.value = InitState.Ready(model.displayName)
                        return@withContext engine
                    } catch (e: Throwable) {
                        Log.e(
                            TAG,
                            "Engine init failed for ${model.id} on $attempt (vision=$withVision)",
                            e
                        )
                        if (firstError == null) firstError = e
                    }
                }

                val message = firstError?.message ?: "Model failed to load"
                _initState.value = InitState.Failed(message)
                throw LiteRtException("Could not load ${model.displayName}: $message")
            }
        }
    }

    private fun backendFor(accelerator: String): Backend =
        if (accelerator.equals("cpu", ignoreCase = true)) Backend.CPU() else Backend.GPU()

    /**
     * Frees the loaded weights. Called from memory-pressure callbacks and whenever
     * [syncTo] finds the current thread does not want this model.
     *
     * **Never unloads mid-turn.** Closing the engine out from under a running
     * generation is a native use-after-free, not a caught exception — and a turn does
     * not hold [engineMutex] while it streams, so the mutex alone is not the guard.
     * A turn in flight simply keeps the model; the next screen change or memory
     * callback releases it.
     */
    fun release() {
        // Non-suspending: a load in progress holds the mutex and will install its engine
        // afterwards, which the next release() call frees.
        if (!engineMutex.tryLock()) return
        try {
            // Re-checked *inside* the lock, and claimed inside it too — see [generate].
            // Checking before taking the lock left a window in which a turn started
            // between the check and the close, and freed the weights out from under it.
            if (inFlightTurns.get() > 0) return
            closeLoadedLocked()
        } finally {
            engineMutex.unlock()
        }
    }

    private fun closeLoadedLocked() {
        _visionActive.value = false
        synchronized(conversationLock) {
            activeConversation?.runCatching { close() }
            activeConversation = null
        }
        loaded?.engine?.runCatching { close() }
        loaded = null
        _initState.value = InitState.Idle
    }

    // ---------------------------------------------------------------- generation

    /**
     * Asks the runtime to abandon the turn, and returns immediately.
     *
     * `cancelProcess()` is a blocking native call: it does not return until the decode
     * step already in flight finishes, which on a large model is seconds. Called straight
     * from the Stop button — the composable's `onClick` runs on the main thread — that is
     * seconds of frozen UI, so the button appeared not to work and the user pressed it
     * again.
     *
     * Dispatched instead, so the tap only *requests* the stop. Everything the user
     * actually watches — the streaming text, the Stop button turning back into Send — is
     * driven by flags `LLMService.cancelRequest` clears synchronously, and those no
     * longer wait on the runtime.
     *
     * The reference is captured first: [closeLoadedLocked] can null the field while this
     * is queued, and a cancel that quietly became a no-op is the bug this exists to fix.
     */
    override fun cancel() {
        cancelScope.launch {
            synchronized(conversationLock) {
                // Re-read inside the lock: whoever closed it set this to null first, so a
                // cancel that arrives after the turn already ended finds nothing to do
                // instead of calling into a freed conversation.
                activeConversation?.runCatching { cancelProcess() }
            }
        }
    }

    override suspend fun generate(
        messages: List<LLMMessage>,
        systemPrompt: String,
        settings: EffectiveSettings,
        onChunk: ((String) -> Unit)?,
        toolSpecs: List<McpToolSpec>,
        onToolCall: ((Boolean) -> Unit)?,
        callTool: (suspend (String, String) -> String)?
    ): GenerationResult {
        val model = settings.litertModelSpec
            ?: throw LiteRtException("No on-device model selected.")

        // Claimed under [engineMutex] — the same lock [release] holds while it frees —
        // so a turn can never be claimed *during* a teardown, nor a teardown begin
        // between this claim and the engine being resolved. Released only in the
        // outermost `finally`, so a screen change or a memory callback cannot unload the
        // weights underneath a turn that is still streaming.
        engineMutex.withLock { inFlightTurns.incrementAndGet() }
        try {
            return generateInternal(model, messages, systemPrompt, settings, onChunk, toolSpecs, onToolCall, callTool)
        } finally {
            inFlightTurns.decrementAndGet()
        }
    }

    private suspend fun generateInternal(
        model: LitertModel,
        messages: List<LLMMessage>,
        systemPrompt: String,
        settings: EffectiveSettings,
        onChunk: ((String) -> Unit)?,
        toolSpecs: List<McpToolSpec>,
        onToolCall: ((Boolean) -> Unit)?,
        callTool: (suspend (String, String) -> String)?
    ): GenerationResult {
        val engine = ensureEngine(model, settings, ownTurns = 1)

        // The last user turn is what we send; everything before it seeds the
        // conversation. History replay is text-only, which is exactly right here:
        // consolidateMessages already strips images from all but the newest message.
        val lastUserIdx = messages.indexOfLast { it.role == LLMMessage.LLMRole.USER }
        val history = if (lastUserIdx >= 0) messages.subList(0, lastUserIdx) else messages
        val current = messages.getOrNull(lastUserIdx)

        val invocations = mutableListOf<ToolInvocation>()
        val toolProviders = if (toolSpecs.isNotEmpty() && callTool != null) {
            McpLiteRtTools.providers(
                specs = toolSpecs,
                onToolCall = onToolCall,
                onInvocation = invocations::add,
                callTool = callTool
            )
        } else {
            emptyList()
        }

        val conversation = withContext(Dispatchers.IO) {
            engine.createConversation(
                ConversationConfig(
                    // The tools are declared to the runtime natively, so no catalogue
                    // has to be appended here for the model to know they exist.
                    systemInstruction = Contents.of(systemPrompt),
                    initialMessages = history.map { it.toLitertMessage() },
                    tools = toolProviders,
                    samplerConfig = SamplerConfig(
                        topK = settings.litertTopK.toIntOrNull() ?: model.defaultTopK,
                        topP = (settings.litertTopP.toFloatOrNull() ?: model.defaultTopP).toDouble(),
                        temperature = (settings.litertTemperature.toFloatOrNull()
                            ?: model.defaultTemperature).toDouble()
                    )
                )
            )
        }
        activeConversation = conversation

        val answer = StringBuilder()
        val reasoning = StringBuilder()
        try {
            // `visionActive`, not `supportsImage`: the engine may have been loaded
            // without vision because its required backend was unavailable.
            val contents = buildTurnContents(current, _visionActive.value)
            // Passed explicitly on every turn, and as a real Boolean rather than a
            // string: the runtime serialises this map to JSON, and only a boolean
            // literal is read back as the flag. Sent even when false so a checkpoint
            // that thinks by default does not quietly ignore the setting; models
            // without a thinking mode ignore the key entirely.
            val extraContext = mapOf<String, Any>(
                ENABLE_THINKING_KEY to (model.supportsThinking && settings.litertEnableThinking)
            )
            withContext(Dispatchers.IO) {
                conversation.sendMessageAsync(contents, extraContext).collect { message ->
                    val delta = message.toString()
                    // Control tokens are the model talking to the runtime, not to the user.
                    if (delta.startsWith(CONTROL_TOKEN_PREFIX)) return@collect
                    message.channels[THOUGHT_CHANNEL]?.let { reasoning.append(it) }
                    if (delta.isNotEmpty()) {
                        answer.append(delta)
                        onChunk?.invoke(delta)
                    }
                }
            }
        } catch (e: CancellationException) {
            conversation.runCatching { cancelProcess() }
            throw e
        } finally {
            onToolCall?.invoke(false)
            withContext(NonCancellable) {
                // Nulled and closed as one step, under the lock a queued [cancel] takes:
                // clearing the field first is what makes that cancel a no-op rather than
                // a call into a conversation this is about to free.
                synchronized(conversationLock) {
                    activeConversation = null
                    conversation.runCatching { close() }
                }
            }
        }

        return GenerationResult(
            content = answer.toString().trim(),
            reasoning = reasoning.toString().trim(),
            toolCalls = invocations
        )
    }

    /**
     * Builds the contents for the turn being sent. Images go first: the runtime uses the
     * final token to anchor generation, so trailing text is what the model answers about.
     *
     * The attachment bytes go straight through — the same JPEGs the OpenAI-compatible path
     * sends to the server. The runtime's decoder reads JPEG as well as PNG, so no PNG
     * round-trip and no crop/resize happens here; the vision preprocessor downsizes the
     * image to the checkpoint's own patch budget, decided by the engine (no
     * `visualTokenBudget` is set on the conversation, exactly like the reference gallery).
     */
    private fun buildTurnContents(message: LLMMessage?, supportsImage: Boolean): Contents {
        val parts = mutableListOf<Content>()
        if (supportsImage) {
            message?.imageData?.take(MAX_IMAGES_PER_TURN)?.forEach { image ->
                parts.add(Content.ImageBytes(image))
            }
        }
        val text = message?.content.orEmpty()
        if (text.isNotBlank()) parts.add(Content.Text(text))
        // The runtime rejects an empty turn; a lone space is the cheapest valid stand-in.
        if (parts.isEmpty()) parts.add(Content.Text(" "))
        return Contents.of(parts)
    }

    private fun LLMMessage.toLitertMessage() =
        if (role == LLMMessage.LLMRole.USER) {
            com.google.ai.edge.litertlm.Message.user(content)
        } else {
            com.google.ai.edge.litertlm.Message.model(content)
        }

    companion object {
        /**
         * Matches the reference app's per-turn image cap. In practice the app sends at
         * most one (a camera frame or a single attachment), so this is a guard rail.
         */
        const val MAX_IMAGES_PER_TURN = 10
    }
}

/** Raised for on-device failures the UI should show as an ordinary LLM error. */
class LiteRtException(message: String) : Exception(message)
