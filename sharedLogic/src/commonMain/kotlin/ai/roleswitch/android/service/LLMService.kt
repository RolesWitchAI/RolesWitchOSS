//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import co.touchlab.kermit.Logger
import ai.roleswitch.android.data.dao.McpToolDao
import ai.roleswitch.android.data.dao.MessageDao
import ai.roleswitch.android.data.dao.MessageThreadDao
import ai.roleswitch.android.data.model.DocumentAttachment
import ai.roleswitch.android.data.model.DocumentInput
import ai.roleswitch.android.data.model.Message
import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.service.mcp.McpService
import ai.roleswitch.android.settings.EffectiveSettings
import ai.roleswitch.android.settings.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val TAG = "LLMService"
private val log = Logger.withTag(TAG)
private const val IMAGE_WINDOW_SIZE = 1

// Only the newest doc-bearing message's extracted text reaches the engine — the same
// windowing as images, so a long conversation (or repeated attaches) cannot balloon the
// context with every document ever sent.
private const val DOCUMENT_WINDOW_SIZE = 1
// Ceiling on how much extracted text a single document brings into the prompt/storage.
// Full-document text runs to megabytes in the wild; 100k chars (~25k tokens) is far past
// what any chat turn can usefully consume and keeps the Room row and the request small.
private const val MAX_DOCUMENT_CHARS = 100_000

/**
 * Ceiling on how many messages an on-device turn replays. The conversation is
 * rebuilt (and therefore re-prefilled) every turn, so an unbounded history is a hang
 * on a phone rather than the merely-expensive request it is against a remote endpoint.
 * Matches the "Messages to process" picker's on-device maximum — "Unlimited" (100000)
 * is hidden for the on-device engine, so 120 is the largest value the UI can select.
 */
private const val ON_DEVICE_MESSAGE_LIMIT = 120
private const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant."

/** Clipping for the tool trace shown under the assistant message. */
private const val TRACE_ARGS_LIMIT = 200
private const val TRACE_RESULT_LIMIT = 400

/**
 * Emitted on successful completion of an LLM response. The App-level observer uses
 * this to post a "messenger-style" notification to the user when the app is in
 * the background or the screen is locked.
 */
data class CompletedResponse(
    val threadId: String,
    val content: String
)

