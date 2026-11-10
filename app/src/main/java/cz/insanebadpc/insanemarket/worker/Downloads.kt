package cz.insanebadpc.insanemarket.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cz.insanebadpc.insanemarket.R
import cz.insanebadpc.insanemarket.model.AppEntry
import cz.insanebadpc.insanemarket.ui.MainActivity
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object Downloads {
    const val TAG_ALL = "insanemarket-download"
    const val EXTRA_INSTALL_REPO = "cz.insanebadpc.insanemarket.INSTALL_REPO"

    const val KEY_REPO = "repo"
    const val KEY_NAME = "name"
    const val KEY_URL = "url"
    const val KEY_FILE = "file"
    const val KEY_TAG = "tag"
    const val KEY_DIGEST = "digest"
    const val KEY_PROGRESS = "progress"
    const val KEY_PATH = "path"
    const val KEY_PACKAGE = "package"
    const val KEY_VERSION_CODE = "versionCode"
    const val KEY_VERSION_NAME = "versionName"
    const val KEY_ERROR = "error"

    private const val ATTEMPT_PREFIX = "attempt:"
    private const val CHANNEL_PROGRESS = "download_progress"
    private const val CHANNEL_READY = "download_ready"

    fun uniqueName(repoFullName: String): String = "download:$repoFullName"

    fun repoTag(repoFullName: String): String = "repo:$repoFullName"

    fun repoFromTags(tags: Set<String>): String? =
        tags.firstOrNull { it.startsWith("repo:") }?.removePrefix("repo:")

    fun attemptFromTags(tags: Set<String>): Long =
        tags.firstOrNull { it.startsWith(ATTEMPT_PREFIX) }
            ?.removePrefix(ATTEMPT_PREFIX)
            ?.toLongOrNull()
            ?: 0L

    fun enqueue(context: Context, entry: AppEntry): UUID {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_REPO, entry.repoFullName)
                    .putString(KEY_NAME, entry.displayName)
                    .putString(KEY_URL, entry.apkUrl)
                    .putString(KEY_FILE, fileName(entry))
                    .putString(KEY_TAG, entry.latestTag)
                    .putString(KEY_DIGEST, entry.assetDigest)
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_ALL)
            .addTag(repoTag(entry.repoFullName))
            .addTag(ATTEMPT_PREFIX + System.currentTimeMillis())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(entry.repoFullName),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return request.id
    }

    fun cancel(context: Context, repoFullName: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(repoFullName))
    }

    fun downloadsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads").apply { mkdirs() }

    fun fileName(entry: AppEntry): String = listOf(
        sanitize(entry.repoName),
        sanitize(entry.latestTag ?: "latest"),
        sanitize(entry.assetName ?: "app.apk"),
    ).joinToString("-")

    fun sanitize(value: String): String =
        value.replace("[^A-Za-z0-9._-]".toRegex(), "_").ifBlank { "app" }

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_READY,
                context.getString(R.string.channel_ready),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    fun progressNotification(context: Context, workId: UUID, name: String, progress: Int): Notification {
        createChannels(context)
        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(name)
            .setContentText(
                if (progress < 0) context.getString(R.string.notification_downloading)
                else context.getString(R.string.notification_progress, progress)
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, progress.coerceIn(0, 100), progress < 0)
            .setContentIntent(openAppIntent(context))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.cancel),
                cancelIntent,
            )
        return builder.build()
    }

    fun readyNotification(
        context: Context,
        repoFullName: String,
        name: String,
        packageName: String,
        versionName: String?,
    ): Notification {
        createChannels(context)
        val text = if (versionName.isNullOrBlank()) {
            context.getString(R.string.notification_ready_text)
        } else {
            context.getString(R.string.notification_ready_text_version, versionName)
        }
        return NotificationCompat.Builder(context, CHANNEL_READY)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.notification_ready_title, name))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(installIntent(context, repoFullName, packageName))
            .build()
    }

    fun failedNotification(context: Context, name: String, message: String): Notification {
        createChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_READY)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.notification_failed_title, name))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
    }

    fun notificationId(repoFullName: String): Int = 1000 + (repoFullName.hashCode() and 0x7fffffff) % 100000

    fun resultNotificationId(repoFullName: String): Int = notificationId(repoFullName) + 200000

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun installIntent(context: Context, repoFullName: String, packageName: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_INSTALL_REPO, repoFullName)
        return PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
