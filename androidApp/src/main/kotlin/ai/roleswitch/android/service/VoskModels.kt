//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

/**
 * A Vosk STT model entry. Models with [bundled] = true ship inside
 * `androidApp/src/main/assets/<dirName>/`; the rest are downloaded on demand
 * from [url] and extracted to `filesDir/<dirName>/` by the phone app.
 *
 * [locale] is the BCP-47 tag used by `SettingsManager.localeForSTT` (e.g.
 * "fr-FR"). [crc32] and [sizeBytes] describe the **zip archive** at [url]
 * (not the extracted tree) and are used to verify the download before
 * extraction — both are computed over the raw bytes fetched from [url].
 */
data class VoskModel(
    val locale: String,
    val dirName: String,
    val url: String,
    val sizeBytes: Long,
    val crc32: Long,
    val bundled: Boolean
)

/**
 * Catalog of all Vosk models known to the app, keyed by BCP-47 locale tag.
 * Consumed by `VoskService` (model loading + readiness check) and by the
 * Settings UI (toggle / download flow).
 *
 * Only en-US is bundled: it ships in the APK and is lazily copied from assets
 * on first use, so it is always ready. Every other locale must be downloaded
 * and verified (CRC32 over the zip bytes) before it can be enabled. There is
 * no build-time dependency on the zip files for downloadable entries; only the
 * metadata lives here.
 *
 * ru-RU used to be bundled too. It was moved to the downloadable set before the
 * first Play release: its extracted tree is 87 MB, which cost ~44 MB of
 * compressed download for *every* user regardless of language — 42% of the
 * app's total download size for a model most of them never load.
 */
object VoskModels {
    val registry: Map<String, VoskModel> = mapOf(
        "en-US" to VoskModel(
            locale = "en-US",
            dirName = "vosk-model-small-en-us-0.15",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            sizeBytes = 41_205_931L,
            crc32 = 4_241_006_371L,
            bundled = true
        ),
        "ru-RU" to VoskModel(
            locale = "ru-RU",
            dirName = "vosk-model-small-ru-0.22",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
            sizeBytes = 46_236_750L,
            crc32 = 533_439_964L,
            bundled = false
        ),
        "fr-FR" to VoskModel(
            locale = "fr-FR",
            dirName = "vosk-model-small-fr-0.22",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
            sizeBytes = 42_233_323L,
            crc32 = 3_046_039_107L,
            bundled = false
        ),
        "de-DE" to VoskModel(
            locale = "de-DE",
            dirName = "vosk-model-small-de-0.15",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip",
            sizeBytes = 46_499_967L,
            crc32 = 383_877_854L,
            bundled = false
        ),
        "es-ES" to VoskModel(
            locale = "es-ES",
            dirName = "vosk-model-small-es-0.42",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip",
            sizeBytes = 39_817_833L,
            crc32 = 2_872_558_954L,
            bundled = false
        ),
        "pt-PT" to VoskModel(
            locale = "pt-PT",
            dirName = "vosk-model-small-pt-0.3",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip",
            sizeBytes = 32_453_112L,
            crc32 = 2_980_912_597L,
            bundled = false
        )
    )

    /** Look up the model for a BCP-47 locale tag, or null if no entry. */
    fun get(locale: String): VoskModel? = registry[locale]
}
