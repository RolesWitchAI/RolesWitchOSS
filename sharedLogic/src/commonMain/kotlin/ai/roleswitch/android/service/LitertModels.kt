//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import kotlin.concurrent.Volatile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * An on-device LiteRT-LM model. Downloaded on demand from HuggingFace to
 * app-specific external storage and run by `LiteRtService` (Android only).
 *
 * Lives in `commonMain` rather than next to the Android service because
 * [ai.roleswitch.android.settings.EffectiveSettings] reads [supportsImage] to
 * decide whether a thread may attach images — the vision capability of the
 * selected model *is* the vision setting in on-device mode.
 *
 * [sizeBytes] and [sha256] come from the repo's git-LFS pointer
 * (`huggingface.co/<repo>/raw/<commit>/<file>`) and are pinned here the same way
 * [VoskModels] pins a CRC32 — verifying against a value baked into the app is the
 * point; an `ETag` served by the same request we are trying to validate is not.
 *
 * [sha256] is null for [gated] models: their pointer file is itself behind the
 * authentication wall, so there is no value to pin at build time. Those downloads are
 * checked on size alone, and a corrupt one surfaces as a failed `Engine.initialize()`
 * instead. Fill it in for any model whose pointer becomes readable.
 */
data class LitertModel(
    val id: String,
    val displayName: String,
    /** HuggingFace repo, e.g. `litert-community/gemma-4-E2B-it-litert-lm`. */
    val repoId: String,
    val fileName: String,
    /** Pinned commit, so an upstream re-upload can never change what we fetch. */
    val commitHash: String,
    val sizeBytes: Long,
    /** Lowercase hex SHA-256 from the repo's git-LFS pointer; null when [gated]. */
    val sha256: String?,
    /** Advisory minimum device RAM; below this the user is warned, not blocked. */
    val minDeviceMemoryGb: Int,
    val supportsImage: Boolean,
    /**
     * Whether the checkpoint has a thinking mode that can be switched on per turn via
     * the runtime's `enable_thinking` flag.
     *
     * Unlike an OpenAI `reasoning_effort` this is a plain on/off — there are no levels.
     * Off by default: thinking spends prefill and output tokens before the user sees a
     * word, which is felt much more sharply on a phone than against a server.
     *
     * Taken from the Google AI Edge Gallery allowlist's `llm_thinking` capability.
     */
    val supportsThinking: Boolean,
    /**
     * Whether HuggingFace required an access token to download this model when the app
     * was built. Documentation only — it is *not* what gates the UI.
     *
     * A repository can be gated or opened up at any time without an app release, so the
     * download is attempted regardless and a 401/403 is what raises the token prompt
     * (see `LiteRtModelDownloader.State.Failed.needsToken`). This still records why
     * [sha256] is null for these models: a gated repo hides its LFS pointer too.
     */
    val gated: Boolean,
    val licenseName: String,
    val licenseUrl: String,
    val defaultTopK: Int,
    val defaultTopP: Float,
    val defaultTemperature: Float,
    val defaultMaxTokens: Int,
    /**
     * Hard ceiling for `maxNumTokens`, from the checkpoint's own context window; null when
     * the checkpoint publishes none and [defaultMaxTokens] is the only sane value.
     *
     * Enforced when the user edits Max tokens: past this the engine either refuses to
     * build or is killed for memory, and neither failure names the real cause. The Google
     * AI Edge Gallery uses the same number as the upper bound of its slider.
     */
    val maxContextLength: Int?,
    /**
     * `"gpu"` or `"cpu"` — the text backend.
     *
     * CPU by default, deliberately: the GPU path needs a working OpenCL driver, and on
     * the phones that lack one the engine fails to build outright. Slower-but-loads beats
     * faster-but-broken as a default; the Advanced screen offers GPU as an opt-in.
     */
    val defaultAccelerator: String,
    /** Vision tower backend; must be `"gpu"` for the Gemma 3n/4 family. */
    val visionAccelerator: String
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repoId/resolve/$commitHash/$fileName?download=true"

    /**
     * Directory holding this model, relative to the app's internal files dir.
     *
     * A directory per model, not a bare file: LiteRT-LM writes its compiled-weights
     * cache **next to the model file** (we pass `cacheDir = null`), so deleting a
     * model means removing this whole directory, and the free-space budget must
     * allow for more than [sizeBytes].
     */
    val relativeDir: String
        get() = "litert/$id/$commitHash"

    val relativePath: String
        get() = "$relativeDir/$fileName"

    /**
     * True for a model the user added by URL rather than one shipped in [LitertModels].
     *
     * Custom models are the only ones that can be *removed* from the list — a built-in
     * whose file is deleted stays on screen, ready to download again, but a custom one
     * has nowhere to come back from.
     */
    val isCustom: Boolean
        get() = id.startsWith(CUSTOM_ID_PREFIX)
}

