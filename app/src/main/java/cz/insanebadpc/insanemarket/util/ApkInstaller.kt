package cz.insanebadpc.insanemarket.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ApkInstaller {
    fun download(context: Context, url: String, fileName: String, token: String?, onProgress: (Int) -> Unit = {}): File {
        val dir = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }
        val out = File(dir, fileName.replace("[^A-Za-z0-9._-]".toRegex(), "_"))
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "InsaneMarket/0.3")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 20_000
            readTimeout = 180_000
        }
        conn.connect()
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode} při stahování APK")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
        conn.inputStream.use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(16 * 1024)
                var read: Int
                var done = 0L
                var last = -1
                while (input.read(buf).also { read = it } >= 0) {
                    output.write(buf, 0, read)
                    done += read
                    if (total > 0) {
                        val p = ((done * 100) / total).toInt().coerceIn(0, 100)
                        if (p != last) { last = p; onProgress(p) }
                    }
                }
            }
        }
        onProgress(100)
        return out
    }

    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls() else true

    fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    fun install(context: Context, file: File): Boolean {
        if (!canInstall(context)) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    fun packageNameFromApk(context: Context, file: File): String? {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33)
                pm.getPackageArchiveInfo(file.absolutePath, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.absolutePath, 0)
            info?.applicationInfo?.sourceDir = file.absolutePath
            info?.applicationInfo?.publicSourceDir = file.absolutePath
            info?.packageName
        } catch (_: Exception) { null }
    }

    fun openInstalled(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
