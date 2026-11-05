package com.yudhis7.gelombang.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.yudhis7.gelombang.data.RadioBrowser
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Serves station artwork as content:// URIs, which is what Android Auto and the media
 * notification can load. Remote logos are downloaded once and cached; stations without a
 * usable logo get a coloured letter tile instead.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("no context")
        val url = uri.getQueryParameter(Q_URL).orEmpty()
        val name = uri.getQueryParameter(Q_NAME).orEmpty()
        val label = uri.getQueryParameter(Q_LABEL)
        val dir = File(ctx.cacheDir, "artwork").apply { mkdirs() }
        val file = File(dir, sha1("$url|$name|$label") + ".png")
        if (!file.exists() || file.length() == 0L) {
            val ok = (url.startsWith("http://") || url.startsWith("https://")) && download(url, file)
            if (!ok) writeTile(name, label, file)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun download(url: String, out: File): Boolean {
        return try {
            downloadUnsafe(url, out)
        } catch (e: Exception) {
            false
        }
    }

    /** Opens the URL, following up to 4 redirects (including http <-> https). */
    private fun open(url: String): HttpURLConnection? {
        var current = URL(url)
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", RadioBrowser.USER_AGENT)
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) return null
                current = URL(current, loc)
            } else if (code in 200..299) {
                return conn
            } else {
                conn.disconnect()
                return null
            }
        }
        return null
    }

    private fun downloadUnsafe(url: String, out: File): Boolean {
        val conn = open(url) ?: return false
        val bytes = conn.inputStream.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) break
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        }
        conn.disconnect()
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (src == null || src.width < 8 || src.height < 8) {
            return false
        } else {
            // Logos are often transparent; put them on white, centred in a square.
            val size = 320
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            val scale = minOf(size * 0.86f / src.width, size * 0.86f / src.height)
            val w = src.width * scale
            val h = src.height * scale
            c.drawBitmap(
                src, null,
                android.graphics.RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
            )
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return true
        }
    }

    private fun writeTile(name: String, label: String?, out: File) {
        val size = 320
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = hueFor(name.ifBlank { label ?: "?" })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            Color.HSVToColor(floatArrayOf(hue, 0.62f, 0.72f)),
            Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.6f, 0.46f)),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        val text = label?.ifBlank { null } ?: initials(name)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = if (text.length > 2) 96f else 128f
        }
        val y = size / 2f - (tp.descent() + tp.ascent()) / 2f
        c.drawText(text, size / 2f, y, tp)
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val Q_URL = "u"
        private const val Q_NAME = "n"
        private const val Q_LABEL = "t"
        private const val MAX_BYTES = 2 * 1024 * 1024

        /** Artwork for a station: its logo if it has one, otherwise a letter tile. */
        fun stationUri(context: Context, favicon: String, name: String): Uri =
            Uri.Builder().scheme("content").authority(context.packageName + ".artwork").path("art")
                .appendQueryParameter(Q_URL, favicon)
                .appendQueryParameter(Q_NAME, name)
                .build()

        /** A tile with short text, e.g. a country code. */
        fun labelUri(context: Context, label: String, seed: String): Uri =
            Uri.Builder().scheme("content").authority(context.packageName + ".artwork").path("tile")
                .appendQueryParameter(Q_NAME, seed)
                .appendQueryParameter(Q_LABEL, label)
                .build()

        fun hueFor(s: String): Float {
            var h = 0
            for (ch in s) h = (h * 31 + ch.code) % 360
            return h.toFloat()
        }

        fun initials(name: String): String {
            val words = name.replace(Regex("""[^\p{L}\p{N}\s]"""), " ").trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return "♪"
            val pick = if (words.size == 1) words[0].take(2) else "${words[0].first()}${words[1].first()}"
            return pick.uppercase()
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
