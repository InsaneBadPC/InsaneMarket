package cz.insanebadpc.insanemarket.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import android.widget.ImageView
import cz.insanebadpc.insanemarket.BuildConfig
import cz.insanebadpc.insanemarket.model.AppEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs

object IconLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val memoryCache = object : LruCache<String, Bitmap>(64) {}

    private val GRADIENT_PAIRS = arrayOf(
        intArrayOf(0xFF2563EB.toInt(), 0xFF7C3AED.toInt()),
        intArrayOf(0xFF059669.toInt(), 0xFF0EA5E9.toInt()),
        intArrayOf(0xFFD97706.toInt(), 0xFFDC2626.toInt()),
        intArrayOf(0xFF7C3AED.toInt(), 0xFFEC4899.toInt()),
        intArrayOf(0xFF0891B2.toInt(), 0xFF2563EB.toInt()),
        intArrayOf(0xFF4F46E5.toInt(), 0xFF06B6D4.toInt()),
        intArrayOf(0xFF10B981.toInt(), 0xFF3B82F6.toInt()),
        intArrayOf(0xFFF59E0B.toInt(), 0xFF8B5CF6.toInt()),
    )

    fun bind(imageView: ImageView, entry: AppEntry) {
        val context = imageView.context.applicationContext
        val tagKey = "${entry.repoFullName}|${entry.iconUrl}|${entry.packageName}|${entry.isInstalled}"
        imageView.tag = tagKey

        // 1. Check memory cache first
        memoryCache.get(tagKey)?.let { cached ->
            imageView.setImageBitmap(cached)
            return
        }

        // 2. Try installed package icon immediately if installed and no custom override URL
        val pkg = entry.packageName
        if (!pkg.isNullOrBlank() && entry.isInstalled) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(pkg)
                imageView.setImageDrawable(drawable)
                return
            }
        }

        // 3. Set generated modern monogram icon immediately as placeholder
        val placeholder = generateMonogramBitmap(entry.displayName, entry.repoFullName)
        imageView.setImageBitmap(placeholder)

        val iconUrl = entry.iconUrl?.trim()?.takeIf { it.startsWith("http") } ?: return

        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                loadRemoteIcon(context, iconUrl)
            }
            if (bmp != null && imageView.tag == tagKey) {
                val rounded = roundBitmap(bmp, 160)
                memoryCache.put(tagKey, rounded)
                imageView.setImageBitmap(rounded)
            }
        }
    }

    private fun loadRemoteIcon(context: Context, url: String): Bitmap? {
        val cacheDir = File(context.cacheDir, "app_icons").apply { mkdirs() }
        val file = File(cacheDir, sha256(url) + ".png")
        if (file.isFile && file.length() > 0L && System.currentTimeMillis() - file.lastModified() < 7 * 24 * 3600_000L) {
            decodeSampled(file.readBytes(), 192)?.let { return it }
        }

        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "InsaneMarket/${BuildConfig.VERSION_NAME}")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isEmpty() || bytes.size > 5 * 1024 * 1024) return null
                val decoded = decodeSampled(bytes, 192) ?: return null
                runCatching {
                    FileOutputStream(file).use { out ->
                        decoded.compress(Bitmap.CompressFormat.PNG, 95, out)
                    }
                }
                decoded
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSampled(bytes: ByteArray, reqSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > reqSize * 2 || bounds.outHeight / sample > reqSize * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    fun generateMonogramBitmap(displayName: String, key: String, sizePx: Int = 160): Bitmap {
        val cacheKey = "mono:$key:$displayName:$sizePx"
        memoryCache.get(cacheKey)?.let { return it }

        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pair = GRADIENT_PAIRS[abs(key.hashCode()) % GRADIENT_PAIRS.size]

        val rect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val radius = sizePx * 0.28f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, sizePx.toFloat(), sizePx.toFloat(),
                pair[0], pair[1], Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        // Subtle inner highlight border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.03f
            color = Color.argb(65, 255, 255, 255)
        }
        val inset = borderPaint.strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(inset, inset, sizePx - inset, sizePx - inset),
            radius,
            radius,
            borderPaint,
        )

        val initials = extractInitials(displayName)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * 0.40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textBounds = Rect()
        textPaint.getTextBounds(initials, 0, initials.length, textBounds)
        val y = sizePx / 2f - textBounds.exactCenterY()
        canvas.drawText(initials, sizePx / 2f, y, textPaint)

        memoryCache.put(cacheKey, bmp)
        return bmp
    }

    private fun roundBitmap(source: Bitmap, sizePx: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(source, sizePx, sizePx, true)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val radius = sizePx * 0.24f
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return out
    }

    private fun extractInitials(name: String): String {
        val words = name.trim()
            .replace(Regex("[^\\p{L}\\p{N}\\s_-]"), "")
            .split(Regex("[\\s_-]+"))
            .filter { it.isNotBlank() }
        return when {
            words.size >= 2 -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
            words.size == 1 && words[0].length >= 2 -> words[0].take(2).uppercase()
            words.size == 1 -> words[0].take(1).uppercase()
            else -> "IM"
        }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