class LLMService(
    private val oaiService: OAIService,
    private val settingsManager: SettingsManager,
    private val messageDao: MessageDao,
    private val threadDao: MessageThreadDao,
    private val mcpToolDao: McpToolDao,
    private val mcpService: McpService,
    /**
     * On-device backend, when the platform has one. Android supplies a `LiteRtService`, iOS a
     * Swift `LiteRtInferenceEngine`. Null makes [EffectiveSettings.useOnDevice] inert rather than
     * broken — every turn falls through to [oaiService].
     *
     * Last parameter with a default so a platform without one compiles unchanged.
     */
    private val onDeviceEngine: InferenceEngine? = null
) {
    private val _streamingResponse = MutableStateFlow("")
    val streamingResponse: StateFlow<String> = _streamingResponse.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _currentThreadId = MutableStateFlow<String?>(null)
    val currentThreadId: StateFlow<String?> = _currentThreadId.asStateFlow()

    /**
     * True while a tool call is executing. Drives the "Using a tool…" status; the LLM
     * is not producing text while this is set. Which tool is running is deliberately not
     * exposed — the status line does not name it, and the per-call detail is already in
     * the trace on the finished message.
     */
    private val _isCallingTool = MutableStateFlow(false)
    val isCallingTool: StateFlow<Boolean> = _isCallingTool.asStateFlow()

    private val _responseCompleted = MutableSharedFlow<CompletedResponse>(
        extraBufferCapacity = 16
    )
    val responseCompleted: SharedFlow<CompletedResponse> = _responseCompleted.asSharedFlow()

    /**
     * Emitted with the thread id when a turn ends **without** persisting an assistant
     * message — cancelled from the stop button, failed, or the model returned nothing.
     *
     * The chat screen holds the last streamed text in a buffer to bridge the gap between
     * the stream ending and the row arriving from Room. On these paths no row is ever
     * coming, so without this signal the buffer strands: the bubble keeps showing partial
     * text that no [Message] backs, which cannot be long-pressed, copied or edited.
     */
    private val _responseDiscarded = MutableSharedFlow<String>(
        extraBufferCapacity = 16
    )
    val responseDiscarded: SharedFlow<String> = _responseDiscarded.asSharedFlow()

    private var currentJob: Job? = null

    /**
     * The turn guard: [turnSequence] assigns each request a number and [activeTurn] names
     * the newest one, so a *stale* turn's teardown cannot clobber the state of the turn that
     * superseded it. Necessary once sending while a turn is in flight becomes a normal flow:
     * one-shot mode lets the user send a message mid-generation, and the old turn's `finally`
     * — which unconditionally cleared `isStreaming`/`isThinking`/`_streamingResponse` and
     * nulled the shared job — would otherwise arrive after the new turn had set its own state
     * and wipe it, dropping the balloon and un-cancellable-ing the new request.
     */
    private var turnSequence = 0L
    private var activeTurn = 0L

    /**
     * The engine serving the in-flight turn. Held so [cancelRequest] aborts the one
     * that is actually running: the choice is resolved per turn from the thread's
     * settings, so cancelling [oaiService] unconditionally would leave an on-device
     * generation running.
     */
    private var activeEngine: InferenceEngine? = null

    /** Picks the backend for a turn, falling back to remote when there is no on-device engine. */
    private fun engineFor(settings: EffectiveSettings): InferenceEngine =
        if (settings.useOnDevice) onDeviceEngine ?: oaiService else oaiService

    private fun formatTimestamp(epochMillis: Long): String {
        val tz = TimeZone.currentSystemDefault()
        val dt = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(tz)
        fun Int.pad2() = toString().padStart(2, '0')
        return "${dt.year}-${dt.monthNumber.pad2()}-${dt.dayOfMonth.pad2()} ${dt.hour.pad2()}:${dt.minute.pad2()}"
    }

    // For AssistantView - inserts both user and assistant messages
    // `@Throws` is load-bearing on iOS: without it an escaping exception terminates the
    // process instead of arriving as an NSError, so the Swift `catch` blocks that turn
    // it into the "Error" alert never run. Broad on purpose — any failure type.
    @Throws(Exception::class)
    suspend fun sendMessage(
        text: String,
        threadId: String,
        images: List<ByteArray> = emptyList(),
        documents: List<DocumentInput> = emptyList()
    ) {
        cancelRequestForThread(threadId)

        val attachments = extractDocuments(documents)
        val userMessage = Message(
            content = text,
            isUser = true,
            threadId = threadId,
            imageData = images,
            documents = attachments
        )
        messageDao.insert(userMessage)

        processRequest(threadId, images)
    }

    /**
     * Turns picked [DocumentInput]s into persisted [DocumentAttachment]s by running the
     * platform PDF extractor off the calling (UI) thread — PDFBox/PDFKit parsing is native
     * and heavy. A document that fails to parse yields an attachment with empty
     * [DocumentAttachment.extractedText]: the bubble still shows the file, and the prompt
     * carries a "no text could be extracted" marker instead of dropping the turn.
     *
     * Public because Android's `MessagesScreen` inserts the user message itself and so
     * needs the extracted attachments before `processLLMResponse`; the failure of one
     * document is never fatal to its siblings or to the turn.
     */
    @Throws(Exception::class)
    suspend fun extractDocuments(documents: List<DocumentInput>): List<DocumentAttachment> =
        withContext(Dispatchers.Default) {
            documents.map { input ->
                DocumentAttachment(
                    fileName = input.fileName,
                    sizeBytes = input.data.size.toLong(),
                    pageCount = PdfTextExtractor.pageCount(input.data),
                    extractedText = PdfTextExtractor.extractText(input.data)
                        ?.trim()?.take(MAX_DOCUMENT_CHARS) ?: ""
                )
            }
        }

    // For MessagesView - user message already inserted
    @Throws(Exception::class)
    suspend fun processLLMResponse(userText: String, threadId: String, images: List<ByteArray> = emptyList()) {
        cancelRequestForThread(threadId)
        processRequest(threadId, images)
    }

    fun cancelRequest() {
        log.d { "Cancelling request" }
        (activeEngine ?: oaiService).cancel()
        currentJob?.cancel()
        currentJob = null
        _isStreaming.value = false
        _streamingResponse.value = ""
        _isThinking.value = false
        _isCallingTool.value = false
    }

    /**
     * Cancels the in-flight request only if it belongs to [threadId]. There is
     * a single shared request slot, so an unscoped cancel from one surface would
     * otherwise kill a stream the user started somewhere else — a different
     * thread in the UI.
     */
    fun cancelRequestForThread(threadId: String) {
        if (_currentThreadId.value == threadId) cancelRequest()
    }

    private suspend fun processRequest(threadId: String, images: List<ByteArray> = emptyList()) {
        // Number this turn *before* touching any shared state: the new turn is the active one
        // from here on, so a still-cancelling predecessor knows it has been superseded.
        val turn = ++turnSequence
        activeTurn = turn
        _currentThreadId.value = threadId
        val thread = threadDao.getThreadById(threadId)
        val settings = EffectiveSettings.resolve(thread ?: MessageThread(threadId = threadId, title = ""), settingsManager)
        val systemPrompt = buildSystemPrompt(thread?.prompt, settings.embedTime)
        val messages = messageDao.getMessagesByThread(threadId)

        val engine = engineFor(settings)
        activeEngine = engine
        // On-device prefill is linear in history and re-run every turn, so the
        // "Unlimited" (100000) setting that costs a remote endpoint only tokens
        // would hang the device. Cap it to something a phone can actually prefill.
        val resolvedMessageLimit = if (settings.useOnDevice) {
            settings.messageLimit.coerceAtMost(ON_DEVICE_MESSAGE_LIMIT)
        } else {
            settings.messageLimit
        }
        val resolvedEmbedTime = settings.embedTime

        _isStreaming.value = true
        _streamingResponse.value = ""
        _isThinking.value = true

        // Drives [responseDiscarded]. Set only once the assistant row is actually in the
        // database, so every other way out of this function — cancellation, an error, an
        // empty completion — is reported as a discarded turn.
        var persisted = false

        try {
            coroutineScope {
                currentJob = coroutineContext[Job]

            val consolidated = consolidateMessages(messages, resolvedMessageLimit, resolvedEmbedTime).let { list ->
                if (images.isNotEmpty()) {
                    // Attach images to the last user message
                    val lastUserIdx = list.indexOfLast { it.role == LLMMessage.LLMRole.USER }
                    if (lastUserIdx >= 0) {
                        list.toMutableList().apply {
                            this[lastUserIdx] = this[lastUserIdx].copy(imageData = images)
                        }
                    } else list
                } else list
            }

            // `update`, not `value +=`: the on-device engine delivers chunks on a
            // LiteRT native thread, where a read-modify-write on `value` can drop text.
            //
            // One-shot mode passes no chunk callback at all: nothing is streamed to the UI,
            // so `streamingResponse` stays empty, the thinking balloon is all the user sees,
            // and the reply lands whole from Room when the turn completes. The engine still
            // streams internally towards the endpoint — this only decides what reaches `_streamingResponse`.
            val onChunk: ((String) -> Unit)? = if (settings.streamingMode) {
                { chunk -> _streamingResponse.update { it + chunk } }
            } else {
                null
            }

            // This thread's own selection is the whole tool decision — `mcp_tools` only
            // registers endpoints, so there is nothing above or beside this to consult.
            // Empty is how "no tools" is expressed, and it is where every chat starts.
            val wanted = settings.enabledToolIds.toSet()
            val enabledTools =
                if (wanted.isEmpty()) emptyList() else mcpToolDao.getAllTools().filter { it.id in wanted }
            // Says out loud what the thread asked for versus what still exists, so a turn that
            // unexpectedly runs without tools can be diagnosed from the log rather than by
            // guessing at thread state — an id whose row was deleted drops out silently here.
            log.d { "Tools for $threadId: ${wanted.size} selected -> ${enabledTools.size} endpoint(s)" }
            val session = if (enabledTools.isEmpty()) null else mcpService.openSession(enabledTools)

            val result = try {
                engine.generate(
                    messages = consolidated,
                    systemPrompt = systemPrompt,
                    settings = settings,
                    onChunk = onChunk,
                    toolSpecs = session?.specs.orEmpty(),
                    // The narration the model streams before a tool call ("Let me search for
                    // that…") is deliberately left on screen while the call runs: it is the
                    // live progress, and the saved message replaces the whole streaming
                    // bubble when the turn ends. Only the *stored* reply drops it — see the
                    // per-round `answer` in OAIService.
                    onToolCall = { running -> _isCallingTool.value = running },
                    callTool = session?.let { { name, args -> it.call(name, args) } }
                )
            } finally {
                _isCallingTool.value = false
                session?.close()
            }

            val (content, reasoning) = if (result.reasoning.isNotEmpty()) {
                result.content to result.reasoning
            } else {
                extractThinking(result.content)
            }

            // The trace joins the model's own reasoning so it lands in the collapsible
            // block the UI already renders — no new message rows, no schema change.
            val fullReasoning = buildReasoning(reasoning, result.toolCalls, session)

            if (content.isNotEmpty()) {
                val assistantMessage = Message(
                    content = content,
                    reasoning = fullReasoning,
                    isUser = false,
                    threadId = threadId
                )
                messageDao.insert(assistantMessage)
                persisted = true
                _responseCompleted.tryEmit(CompletedResponse(threadId = threadId, content = content))
                log.d { "Response saved, length: ${content.length}" }
            }
            }
        } catch (e: CancellationException) {
            // Only the active turn clears the streaming text on cancellation — a *superseded*
            // turn arriving here must not wipe the newer turn's live stream.
            if (activeTurn == turn) _streamingResponse.value = ""
            log.d { "Request cancelled" }
        } catch (e: OAIError) {
            // A superseded turn's failure is the new turn's beginning: the user replaced this
            // request mid-flight, so surfacing its error as an alert would be noise.
            if (activeTurn != turn) return
            if (!e.isSilent) {
                throw e
            }
        } catch (e: Exception) {
            // Catch-all, not just for iOS: anything unexpected — the database, the on-device
            // engine's native errors, serialization — becomes an OAIError here, so no surface
            // ever sees a raw exception type and there is always a readable message to show.
            // CancellationException is matched above; this arm cannot swallow it.
            if (activeTurn != turn) return
            throw OAIError.InvalidResponse(
                reason = e.message?.takeIf { it.isNotBlank() }
                    ?: e::class.simpleName
                    ?: "Unknown error"
            )
        } finally {
            // The turn guard: only the newest turn's teardown may clear the shared progress
            // state. A superseded one has already been replaced — `sendMessage`/`processLLMResponse`
            // cancel the old job and immediately start the new request, whose own state must
            // survive the old `finally` running afterwards. `activeTurn` is only ever touched
            // from the calling coroutine (which starts each new turn), so the comparison is
            // race-free on the single-threaded dispatchers these surfaces share.
            if (activeTurn == turn) {
                _isThinking.value = false
                _isStreaming.value = false
                _streamingResponse.value = ""
                _isCallingTool.value = false
                currentJob = null
                _currentThreadId.value = null
            }
            // Runs on cancellation too — `finally` still executes once the job is
            // cancelled, and `tryEmit` does not suspend, so it cannot be skipped.
            // A superseded turn emits nothing: there is no partial bubble for the chat
            // screen to drop once its replacement has already started.
            if (!persisted && activeTurn == turn) _responseDiscarded.tryEmit(threadId)
        }
    }

    /**
     * Renders the tool calls made during a turn as a compact trace and appends it to the
     * model's own reasoning. Arguments and results are clipped hard: this is a summary for
     * a collapsible UI block, not a log, and the full result already went to the model.
     */
    private fun buildReasoning(
        modelReasoning: String,
        invocations: List<ToolInvocation>,
        session: McpService.McpSession?
    ): String {
        if (invocations.isEmpty()) return modelReasoning
        val trace = invocations.joinToString("\n") { call ->
            val label = session?.describe(call.label) ?: call.label
            val args = call.arguments.ifBlank { "{}" }.clip(TRACE_ARGS_LIMIT)
            val result = call.result.clip(TRACE_RESULT_LIMIT)
            "→ $label($args)\n← $result"
        }
        return if (modelReasoning.isBlank()) trace else "$modelReasoning\n\n$trace"
    }

    private fun String.clip(limit: Int): String {
        val single = replace('\n', ' ').trim()
        return if (single.length <= limit) single else single.take(limit) + "…"
    }

    private fun buildSystemPrompt(threadPrompt: String?, embedTime: Boolean): String {
        val base = if (!threadPrompt.isNullOrEmpty()) threadPrompt else DEFAULT_SYSTEM_PROMPT
        // When time embedding is enabled, also surface the current wall-clock time at the
        // top of the system prompt — this is the most reliable way for the model to know
        // "what day is it?" even in a fresh thread with no prior history.
        return if (embedTime) {
            val now = formatTimestamp(Clock.System.now().toEpochMilliseconds())
            "$base\n\nCurrent date and time: $now"
        } else {
            base
        }
    }

    internal fun consolidateMessages(messages: List<Message>, messageLimit: Int, embedTime: Boolean): List<LLMMessage> {

        // Find the last message with images within the window (only one image message sent to LLM)
        val imageWindowStart = (messages.size - IMAGE_WINDOW_SIZE).coerceAtLeast(0)
        val lastImageIdx = messages.indices.lastOrNull { idx ->
            idx >= imageWindowStart && messages[idx].imageData.isNotEmpty()
        } ?: -1

        // Same newest-only window for documents: only the most recent doc-bearing message's
        // extracted text reaches the engine, bounding the context exactly like images.
        val docWindowStart = (messages.size - DOCUMENT_WINDOW_SIZE).coerceAtLeast(0)
        val lastDocIdx = messages.indices.lastOrNull { idx ->
            idx >= docWindowStart && messages[idx].documents.isNotEmpty()
        } ?: -1

        // Phase 1: Consolidate messages
        val phase1 = mutableListOf<LLMMessage>()
        var currentRole: LLMMessage.LLMRole? = null
        var currentContent = ""
        var currentImages = mutableListOf<ByteArray>()
        for ((reversedIdx, message) in messages.reversed().withIndex()) {
            if (phase1.size >= messageLimit) continue
            val role = if (message.isUser) LLMMessage.LLMRole.USER else LLMMessage.LLMRole.ASSISTANT
            val originalIdx = messages.size - 1 - reversedIdx
            // The document's extracted text rides on the user message's content — the engine
            // never sees the file itself, only this injected block, on the newest doc-bearing
            // message within the window and no other.
            val origin = formatContent(message, embedTime)
            val content = if (originalIdx == lastDocIdx && message.documents.isNotEmpty()) {
                "$origin\n\n${documentContextBlock(message.documents)}"
            } else {
                origin
            }

            // Include images only from the last message that has images (within the window)
            val msgImages = if (originalIdx == lastImageIdx) message.imageData else emptyList()

            if (currentRole == null) {
                currentRole = role
                currentContent = content
                currentImages = msgImages.toMutableList()
            } else if (currentRole == role) {
                currentContent = "$content\n$currentContent"
                currentImages.addAll(0, msgImages)
            } else {
                phase1.add(LLMMessage(role = currentRole, content = currentContent, imageData = currentImages))
                currentRole = role
                currentContent = content
                currentImages = msgImages.toMutableList()
            }
        }

        if (currentRole != null && currentContent.isNotEmpty() &&
            phase1.size < messageLimit
        ) {
            phase1.add(LLMMessage(role = currentRole, content = currentContent, imageData = currentImages))
        }

        return phase1.reversed()
    }

    private fun formatContent(message: Message, embedTime: Boolean): String {
        if (embedTime && message.isUser) {
            val timestamp = formatTimestamp(message.createdAt)
            val prefixed = "[$timestamp] ${message.content}"
            log.d { "embedTime: user message -> $prefixed" }
            return prefixed
        }
        return message.content
    }

    /**
     * Renders a message's documents as a labelled text block for the engine prompt.
     * The model reads the extracted text as content — the file itself never leaves
     * the device. A document with nothing extracted still says so explicitly rather
     * than vanishing silently, so the model knows the attachment exists.
     */
    private fun documentContextBlock(documents: List<DocumentAttachment>): String =
        documents.joinToString("\n\n") { doc ->
            val name = doc.fileName.ifBlank { "document" }
            val body = doc.extractedText.trim().takeIf { it.isNotEmpty() }
                ?: "(no text could be extracted from this PDF)"
            "[Attached document: $name]\n$body\n[/document]"
        }

    private fun extractThinking(response: String): Pair<String, String> {
        // Most thinking models use <think> … </think>; Gemma 4 uses
        // <|channel> … <channel|> instead.  The two formats are mutually exclusive per model,
        // so the first match wins.
        data class TagPair(val start: String, val end: String)

        val candidates = listOf(
            TagPair("<think>", "</think>"),
            TagPair("<|channel>", "<channel|>")
        )

        for (tag in candidates) {
            val startIndex = response.indexOf(tag.start)
            if (startIndex == -1) continue
            val endIndex = response.indexOf(tag.end, startIndex + tag.start.length)
            if (endIndex == -1) continue
            val reasoning = response.substring(startIndex + tag.start.length, endIndex).trim()
            val content = response.substring(0, startIndex) + response.substring(endIndex + tag.end.length)
            return Pair(content.trim(), reasoning)
        }

        return Pair(response.trim(), "")
    }
}
