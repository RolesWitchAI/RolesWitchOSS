//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import java.io.File
import java.util.UUID
import kotlin.math.ceil

/**
 * On-disk location of downloaded LiteRT-LM models, shared by [LiteRtModelDownloader]
 * (which writes them) and [LiteRtService] (which loads them).
 *
 * Models live under internal storage — `filesDir/litert/<id>/<commitHash>/` — the same
 * place Vosk models go. Internal storage is not user-deletable through a file manager
 * and never returns null, unlike `getExternalFilesDir`.
 *
 * The per-model **directory** matters: `EngineConfig.cacheDir` is left null, so LiteRT-LM
 * writes its compiled-weights cache next to the model file. Deleting a model therefore
 * means removing the directory, and the free-space budget must cover more than the
 * download itself.
 */
object LitertModelStore {

    /** Extra headroom beyond the model size, for LiteRT's compiled-weights cache. */
    private const val CACHE_HEADROOM_FACTOR = 0.5

    fun dirFor(context: Context, model: LitertModel): File =
        File(context.filesDir, model.relativeDir)

    fun fileFor(context: Context, model: LitertModel): File =
        File(context.filesDir, model.relativePath)

    /** The partial file a resumable download appends to. */
    fun partFileFor(context: Context, model: LitertModel): File =
        File(dirFor(context, model), "${model.fileName}.part")

    /**
     * True when the model is present and complete. The size check is what makes a
     * half-written file from a killed process read as "not downloaded" rather than
     * being handed to the engine.
     */
    fun isDownloaded(context: Context, model: LitertModel): Boolean {
        val file = fileFor(context, model)
        return file.isFile && file.length() == model.sizeBytes
    }

    /** Removes the model and its compiled cache. Returns true if anything was deleted. */
    fun delete(context: Context, model: LitertModel): Boolean =
        dirFor(context, model).deleteRecursively()

    /** Bytes still needed to fit [model] plus its cache, or 0 if there is room. */
    fun missingSpaceBytes(context: Context, model: LitertModel): Long {
        val needed = model.sizeBytes + (model.sizeBytes * CACHE_HEADROOM_FACTOR).toLong() -
            partFileFor(context, model).length()
        val available = allocatableBytes(context)
        return (needed - available).coerceAtLeast(0L)
    }

    /**
     * Space the system is willing to give us, which is more than
     * `File.getUsableSpace` — it includes other apps' evictable caches. minSdk is 31,
     * so this is always available; it falls back to the plain figure if the platform
     * refuses the query.
     */
    private fun allocatableBytes(context: Context): Long {
        val storage = context.getSystemService(StorageManager::class.java) ?: return context.filesDir.usableSpace
        return runCatching {
            val uuid: UUID = storage.getUuidForPath(context.filesDir)
            storage.getAllocatableBytes(uuid)
        }.getOrElse { context.filesDir.usableSpace }
    }

    /**
     * Device RAM in GB as the user would describe it, for the warning shown before
     * downloading a model the phone may not be able to run.
     *
     * `totalMem` is **usable** memory, not installed memory — the kernel and hardware
     * reservations are already subtracted. A Galaxy S23 sold as 8 GB reports 6.9 GiB, so
     * no amount of rounding to the nearest gigabyte can recover "8". Android 14 added
     * `advertisedMem` for exactly this and is the path taken wherever it exists; below
     * that, the usable figure is snapped up to the nearest size phones are actually
     * built in, which is the only way to undo a reservation of that size.
     */
    fun deviceMemoryGb(context: Context): Int {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val gib = 1024.0 * 1024 * 1024
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ceil(info.advertisedMem / gib).toInt()
        }
        val usable = info.totalMem / gib
        return COMMON_MEMORY_SIZES_GB.firstOrNull { it >= usable } ?: ceil(usable).toInt()
    }

    /**
     * RAM configurations Android phones ship in. Used only to recover the advertised
     * size from the usable one on Android 13 and older; anything larger than the last
     * entry falls back to plain rounding.
     */
    private val COMMON_MEMORY_SIZES_GB = listOf(2, 3, 4, 6, 8, 12, 16, 24, 32)
}
