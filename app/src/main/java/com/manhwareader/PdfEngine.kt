package com.manhwareader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Renders PDF pages to bitmaps. PdfRenderer is not thread-safe, so every call
 * runs on one dedicated thread. Only a few PDFs are kept open at a time.
 */
class PdfEngine(private val context: Context) {

    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render") }.asCoroutineDispatcher()

    private class OpenDoc(val pfd: ParcelFileDescriptor, val renderer: PdfRenderer) {
        fun close() {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    private val docs = object : LinkedHashMap<Uri, OpenDoc>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, OpenDoc>): Boolean {
            val drop = size > MAX_OPEN
            if (drop) eldest.value.close()
            return drop
        }
    }

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 5).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun key(uri: Uri, page: Int, width: Int) = "$uri#$page#$width"

    private fun doc(uri: Uri): OpenDoc? {
        docs[uri]?.let { return it }
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            ?: return null
        return try {
            val opened = OpenDoc(pfd, PdfRenderer(pfd))
            docs[uri] = opened
            opened
        } catch (e: Exception) {
            // Corrupt or password-protected PDF.
            runCatching { pfd.close() }
            null
        }
    }

    /** Page count and the height/width ratio of the first page, or null if the file can't be opened. */
    suspend fun info(uri: Uri): PdfInfo? = withContext(dispatcher) {
        val d = doc(uri) ?: return@withContext null
        try {
            val n = d.renderer.pageCount
            if (n <= 0) return@withContext null
            val page = d.renderer.openPage(0)
            try {
                PdfInfo(n, page.height.toFloat() / page.width.toFloat())
            } finally {
                page.close()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun cached(uri: Uri, page: Int, width: Int): Bitmap? = cache.get(key(uri, page, width))

    suspend fun render(uri: Uri, page: Int, width: Int): Bitmap? {
        cached(uri, page, width)?.let { return it }
        return withContext(dispatcher) {
            cached(uri, page, width)?.let { return@withContext it }
            val d = doc(uri) ?: return@withContext null
            try {
                if (page !in 0 until d.renderer.pageCount) return@withContext null
                val p = d.renderer.openPage(page)
                try {
                    val ratio = p.height.toFloat() / p.width.toFloat()
                    var w = width.coerceAtLeast(1)
                    var h = (w * ratio).toInt().coerceAtLeast(1)
                    // Very tall pages are rendered at reduced resolution to avoid running out of memory.
                    var s = 1.0
                    if (w.toLong() * h > MAX_PIXELS) s = sqrt(MAX_PIXELS.toDouble() / (w.toLong() * h))
                    if (h * s > MAX_DIM) s = MAX_DIM.toDouble() / h
                    if (s < 1.0) {
                        w = (w * s).toInt().coerceAtLeast(1)
                        h = (h * s).toInt().coerceAtLeast(1)
                    }
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key(uri, page, width), bmp)
                    bmp
                } finally {
                    p.close()
                }
            } catch (e: OutOfMemoryError) {
                cache.evictAll()
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    // ---- Covers for the home screen ----

    fun cachedCover(uri: Uri, width: Int): Bitmap? = cache.get("cover#$uri#$width")

    /** Top part of the first page, cropped to a 2:3 poster shape. */
    suspend fun renderCover(uri: Uri, width: Int): Bitmap? = withContext(dispatcher) {
        val k = "cover#$uri#$width"
        cache.get(k)?.let { return@withContext it }
        val d = doc(uri) ?: return@withContext null
        try {
            val p = d.renderer.openPage(0)
            try {
                val bmp = Bitmap.createBitmap(width, (width * 1.5f).toInt(), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val s = width / p.width.toFloat()
                val m = Matrix().apply { setScale(s, s) }
                p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(k, bmp)
                bmp
            } finally {
                p.close()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun cachedImage(uri: Uri, maxWidth: Int): Bitmap? = cache.get("img#$uri#$maxWidth")

    /** Loads a cover image file (png/jpg/webp) from the folder, downsampled. */
    suspend fun loadImage(uri: Uri, maxWidth: Int): Bitmap? {
        val k = "img#$uri#$maxWidth"
        cache.get(k)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                if (bmp != null) cache.put(k, bmp)
                bmp
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    private companion object {
        const val MAX_OPEN = 4
        const val MAX_DIM = 8192
        const val MAX_PIXELS = 8_000_000L
    }
}
