//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import ai.roleswitch.android.MainActivity
import ai.roleswitch.android.R
import kotlin.math.abs

/**
 * Foreground service that keeps the process alive while an LLM response is being
 * streamed or while the microphone is recording. Without this, Android will
 * kill the process (and the in-flight HTTP stream / AudioRecord loop) within a
 * few seconds of the screen being locked or the app being backgrounded.
 *
 * The foreground-service type is selected at runtime:
 *  - recording only      -> [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE] |
 *                           [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK] |
 *                           [ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE]
 *                           (MEDIA_PLAYBACK is included so TTS can play the
 *                           assistant's response immediately while the user is
 *                           in a backgrounded/locked voice call.)
 *  - LLM streaming       -> [ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE]
 *  - recording + LLM     -> both (same as "recording only" above)
 *
 * While streaming, the mandatory foreground-service notification shows a generic
 * "Generating response…" body. When the response completes — and only when the
 * app is in the background — [postCompletionNotification] posts a *separate*
 * messenger-style heads-up notification with the thread title and the response.
 */
class LlmForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isRecording = intent?.getBooleanExtra(EXTRA_RECORDING, false) ?: false
        val isDownloading = intent?.getBooleanExtra(EXTRA_DOWNLOADING, false) ?: false
        val downloadLabel = intent?.getStringExtra(EXTRA_DOWNLOAD_LABEL)
        // -1 means "no measurable progress yet" (connecting, or verifying a finished file).
        val downloadPercent = intent?.getIntExtra(EXTRA_DOWNLOAD_PERCENT, -1) ?: -1
        startForegroundWithNotification(
            buildStreamingNotification(isDownloading, downloadLabel, downloadPercent),
            isRecording
        )
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundWithNotification(
        notification: Notification,
        isRecording: Boolean
    ) {
        // On Android 14+ (API 34) we must pass a runtime foreground-service type
        // that is a subset of the manifest-declared types. Pre-API-34 we use the
        // legacy single-arg form (the manifest type is the default).
        //
        // While recording (voice call), we additionally claim MEDIA_PLAYBACK so
        // the system lets TTS play the assistant's response immediately even
        // when the app is in the background (screen locked). Without it, on
        // Android 14+, audio tagged USAGE_ASSISTANT/USAGE_MEDIA from a non-
        // mediaPlayback FGS gets held until the app is foregrounded again.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (isRecording) {
                type = type or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            // A model download runs under SPECIAL_USE too, not DATA_SYNC. dataSync is
            // the type that nominally describes a transfer, but from Android 15 it is
            // capped at roughly six cumulative hours per day, after which the system
            // calls Service.onTimeout and the app must stop or be killed — a poor fit
            // for a multi-gigabyte download on mobile data. The specialUse subtype
            // declared in the manifest names the download explicitly.
            startForeground(STREAMING_NOTIFICATION_ID, notification, type)
        } else {
            startForeground(STREAMING_NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * The mandatory notification for the foreground service while the LLM is
     * generating a response. Generic body — no thread content is leaked while
     * streaming. The meaningful, messenger-style notification is posted
     * separately on completion via [postCompletionNotification].
     */
    /**
     * The one notification this service posts, in whichever of its two shapes applies.
     *
     * A download is not "generating a response": it runs for many minutes, and the only
     * questions the user has about it are *which model* and *how far*. So it gets the
     * model's name and a progress bar, and [setOnlyAlertOnce] keeps the stream of updates
     * silent after the first post.
     */
    private fun buildStreamingNotification(
        isDownloading: Boolean = false,
        downloadLabel: String? = null,
        downloadPercent: Int = -1
    ): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, STREAMING_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setLocalOnly(true)

        if (isDownloading) {
            val name = downloadLabel.orEmpty()
            builder
                .setContentText(
                    if (downloadPercent < 0) {
                        getString(R.string.notification_verifying, name)
                    } else {
                        getString(R.string.notification_downloading, name)
                    }
                )
                // Indeterminate while connecting or verifying — there is no meaningful
                // fraction to show, and a bar stuck at 0% reads as a stalled transfer.
                .setProgress(100, downloadPercent.coerceAtLeast(0), downloadPercent < 0)
        } else {
            builder.setContentText(getString(R.string.notification_working))
        }

        return builder.build()
    }

    companion object {
        const val EXTRA_RECORDING = "extra_recording"
        const val EXTRA_DOWNLOADING = "extra_downloading"
        const val EXTRA_DOWNLOAD_LABEL = "extra_download_label"
        const val EXTRA_DOWNLOAD_PERCENT = "extra_download_percent"

        private const val STREAMING_CHANNEL_ID = "llm_generation_channel"
        private const val COMPLETION_CHANNEL_ID = "llm_completion_channel"

        private const val STREAMING_NOTIFICATION_ID = 1001
        private const val COMPLETION_NOTIFICATION_BASE = 2000
        private const val MAX_BODY_LEN = 500

        private const val WAKE_LOCK_TAG = "roleswitch:llm_fg_service"
        private const val WAKE_LOCK_TIMEOUT_MS = 10L * 60 * 1000 // 10 min, streaming cap is 600s

        private fun completionIdFor(threadId: String): Int =
            COMPLETION_NOTIFICATION_BASE + abs(threadId.hashCode())

        /**
         * Create the low-importance streaming channel. Idempotent.
         */
        fun createStreamingChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(STREAMING_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    STREAMING_CHANNEL_ID,
                    context.getString(R.string.notification_channel_llm),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_llm_description)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }

        /**
         * Create the high-importance completion channel (heads-up peek). Idempotent.
         */
        fun createCompletionChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(COMPLETION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    COMPLETION_CHANNEL_ID,
                    context.getString(R.string.notification_channel_completion),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_completion_description)
                    setShowBadge(true)
                    enableLights(false)
                    enableVibration(false)
                    lockscreenVisibility = NotificationManager.IMPORTANCE_HIGH
                }
                manager.createNotificationChannel(channel)
            }
        }

        /**
         * Start the foreground service (idempotent: starting an already-running
         * service re-delivers the intent to [onStartCommand]).
         */
        fun start(
            context: Context,
            isRecording: Boolean,
            isDownloading: Boolean = false,
            downloadLabel: String? = null,
            downloadPercent: Int = -1
        ) {
            val intent = Intent(context, LlmForegroundService::class.java).apply {
                putExtra(EXTRA_DOWNLOAD_LABEL, downloadLabel)
                putExtra(EXTRA_DOWNLOAD_PERCENT, downloadPercent)
                putExtra(EXTRA_RECORDING, isRecording)
                putExtra(EXTRA_DOWNLOADING, isDownloading)
            }
            try {
                context.startForegroundService(intent)
            } catch (_: IllegalStateException) {
                // Background-start restrictions (e.g. Android 12+ when app is not
                // in an allowlisted state). The LLM coroutine still runs as long
                // as the process is alive; the service simply won't keep it alive.
            } catch (_: SecurityException) {
                // Same family of restrictions as above on some OEMs.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LlmForegroundService::class.java))
        }

        /**
         * Post a messenger-style heads-up notification for a completed response.
         * Tapping it opens [MainActivity] at the matching thread and dismisses
         * the notification (auto-cancel). Per-thread notification ID so a new
         * response in the same thread replaces the previous one.
         */
        fun postCompletionNotification(
            context: Context,
            threadTitle: String,
            threadId: String,
            content: String
        ) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(COMPLETION_CHANNEL_ID) == null) {
                createCompletionChannel(context)
            }

            val displayTitle = threadTitle.ifBlank { context.getString(R.string.app_name) }
            val displayBody = if (content.length > MAX_BODY_LEN) {
                content.take(MAX_BODY_LEN) + "…"
            } else {
                content
            }

            val deepLinkIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                completionIdFor(threadId),
                deepLinkIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(displayTitle)
                .setContentText(displayBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(displayBody))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColor(0xFF6750A4.toInt())
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setLocalOnly(true)

            manager.notify(completionIdFor(threadId), builder.build())
        }
    }
}
