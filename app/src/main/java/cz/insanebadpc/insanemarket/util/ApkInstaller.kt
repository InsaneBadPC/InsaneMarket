package cz.insanebadpc.insanemarket.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ApkInstaller {
    suspend fun download(context: Context, url: String, fileName: String, token: String?, onProgress: (Int) -> Unit = {}): File {
        val dir = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }
        val out = File(dir, fileName.replace("[^A-Za-z0-9._-]".toRegex(), "_"))
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "InsaneMarket/0.1")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        conn.connect()
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
        conn.inputStream.use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(8192)
                var read: Int; var done = 0L
                while (input.read(buf).also { read = it } >= 0) {
                    output.write(buf, 0, read); done += read
                    if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        }
        return out
    }

    fun install(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
