//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ai.roleswitch.android.service.LitertModel
import ai.roleswitch.android.service.LitertModels
import ai.roleswitch.android.service.decodeCustomLitertModels
import ai.roleswitch.android.service.encodeCustomLitertModels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsManager(private val dataStore: DataStore<Preferences>) {
    /**
     * Single-threaded so queued writes start in the order they were requested;
     * [writeMutex] then keeps them from interleaving once they suspend inside
     * `dataStore.edit`. Without both, a bulk [reset] could land *after* writes
     * the user made later and silently undo them.
     */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val writeMutex = Mutex()
    private val snapshot: Preferences = runBlocking { dataStore.data.first() }

    companion object {
        val defaultLanguages = mapOf(
            "en" to "English",
            "ru" to "Русский",
            "fr" to "Français",
            "de" to "Deutsch",
            "es" to "Español",
            "pt" to "Português"
        )

        val localeForSTT = mapOf(
            "en" to "en-US",
            "ru" to "ru-RU",
            "fr" to "fr-FR",
            "de" to "de-DE",
            "es" to "es-ES",
            "pt" to "pt-PT"
        )

        private val KEY_HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("hasCompletedOnboarding")
        private val KEY_SELECTED_MODEL = stringPreferencesKey("selectedModel")
        private val KEY_OPENAI_API = stringPreferencesKey("openaiAPI")
        private val KEY_OPENAI_KEY = stringPreferencesKey("openaiKey")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_VOICE = stringPreferencesKey("voice")
        private val KEY_ENABLE_VISION = booleanPreferencesKey("enableVision")
        private val KEY_EMBED_TIME = booleanPreferencesKey("embedTime")
        private val KEY_MESSAGE_LIMIT = intPreferencesKey("messageLimit")
        private val KEY_REQUIRE_ON_DEVICE_VOICE = booleanPreferencesKey("requireOnDeviceVoice")
        private val KEY_KEEP_LAST_ATTACHMENT = booleanPreferencesKey("keepLastAttachment")
        private val KEY_USE_VOSK = booleanPreferencesKey("useVosk")
        private val KEY_ALLOW_INTERRUPTIONS = booleanPreferencesKey("allowInterruptions")
        private val KEY_USE_SYSTEM_TTS = booleanPreferencesKey("useSystemTts")
        private val KEY_USE_EXTERNAL_TTS = booleanPreferencesKey("useExternalTts")
        private val KEY_EXTERNAL_TTS_VOICE = stringPreferencesKey("externalTtsVoice")
        // The external speech server and its token are their own settings, separate from
        // `openaiAPI`/`openaiKey`: a user may run inference and speech on different servers
        // (or the same server) and nothing here may assume they share an endpoint.
        private val KEY_EXTERNAL_TTS_API = stringPreferencesKey("externalTtsAPI")
        private val KEY_EXTERNAL_TTS_KEY = stringPreferencesKey("externalTtsKey")
        private val KEY_STREAMING_MODE = booleanPreferencesKey("streamingMode")
        private val KEY_TEMPERATURE = stringPreferencesKey("temperature")
        private val KEY_TOP_P = stringPreferencesKey("topP")
        private val KEY_N = stringPreferencesKey("n")
        private val KEY_MAX_COMPLETION_TOKENS = stringPreferencesKey("maxCompletionTokens")
        private val KEY_PRESENCE_PENALTY = stringPreferencesKey("presencePenalty")
        private val KEY_FREQUENCY_PENALTY = stringPreferencesKey("frequencyPenalty")
        private val KEY_REASONING_EFFORT = stringPreferencesKey("reasoningEffort")
        private val KEY_HAS_SEEN_NOTIFICATION_PROMPT = booleanPreferencesKey("hasSeenNotificationPrompt")
        private val KEY_HAS_ACCEPTED_DATA_SHARING = booleanPreferencesKey("hasAcceptedDataSharing")
        private val KEY_HAS_ACCEPTED_MULTIPLE_ATTACHMENTS = booleanPreferencesKey("hasAcceptedMultipleAttachments")

        // On-device inference (LiteRT-LM). Keys are `litert*`-prefixed so an iOS
        // port — LiteRT ships there too — can reuse the same store.
        private val KEY_USE_LITERT = booleanPreferencesKey("litertEnabled")
        private val KEY_LITERT_MODEL = stringPreferencesKey("litertModel")
        private val KEY_LITERT_TOP_K = stringPreferencesKey("litertTopK")
        private val KEY_LITERT_TOP_P = stringPreferencesKey("litertTopP")
        private val KEY_LITERT_TEMPERATURE = stringPreferencesKey("litertTemperature")
        private val KEY_LITERT_MAX_TOKENS = stringPreferencesKey("litertMaxTokens")
        private val KEY_LITERT_ACCELERATOR = stringPreferencesKey("litertAccelerator")
        private val KEY_LITERT_ENABLE_THINKING = booleanPreferencesKey("litertEnableThinking")
        private val KEY_CUSTOM_LITERT_MODELS = stringPreferencesKey("litertCustomModels")
        private val KEY_LITERT_ACCEPTED_LICENSES = stringPreferencesKey("litertAcceptedLicenses")
        private val KEY_HUGGING_FACE_TOKEN = stringPreferencesKey("litertHuggingFaceToken")
    }

    private fun bool(key: Preferences.Key<Boolean>, default: Boolean): Boolean = snapshot[key] ?: default
    private fun str(key: Preferences.Key<String>, default: String): String = snapshot[key] ?: default
    private fun int(key: Preferences.Key<Int>, default: Int): Int = snapshot[key] ?: default
    private fun flt(key: Preferences.Key<Float>, default: Float): Float = snapshot[key] ?: default

    private val _hasCompletedOnboarding = MutableStateFlow(bool(KEY_HAS_COMPLETED_ONBOARDING, false))
    val hasCompletedOnboarding: StateFlow<Boolean> = _hasCompletedOnboarding.asStateFlow()

    private val _selectedModel = MutableStateFlow(str(KEY_SELECTED_MODEL, ""))
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _openaiAPI = MutableStateFlow(str(KEY_OPENAI_API, ""))
    val openaiAPI: StateFlow<String> = _openaiAPI.asStateFlow()

    private val _openaiKey = MutableStateFlow(str(KEY_OPENAI_KEY, ""))
    val openaiKey: StateFlow<String> = _openaiKey.asStateFlow()

    private val _language = MutableStateFlow(str(KEY_LANGUAGE, "en"))
    val language: StateFlow<String> = _language.asStateFlow()

    private val _voice = MutableStateFlow(str(KEY_VOICE, ""))
    val voice: StateFlow<String> = _voice.asStateFlow()

    private val _enableVision = MutableStateFlow(bool(KEY_ENABLE_VISION, false))
    val enableVision: StateFlow<Boolean> = _enableVision.asStateFlow()

    private val _embedTime = MutableStateFlow(bool(KEY_EMBED_TIME, false))
    val embedTime: StateFlow<Boolean> = _embedTime.asStateFlow()

    private val _messageLimit = MutableStateFlow(int(KEY_MESSAGE_LIMIT, 50))
    val messageLimit: StateFlow<Int> = _messageLimit.asStateFlow()

    private val _requireOnDeviceVoice = MutableStateFlow(bool(KEY_REQUIRE_ON_DEVICE_VOICE, false))
    val requireOnDeviceVoice: StateFlow<Boolean> = _requireOnDeviceVoice.asStateFlow()

    private val _keepLastAttachment = MutableStateFlow(bool(KEY_KEEP_LAST_ATTACHMENT, false))
    val keepLastAttachment: StateFlow<Boolean> = _keepLastAttachment.asStateFlow()

    private val _useVosk = MutableStateFlow(bool(KEY_USE_VOSK, false))
    val useVosk: StateFlow<Boolean> = _useVosk.asStateFlow()

    private val _allowInterruptions = MutableStateFlow(bool(KEY_ALLOW_INTERRUPTIONS, false))
    val allowInterruptions: StateFlow<Boolean> = _allowInterruptions.asStateFlow()

    private val _useSystemTts = MutableStateFlow(bool(KEY_USE_SYSTEM_TTS, false))
    val useSystemTts: StateFlow<Boolean> = _useSystemTts.asStateFlow()

    /**
     * Synthesize speech through the OpenAI-compatible `/v1/audio/speech` endpoint instead
     * of a local engine. Mutually exclusive with [useSystemTts] at runtime — the picker
     * writes one flag false when it sets the other, and [ai.roleswitch.android.settings.EffectiveSettings.ttsMode]
     * is the single derivation callers read.
     *
     * The user must not be able to end up with both true in a live thread: EXTERNAL is by
     * definition a network call, so turning "Process on device" on drops it back to AUTO
     * (see [setRequireOnDeviceVoice]).
     */
    private val _useExternalTts = MutableStateFlow(bool(KEY_USE_EXTERNAL_TTS, false))
    val useExternalTts: StateFlow<Boolean> = _useExternalTts.asStateFlow()

    /**
     * The voice name sent to the external speech server (e.g. `"alloy"`). Empty means the
     * server picks its default voice. It is a server-side id, unrelated to [voice], so it
     * is stored and resolved separately and survives engine voice changes.
     */
    private val _externalTtsVoice = MutableStateFlow(str(KEY_EXTERNAL_TTS_VOICE, ""))
    val externalTtsVoice: StateFlow<String> = _externalTtsVoice.asStateFlow()

    /**
     * The external speech server's OpenAI-compatible base URL (e.g. `http://0.0.0.0:9000/v1`).
     * Kept distinct from [openaiAPI]: inference and speech may live on different servers, and
     * editing one must never clobber the other (or the inference model selection).
     */
    private val _externalTtsAPI = MutableStateFlow(str(KEY_EXTERNAL_TTS_API, ""))
    val externalTtsAPI: StateFlow<String> = _externalTtsAPI.asStateFlow()

    /**
     * The token for [externalTtsAPI]. May be empty — local speech servers often need none.
     */
    private val _externalTtsKey = MutableStateFlow(str(KEY_EXTERNAL_TTS_KEY, ""))
    val externalTtsKey: StateFlow<String> = _externalTtsKey.asStateFlow()

    /**
     * Streams the reply into the chat as it is generated (the app's original behaviour),
     * with a Stop button while a turn is in flight. When false a turn runs to completion
     * before anything is appended: the thinking balloon is all the user sees, there is no
     * partial text and no Stop button, and sending while a turn is in flight cancels it and
     * starts another. Defaults to true — streaming is what the app has always done.
     */
    private val _streamingMode = MutableStateFlow(bool(KEY_STREAMING_MODE, true))
    val streamingMode: StateFlow<Boolean> = _streamingMode.asStateFlow()

    private val _temperature = MutableStateFlow(str(KEY_TEMPERATURE, ""))
    val temperature: StateFlow<String> = _temperature.asStateFlow()

    private val _topP = MutableStateFlow(str(KEY_TOP_P, ""))
    val topP: StateFlow<String> = _topP.asStateFlow()

    private val _n = MutableStateFlow(str(KEY_N, ""))
    val n: StateFlow<String> = _n.asStateFlow()

    private val _maxCompletionTokens = MutableStateFlow(str(KEY_MAX_COMPLETION_TOKENS, ""))
    val maxCompletionTokens: StateFlow<String> = _maxCompletionTokens.asStateFlow()

    private val _presencePenalty = MutableStateFlow(str(KEY_PRESENCE_PENALTY, ""))
    val presencePenalty: StateFlow<String> = _presencePenalty.asStateFlow()

    private val _frequencyPenalty = MutableStateFlow(str(KEY_FREQUENCY_PENALTY, ""))
    val frequencyPenalty: StateFlow<String> = _frequencyPenalty.asStateFlow()

    /**
     * `reasoning_effort` for reasoning models — `"none"`, `"low"`, `"medium"` or `"high"`.
     *
     * Empty is the default and means *omit the field*, not "send an empty value": a server
     * that has never heard of the parameter, or a model that is not a reasoning model, then
     * sees exactly the request it saw before this setting existed. Same `""`-means-unset
     * convention as the six generation parameters above.
     */
    private val _reasoningEffort = MutableStateFlow(str(KEY_REASONING_EFFORT, ""))
    val reasoningEffort: StateFlow<String> = _reasoningEffort.asStateFlow()

    private val _hasSeenNotificationPrompt = MutableStateFlow(bool(KEY_HAS_SEEN_NOTIFICATION_PROMPT, false))
    val hasSeenNotificationPrompt: StateFlow<Boolean> = _hasSeenNotificationPrompt.asStateFlow()

    /**
     * The user has been told, in as many words, that configuring a remote endpoint sends
     * their messages to it, and has agreed. Gates saving the server settings on both
     * platforms; false means the Data Sharing alert is still owed.
     *
     * One flag for the whole app, not one per endpoint — it records that the user
     * understands what remote mode does, which does not become untrue when the URL changes.
     * `reset()` clears it, so a fresh install and a reset app both ask again.
     */
    private val _hasAcceptedDataSharing = MutableStateFlow(bool(KEY_HAS_ACCEPTED_DATA_SHARING, false))
    val hasAcceptedDataSharing: StateFlow<Boolean> = _hasAcceptedDataSharing.asStateFlow()

    /**
     * The user has been shown the "Some models does not support multiple attachments"
     * gate and tapped OK. While false, attaching a *second* attachment to one message
     * raises that one-shot warning instead of silently attaching; once true the
     * composer accepts any number of attachments and never asks again.
     *
     * One flag for the whole app — the warning is a property of multi-attach in general,
     * not of one thread or one model. `reset()` clears it, so a fresh install and a
     * reset app both ask again, exactly like `hasAcceptedDataSharing`.
     */
    private val _hasAcceptedMultipleAttachments =
        MutableStateFlow(bool(KEY_HAS_ACCEPTED_MULTIPLE_ATTACHMENTS, false))
    val hasAcceptedMultipleAttachments: StateFlow<Boolean> =
        _hasAcceptedMultipleAttachments.asStateFlow()

    /**
     * Runs inference on device instead of against the OpenAI-compatible endpoint.
     *
     * Which engine that means is the platform's: LiteRT-LM on Android, MLX on iOS.
     * `LLMService` falls back to the remote engine when the platform supplied none, so this
     * is inert rather than broken on a platform without one.
     *
     * The preference key is still `litertEnabled`, from before there was a second engine.
     * Renaming it would reset the choice on every device that has already made one, and the
     * flag was never LiteRT-specific in the first place — only its name was.
     */
    private val _useOnDevice = MutableStateFlow(bool(KEY_USE_LITERT, false))
    val useOnDevice: StateFlow<Boolean> = _useOnDevice.asStateFlow()

    /** Id of the selected [ai.roleswitch.android.service.LitertModel]; empty means none. */
    private val _litertModel = MutableStateFlow(str(KEY_LITERT_MODEL, ""))
    val litertModel: StateFlow<String> = _litertModel.asStateFlow()

    // Sampler / engine parameters. String-typed like the OpenAI advanced fields so
    // blank cleanly means "use the model's own default" from the registry.
    private val _litertTopK = MutableStateFlow(str(KEY_LITERT_TOP_K, ""))
    val litertTopK: StateFlow<String> = _litertTopK.asStateFlow()

    private val _litertTopP = MutableStateFlow(str(KEY_LITERT_TOP_P, ""))
    val litertTopP: StateFlow<String> = _litertTopP.asStateFlow()

    private val _litertTemperature = MutableStateFlow(str(KEY_LITERT_TEMPERATURE, ""))
    val litertTemperature: StateFlow<String> = _litertTemperature.asStateFlow()

    private val _litertMaxTokens = MutableStateFlow(str(KEY_LITERT_MAX_TOKENS, ""))
    val litertMaxTokens: StateFlow<String> = _litertMaxTokens.asStateFlow()

    /** `"gpu"` or `"cpu"`; blank falls back to the model's default accelerator. */
    private val _litertAccelerator = MutableStateFlow(str(KEY_LITERT_ACCELERATOR, ""))
    val litertAccelerator: StateFlow<String> = _litertAccelerator.asStateFlow()

    /**
     * The runtime's `enable_thinking` flag, for checkpoints that have a thinking mode
     * ([ai.roleswitch.android.service.LitertModel.supportsThinking]). Ignored by models
     * that do not, so it is safe to leave set while switching between them.
     *
     * Defaults to false, matching the Google AI Edge Gallery: thinking is paid for in
     * tokens generated before the first visible word, which is a long wait on a phone.
     */
    private val _litertEnableThinking = MutableStateFlow(bool(KEY_LITERT_ENABLE_THINKING, false))
    val litertEnableThinking: StateFlow<Boolean> = _litertEnableThinking.asStateFlow()

    /**
     * Models the user added by URL, alongside the ones shipped in [LitertModels.registry].
     *
     * Pushed into [LitertModels.setCustom] on every change as well as being exposed here:
     * `EffectiveSettings.litertModelSpec` resolves an id through that object and cannot
     * reach this class, so a custom model would otherwise be selectable and then
     * unrunnable.
     */
    private val _customLitertModels =
        MutableStateFlow(decodeCustomLitertModels(str(KEY_CUSTOM_LITERT_MODELS, "")))
    val customLitertModels: StateFlow<List<LitertModel>> = _customLitertModels.asStateFlow()

    init {
        LitertModels.setCustom(_customLitertModels.value)
    }

    /**
     * Ids of models whose license the user has accepted, comma-separated. Global
     * on purpose: accepting the Gemma terms is a statement about the person, not
     * about one thread, so it has no `custom*` counterpart.
     */
    private val _litertAcceptedLicenses = MutableStateFlow(str(KEY_LITERT_ACCEPTED_LICENSES, ""))
    val litertAcceptedLicenses: StateFlow<String> = _litertAcceptedLicenses.asStateFlow()

    /**
     * HuggingFace access token, used to download models from gated repositories.
     *
     * Global rather than per-thread: it identifies the *person*, not a conversation, in
     * the same way the accepted-licence list does. Optional — the catalogue's ungated
     * models download without it.
     */
    private val _huggingFaceToken = MutableStateFlow(str(KEY_HUGGING_FACE_TOKEN, ""))
    val huggingFaceToken: StateFlow<String> = _huggingFaceToken.asStateFlow()

    private fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        writeScope.launch { writeMutex.withLock { dataStore.edit(block) } }
    }

    fun setHasCompletedOnboarding(value: Boolean) {
        _hasCompletedOnboarding.value = value
        write { it[KEY_HAS_COMPLETED_ONBOARDING] = value }
    }

    fun setSelectedModel(value: String) {
        _selectedModel.value = value
        write { it[KEY_SELECTED_MODEL] = value }
    }

    fun setOpenaiAPI(value: String) {
        _openaiAPI.value = value
        write { it[KEY_OPENAI_API] = value }
    }

    fun setOpenaiKey(value: String) {
        _openaiKey.value = value
        write { it[KEY_OPENAI_KEY] = value }
    }

    fun setLanguage(value: String) {
        _language.value = value
        write { it[KEY_LANGUAGE] = value }
    }

    fun setVoice(value: String) {
        _voice.value = value
        write { it[KEY_VOICE] = value }
    }

    fun setEnableVision(value: Boolean) {
        _enableVision.value = value
        write { it[KEY_ENABLE_VISION] = value }
    }

    fun setEmbedTime(value: Boolean) {
        _embedTime.value = value
        write { it[KEY_EMBED_TIME] = value }
    }

    fun setMessageLimit(value: Int) {
        _messageLimit.value = value
        write { it[KEY_MESSAGE_LIMIT] = value }
    }

    fun setRequireOnDeviceVoice(value: Boolean) {
        _requireOnDeviceVoice.value = value
        write { it[KEY_REQUIRE_ON_DEVICE_VOICE] = value }
    }

    fun setKeepLastAttachment(value: Boolean) {
        _keepLastAttachment.value = value
        write { it[KEY_KEEP_LAST_ATTACHMENT] = value }
    }

    fun setUseVosk(value: Boolean) {
        _useVosk.value = value
        write { it[KEY_USE_VOSK] = value }
    }

    fun setAllowInterruptions(value: Boolean) {
        _allowInterruptions.value = value
        write { it[KEY_ALLOW_INTERRUPTIONS] = value }
    }

    fun setUseSystemTts(value: Boolean) {
        _useSystemTts.value = value
        write { it[KEY_USE_SYSTEM_TTS] = value }
    }

    fun setUseExternalTts(value: Boolean) {
        _useExternalTts.value = value
        write { it[KEY_USE_EXTERNAL_TTS] = value }
    }

    fun setExternalTtsVoice(value: String) {
        _externalTtsVoice.value = value
        write { it[KEY_EXTERNAL_TTS_VOICE] = value }
    }

    /**
     * Sets the external speech server URL independently of the inference server
     * ([setOpenaiAPI]): the two may point at different hosts, and changing one must not
     * reset the other or the selected inference model.
     */
    fun setExternalTtsAPI(value: String) {
        _externalTtsAPI.value = value
        write { it[KEY_EXTERNAL_TTS_API] = value }
    }

    /** Sets the external speech server token independently of [KEY_OPENAI_KEY]. */
    fun setExternalTtsKey(value: String) {
        _externalTtsKey.value = value
        write { it[KEY_EXTERNAL_TTS_KEY] = value }
    }

    fun setStreamingMode(value: Boolean) {
        _streamingMode.value = value
        write { it[KEY_STREAMING_MODE] = value }
    }

    fun setTemperature(value: String) {
        _temperature.value = value
        write { it[KEY_TEMPERATURE] = value }
    }

    fun setTopP(value: String) {
        _topP.value = value
        write { it[KEY_TOP_P] = value }
    }

    fun setN(value: String) {
        _n.value = value
        write { it[KEY_N] = value }
    }

    fun setMaxCompletionTokens(value: String) {
        _maxCompletionTokens.value = value
        write { it[KEY_MAX_COMPLETION_TOKENS] = value }
    }

    fun setPresencePenalty(value: String) {
        _presencePenalty.value = value
        write { it[KEY_PRESENCE_PENALTY] = value }
    }

    fun setFrequencyPenalty(value: String) {
        _frequencyPenalty.value = value
        write { it[KEY_FREQUENCY_PENALTY] = value }
    }

    fun setReasoningEffort(value: String) {
        _reasoningEffort.value = value
        write { it[KEY_REASONING_EFFORT] = value }
    }

    fun setHasSeenNotificationPrompt(value: Boolean) {
        _hasSeenNotificationPrompt.value = value
        write { it[KEY_HAS_SEEN_NOTIFICATION_PROMPT] = value }
    }

    fun setHasAcceptedDataSharing(value: Boolean) {
        _hasAcceptedDataSharing.value = value
        write { it[KEY_HAS_ACCEPTED_DATA_SHARING] = value }
    }

    fun setHasAcceptedMultipleAttachments(value: Boolean) {
        _hasAcceptedMultipleAttachments.value = value
        write { it[KEY_HAS_ACCEPTED_MULTIPLE_ATTACHMENTS] = value }
    }

    fun setUseOnDevice(value: Boolean) {
        val changed = value != _useOnDevice.value
        _useOnDevice.value = value
        write { it[KEY_USE_LITERT] = value }
        // The "Messages to process" option sets differ per engine: "Unlimited" (100000)
        // exists only for the remote endpoint, so an engine switch settles on the shared
        // 50 default instead of carrying a value the other engine's picker cannot show.
        if (changed) setMessageLimit(50)
    }

    fun setLitertModel(value: String) {
        _litertModel.value = value
        write { it[KEY_LITERT_MODEL] = value }
    }

    /**
     * Selects [modelId] **and** fills the sampler settings with that checkpoint's own
     * defaults, the way the Google AI Edge Gallery does on selection.
     *
     * Blank fields already fall back to these at generate time, so this changes nothing
     * about what runs — it changes what the user can *see*. An empty Advanced screen
     * hides the values actually in force and gives no starting point to adjust from.
     *
     * Every model is written, not merged: the samplers belong to the checkpoint (topK 64
     * means something different to a 4 000-token model than to a 32 000-token one), so
     * switching models replaces them rather than carrying the old model's tuning over.
     */
    fun selectLitertModel(modelId: String) {
        setLitertModel(modelId)
        val model = LitertModels.get(modelId) ?: return
        setLitertTopK(model.defaultTopK.toString())
        setLitertTopP(model.defaultTopP.toString())
        setLitertTemperature(model.defaultTemperature.toString())
        setLitertMaxTokens(model.defaultMaxTokens.toString())
        setLitertAccelerator(model.defaultAccelerator)
    }

    fun setLitertTopK(value: String) {
        _litertTopK.value = value
        write { it[KEY_LITERT_TOP_K] = value }
    }

    fun setLitertTopP(value: String) {
        _litertTopP.value = value
        write { it[KEY_LITERT_TOP_P] = value }
    }

    fun setLitertTemperature(value: String) {
        _litertTemperature.value = value
        write { it[KEY_LITERT_TEMPERATURE] = value }
    }

    fun setLitertMaxTokens(value: String) {
        _litertMaxTokens.value = value
        write { it[KEY_LITERT_MAX_TOKENS] = value }
    }

    fun setHuggingFaceToken(value: String) {
        _huggingFaceToken.value = value.trim()
        write { it[KEY_HUGGING_FACE_TOKEN] = value.trim() }
    }

    fun setLitertAccelerator(value: String) {
        _litertAccelerator.value = value
        write { it[KEY_LITERT_ACCELERATOR] = value }
    }

    fun setLitertEnableThinking(value: Boolean) {
        _litertEnableThinking.value = value
        write { it[KEY_LITERT_ENABLE_THINKING] = value }
    }

    /** Adds a user-supplied model, replacing any earlier entry with the same id. */
    fun addCustomLitertModel(model: LitertModel) {
        setCustomLitertModels(_customLitertModels.value.filterNot { it.id == model.id } + model)
    }

    /** Forgets a user-supplied model. Deleting its files is the caller's job. */
    fun removeCustomLitertModel(id: String) {
        setCustomLitertModels(_customLitertModels.value.filterNot { it.id == id })
    }

    private fun setCustomLitertModels(models: List<LitertModel>) {
        _customLitertModels.value = models
        LitertModels.setCustom(models)
        val encoded = encodeCustomLitertModels(models)
        write { it[KEY_CUSTOM_LITERT_MODELS] = encoded }
    }

    /** True once the user has accepted [modelId]'s license on the model screen. */
    fun hasAcceptedLicense(modelId: String): Boolean =
        _litertAcceptedLicenses.value.split(',').any { it.trim() == modelId }

    fun acceptLicense(modelId: String) {
        if (hasAcceptedLicense(modelId)) return
        val updated = (_litertAcceptedLicenses.value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() } + modelId)
            .joinToString(",")
        _litertAcceptedLicenses.value = updated
        write { it[KEY_LITERT_ACCEPTED_LICENSES] = updated }
    }

    val hasValidModel: Boolean
        get() = if (_useOnDevice.value) {
            _litertModel.value.isNotEmpty()
        } else {
            _openaiAPI.value.isNotEmpty() && _selectedModel.value.isNotEmpty()
        }

    /**
     * Wipes every persisted preference and returns the in-memory state to its
     * defaults.
     *
     * Suspends until the store is actually cleared **before** touching
     * [hasCompletedOnboarding]: flipping that flag sends the UI straight back
     * to onboarding, which immediately writes settings of its own. If the clear
     * were still in flight it could land afterwards and wipe them — the app
     * would look reset-and-reconfigured until the next launch, when the stale
     * cleared state reappeared.
     */
    suspend fun reset() {
        writeMutex.withLock { dataStore.edit { it.clear() } }
        _hasCompletedOnboarding.value = false
        _selectedModel.value = ""
        _openaiAPI.value = ""
        _openaiKey.value = ""
        _language.value = "en"
        _voice.value = ""
        _enableVision.value = false
        _embedTime.value = false
        _messageLimit.value = 50
        _requireOnDeviceVoice.value = false
        _keepLastAttachment.value = false
        _useVosk.value = false
        _allowInterruptions.value = false
        _useSystemTts.value = false
        _useExternalTts.value = false
        _externalTtsVoice.value = ""
        _externalTtsAPI.value = ""
        _externalTtsKey.value = ""
        _streamingMode.value = true
        _temperature.value = ""
        _topP.value = ""
        _n.value = ""
        _maxCompletionTokens.value = ""
        _presencePenalty.value = ""
        _frequencyPenalty.value = ""
        _reasoningEffort.value = ""
        _hasSeenNotificationPrompt.value = false
        _hasAcceptedDataSharing.value = false
        _hasAcceptedMultipleAttachments.value = false
        _useOnDevice.value = false
        _litertModel.value = ""
        _litertTopK.value = ""
        _litertTopP.value = ""
        _litertTemperature.value = ""
        _litertMaxTokens.value = ""
        _litertAccelerator.value = ""
        _litertEnableThinking.value = false
        setCustomLitertModels(emptyList())
        // Built-in model files are deliberately NOT deleted here — reset clears
        // preferences, and silently discarding a 2.6 GB download would be a much
        // bigger action than the button promises. They stay listed and stay usable.
        // The license acceptance is reset with everything else, so it is asked for
        // again before the next download.
        //
        // Custom models are the exception, and the caller deletes their files just
        // before calling this: clearing the list below is what makes them
        // unreachable, so keeping the bytes would strand them.
        _litertAcceptedLicenses.value = ""
        _huggingFaceToken.value = ""
    }
}