/** Marks an id as belonging to a user-added model. See [LitertModel.isCustom]. */
const val CUSTOM_ID_PREFIX = "custom:"

/**
 * Serialises user-added models for [ai.roleswitch.android.settings.SettingsManager].
 *
 * Hand-built rather than `@Serializable`: the serialization *runtime* is on the
 * classpath but the compiler plugin is not applied to this module, and adding one to a
 * KMP module that also feeds an iOS XCFramework is a bigger change than one preference
 * is worth.
 */
fun encodeCustomLitertModels(models: List<LitertModel>): String = buildJsonArray {
    models.forEach { model ->
        addJsonObject {
            put("id", model.id)
            put("displayName", model.displayName)
            put("repoId", model.repoId)
            put("fileName", model.fileName)
            put("commitHash", model.commitHash)
            put("sizeBytes", model.sizeBytes)
            put("minDeviceMemoryGb", model.minDeviceMemoryGb)
            put("supportsImage", model.supportsImage)
            put("supportsThinking", model.supportsThinking)
            put("gated", model.gated)
            put("licenseName", model.licenseName)
            put("licenseUrl", model.licenseUrl)
        }
    }
}.toString()

/** Parses [encodeCustomLitertModels]. Anything malformed yields an empty list rather than throwing. */
fun decodeCustomLitertModels(raw: String): List<LitertModel> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        Json.parseToJsonElement(raw).jsonArray.map { element ->
            val o = element.jsonObject
            fun str(key: String) = o[key]?.jsonPrimitive?.content.orEmpty()
            LitertModel(
                id = str("id"),
                displayName = str("displayName"),
                repoId = str("repoId"),
                fileName = str("fileName"),
                commitHash = str("commitHash"),
                sizeBytes = o["sizeBytes"]?.jsonPrimitive?.long ?: 0L,
                // Never pinned for a user-added model: the hash would have to come from
                // the same repository being verified, which verifies nothing.
                sha256 = null,
                minDeviceMemoryGb = o["minDeviceMemoryGb"]?.jsonPrimitive?.int ?: 8,
                supportsImage = o["supportsImage"]?.jsonPrimitive?.booleanOrNull ?: false,
                // Defaults to true, matching the resolver: a checkpoint with no thinking
                // mode ignores the flag, so offering it costs nothing, while withholding
                // it hides the feature on a derivative that does support one.
                supportsThinking = o["supportsThinking"]?.jsonPrimitive?.booleanOrNull ?: true,
                gated = o["gated"]?.jsonPrimitive?.booleanOrNull ?: false,
                licenseName = str("licenseName"),
                licenseUrl = str("licenseUrl"),
                // The checkpoint's own values are not published anywhere we can read, so
                // these are the family defaults. The Advanced screen can change them.
                defaultTopK = 64,
                defaultTopP = 0.95f,
                defaultTemperature = 1.0f,
                defaultMaxTokens = 4096,
                maxContextLength = null,
                defaultAccelerator = "cpu",
                visionAccelerator = "gpu"
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * Catalog of on-device models known to the app, mirroring [VoskModels] in shape:
 * a static list, updated by app release rather than fetched at runtime.
 *
 * Models marked [LitertModel.gated] need a HuggingFace access token (Settings →
 * Intelligence → HuggingFace token) *and* an account that has accepted the model's
 * terms on huggingface.co; without both, the download returns 401/403.
 *
 * Metadata is taken from the Google AI Edge Gallery's model allowlist
 * (`model_allowlists/1_0_18.json`, Apache-2.0).
 */
object LitertModels {
    /** Models shipped with the app. See [all] for these plus the user's own. */
    val registry: List<LitertModel> = listOf(
        LitertModel(
            id = "gemma-4-E2B-it",
            displayName = "Gemma 4 E2B",
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it.litertlm",
            commitHash = "6e5c4f1e395deb959c494953478fa5cec4b8008f",
            sizeBytes = 2_588_147_712L,
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            minDeviceMemoryGb = 4,
            supportsImage = true,
            supportsThinking = true,
            gated = false,
            licenseName = "Gemma Terms of Use",
            licenseUrl = "https://ai.google.dev/gemma/terms",
            defaultTopK = 64,
            defaultTopP = 0.95f,
            defaultTemperature = 1.0f,
            defaultMaxTokens = 4000,
            maxContextLength = 32000,
            defaultAccelerator = "cpu",
            visionAccelerator = "gpu"
        ),
        LitertModel(
            id = "gemma-4-E4B-it",
            displayName = "Gemma 4 E4B",
            repoId = "litert-community/gemma-4-E4B-it-litert-lm",
            fileName = "gemma-4-E4B-it.litertlm",
            commitHash = "28299f30ee4d43294517a4ac93abd6163412f07f",
            sizeBytes = 3_659_530_240L,
            sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            minDeviceMemoryGb = 6,
            supportsImage = true,
            supportsThinking = true,
            gated = false,
            licenseName = "Gemma Terms of Use",
            licenseUrl = "https://ai.google.dev/gemma/terms",
            defaultTopK = 64,
            defaultTopP = 0.95f,
            defaultTemperature = 1.0f,
            defaultMaxTokens = 4000,
            maxContextLength = 32000,
            defaultAccelerator = "cpu",
            visionAccelerator = "gpu"
        ),
        LitertModel(
            id = "gemma-3n-E2B-it",
            displayName = "Gemma 3n E2B",
            repoId = "google/gemma-3n-E2B-it-litert-lm",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            commitHash = "ba9ca88da013b537b6ed38108be609b8db1c3a16",
            sizeBytes = 3_655_827_456L,
            sha256 = null,
            minDeviceMemoryGb = 6,
            supportsImage = true,
            supportsThinking = false,
            gated = true,
            licenseName = "Gemma Terms of Use",
            licenseUrl = "https://ai.google.dev/gemma/terms",
            defaultTopK = 64,
            defaultTopP = 0.95f,
            defaultTemperature = 1.0f,
            defaultMaxTokens = 4096,
            maxContextLength = null,
            defaultAccelerator = "cpu",
            visionAccelerator = "gpu"
        ),
        LitertModel(
            id = "gemma-3n-E4B-it",
            displayName = "Gemma 3n E4B",
            repoId = "google/gemma-3n-E4B-it-litert-lm",
            fileName = "gemma-3n-E4B-it-int4.litertlm",
            commitHash = "297ed75955702dec3503e00c2c2ecbbf475300bc",
            sizeBytes = 4_919_541_760L,
            sha256 = null,
            minDeviceMemoryGb = 8,
            supportsImage = true,
            supportsThinking = false,
            gated = true,
            licenseName = "Gemma Terms of Use",
            licenseUrl = "https://ai.google.dev/gemma/terms",
            defaultTopK = 64,
            defaultTopP = 0.95f,
            defaultTemperature = 1.0f,
            defaultMaxTokens = 4096,
            maxContextLength = null,
            defaultAccelerator = "cpu",
            visionAccelerator = "gpu"
        )
    )

    @Volatile
    private var customModels: List<LitertModel> = emptyList()

    /**
     * Every model the app can run: the built-in [registry] plus the ones the user added
     * by URL.
     *
     * Held here rather than read from settings because [get] is called from
     * `EffectiveSettings`, which is a pure function over a thread row and a
     * `SettingsManager` — it cannot suspend to load anything. `SettingsManager` pushes the
     * list in on startup and on every change.
     */
    val all: List<LitertModel> get() = registry + customModels

    /** Replaces the user-added models. Called by `SettingsManager`; not for general use. */
    fun setCustom(models: List<LitertModel>) {
        customModels = models
    }

    /** Look up a model by [LitertModel.id], or null if it is in neither list. */
    fun get(id: String): LitertModel? = all.firstOrNull { it.id == id }
}
