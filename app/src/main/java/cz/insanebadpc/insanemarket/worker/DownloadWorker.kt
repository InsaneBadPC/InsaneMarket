package cz.insanebadpc.insanemarket.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cz.insanebadpc.insanemarket.BuildConfig
import cz.insanebadpc.insanemarket.data.AppStateStore
import cz.insanebadpc.insanemarket.util.ApkInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

private const val HTTP_RANGE_NOT_SATISFIABLE = 416

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = inputData.getString(Downloads.KEY_REPO) ?: return Result.failure()
        val name = inputData.getString(Downloads.KEY_NAME) ?: repo
        val url = inputData.getString(Downloads.KEY_URL) ?: return Result.failure()
        val fileName = inputData.getString(Downloads.KEY_FILE) ?: return Result.failure()
        val tag = inputData.getString(Downloads.KEY_TAG)
        val expectedDigest = inputData.getString(Downloads.KEY_DIGEST)
        val progressId = Downloads.notificationId(repo)
        val resultId = Downloads.resultNotificationId(repo)

        Downloads.createChannels(applicationContext)
        showForeground(progressId, name)

        val dir = Downloads.downloadsDir(applicationContext)
        val finalFile = File(dir, fileName)
        val partFile = File(dir, "$fileName.part")
        cleanupOldFiles(dir, Downloads.sanitize(repo.substringAfterLast('/')) + "-", finalFile, partFile)

        var lastNotified = -1
        return try {
            val file = withContext(Dispatchers.IO) {
                download(url, finalFile, partFile, expectedDigest) { progress ->
                    setProgress(workDataOf(Downloads.KEY_PROGRESS to progress))
                    if (progress == 100 || lastNotified < 0 || progress - lastNotified >= 5) {
                        lastNotified = progress
                        notify(
                            progressId,
                            Downloads.progressNotification(applicationContext, id, name, progress),
                        )
                    }
                }
            }

            val info = ApkInstaller.infoFromApk(applicationContext, file)
                ?: throw IllegalStateException("Stažený soubor není platné APK.")
            val state = AppStateStore(applicationContext)
            state.setPackageForRepo(repo, info.packageName)
            state.setPackageOwner(info.packageName, repo)
            state.setKnownRelease(repo, tag, info.versionCode)
            state.setDownloadedApk(repo, file.absolutePath, tag, info.versionCode, info.packageName)

            NotificationManagerCompat.from(applicationContext).cancel(progressId)
            notify(
                resultId,
                Downloads.readyNotification(
                    applicationContext,
                    repo,
                    name,
                    info.packageName,
                    info.versionName,
                ),
            )
            Result.success(
                workDataOf(
                    Downloads.KEY_PATH to file.absolutePath,
                    Downloads.KEY_PACKAGE to info.packageName,
                    Downloads.KEY_VERSION_CODE to info.versionCode,
                    Downloads.KEY_VERSION_NAME to info.versionName,
                )
            )
        } catch (e: CancellationException) {
            NotificationManagerCompat.from(applicationContext).cancel(progressId)
            throw e
        } catch (e: Exception) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: "Stažení selhalo."
            NotificationManagerCompat.from(applicationContext).cancel(progressId)
            notify(resultId, Downloads.failedNotification(applicationContext, name, message))
            Result.failure(workDataOf(Downloads.KEY_ERROR to message))
        }
    }

    private suspend fun showForeground(notificationId: Int, name: String) {
        val notification = Downloads.progressNotification(applicationContext, id, name, -1)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
        try {
            setForeground(info)
        } catch (_: Exception) {
            // Některá zařízení nedovolí foreground službu z pozadí; stahování
            // může pokračovat, jen nebude vidět v oznámení.
        }
    }

    private suspend fun download(
        url: String,
        finalFile: File,
        partFile: File,
        expectedDigest: String?,
        onProgress: suspend (Int) -> Unit,
    ): File {
        if (finalFile.exists()) {
            if (!expectedDigest.isNullOrBlank() && matchesDigest(finalFile, expectedDigest)) {
                onProgress(100)
                return finalFile
            }
            finalFile.delete()
        }

        val existing = if (partFile.exists()) partFile.length() else 0L
        var connection = openFollowing(url, existing)
        var code = connection.responseCode
        if (existing > 0L && code == HTTP_RANGE_NOT_SATISFIABLE) {
            connection.disconnect()
            partFile.delete()
            connection = openFollowing(url, 0L)
            code = connection.responseCode
        }

        var effectiveExisting = existing
        if (code == HttpURLConnection.HTTP_OK && effectiveExisting > 0L) {
            effectiveExisting = 0L
            partFile.delete()
        }
        if (code !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("HTTP $code při stahování APK.")
        }

        val append = code == HttpURLConnection.HTTP_PARTIAL && effectiveExisting > 0L
        val contentLength = connection.contentLengthLong
        val total = if (contentLength > 0L) {
            (if (append) effectiveExisting else 0L) + contentLength
        } else {
            -1L
        }
        var done = if (append) partFile.length() else 0L

        try {
            connection.inputStream.use { input ->
                FileOutputStream(partFile, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastPercent = -1
                    while (true) {
                        if (isStopped) throw CancellationException("Stahování zrušeno.")
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0L) {
                            val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }

        if (total > 0L && partFile.length() != total) {
            throw IllegalStateException("Stažení je neúplné (${partFile.length()} z $total B).")
        }
        if (!expectedDigest.isNullOrBlank() && !matchesDigest(partFile, expectedDigest)) {
            partFile.delete()
            throw IllegalStateException("Kontrolní součet staženého APK nesouhlasí.")
        }
        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
        onProgress(100)
        return finalFile
    }

    private fun openFollowing(url: String, offset: Long, depth: Int = 0): HttpURLConnection {
        val connection = open(url, offset)
        val code = connection.responseCode
        if (code in 301..308 && depth < 5) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (!location.isNullOrBlank()) {
                return openFollowing(URL(URL(url), location).toString(), offset, depth + 1)
            }
        }
        return connection
    }

    private fun open(url: String, offset: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "InsaneMarket/${BuildConfig.VERSION_NAME}")
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }

    private fun matchesDigest(file: File, expected: String): Boolean {
        val parts = expected.split(":", limit = 2)
        if (parts.size != 2 || !parts[0].equals("sha256", ignoreCase = true)) return true
        return sha256(file).equals(parts[1], ignoreCase = true)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    private fun cleanupOldFiles(dir: File, prefix: String, finalFile: File, partFile: File) {
        dir.listFiles()?.forEach { file ->
            if (file == finalFile || file == partFile) return@forEach
            if (file.name.startsWith(prefix) && (file.name.endsWith(".apk") || file.name.endsWith(".part"))) {
                file.delete()
            }
        }
    }

    private fun notify(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(applicationContext).notify(id, notification)
        } catch (_: SecurityException) {
            // Uživatel nepovolil oznámení; stahování tím netrpí.
        }
    }
}
