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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Renders PDF pages to bitmaps. PdfRenderer is not thread-safe, so every PDF call
 * runs on one dedicated thread. Only a few PDFs are kept open at a time.
 */
class PdfEngine(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render") }
    private val dispatcher = executor.asCoroutineDispatcher()

    private class OpenDoc(val pfd: ParcelFileDescriptor, val renderer: PdfRenderer) {
        fun close() {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    // Only touched from the PDF thread.
    private val docs = object : LinkedHashMap<Uri, OpenDoc>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, OpenDoc>): Boolean {
            val drop = size > MAX_OPEN
            if (drop) eldest.value.close()
            return drop
        }
    }

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 4).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Height/width of every page seen so far. Lets the reader reserve the right space and avoid jumping. */
    private val ratios = ConcurrentHashMap<String, Float>()

    private fun key(uri: Uri, page: Int, width: Int) = "$uri#$page#$width"

    fun ratioOf(uri: Uri, page: Int): Float? = ratios["$uri#$page"]

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
                    ratios["$uri#$page"] = ratio
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

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun coverFile(uri: Uri, width: Int): File {
        val dir = File(context.cacheDir, "covers").apply { mkdirs() }
        return File(dir, "${md5(uri.toString())}_$width.jpg")
    }

    /**
     * First page of the chapter as a poster. The result is saved on the phone,
     * so the home screen opens instantly the next time.
     */
    suspend fun renderCover(uri: Uri, width: Int): Bitmap? {
        val k = "cover#$uri#$width"
        cache.get(k)?.let { return it }

        val file = coverFile(uri, width)
        val fromDisk = withContext(Dispatchers.IO) {
            if (file.exists()) runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() else null
        }
        if (fromDisk != null) {
            cache.put(k, fromDisk)
            return fromDisk
        }

        val bmp = withContext(dispatcher) {
            val d = doc(uri) ?: return@withContext null
            try {
                val p = d.renderer.openPage(0)
                try {
                    val b = Bitmap.createBitmap(width, (width * 1.5f).toInt(), Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    val s = width / p.width.toFloat()
                    val m = Matrix().apply { setScale(s, s) }
                    p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                } finally {
                    p.close()
                }
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                null
            }
        } ?: return null

        cache.put(k, bmp)
        withContext(Dispatchers.IO) {
            runCatching { FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        }
        return bmp
    }

    fun cachedImage(uri: Uri, maxWidth: Int): Bitmap? = cache.get("img#$uri#$maxWidth")

    /** Reads a picture file shrunk to about [maxWidth] pixels wide (never decodes more than ~4 million pixels). */
    private fun decodeSampled(uri: Uri, maxWidth: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth ||
            (bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) > 4_000_000L
        ) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Loads a cover image file (png/jpg/webp), downsampled. */
    suspend fun loadImage(uri: Uri, maxWidth: Int): Bitmap? {
        val k = "img#$uri#$maxWidth"
        cache.get(k)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val bmp = decodeSampled(uri, maxWidth)
                if (bmp != null) cache.put(k, bmp)
                bmp
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Copies a picture the user picked into the app's own storage as a small JPEG.
     * That way the cover keeps working after the app restarts, no matter where the original was.
     */
    suspend fun importCover(source: Uri, name: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val bmp = decodeSampled(source, 720) ?: return@withContext null
            val dir = File(context.filesDir, "covers").apply { mkdirs() }
            val prefix = md5(name)
            val file = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            // Remove the older picture for this manhwa.
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith("${prefix}_") && f != file) f.delete()
            }
            Uri.fromFile(file)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    fun deleteCover(stored: String) {
        runCatching {
            val path = Uri.parse(stored).path ?: return
            val f = File(path)
            // Only ever delete files inside our own covers folder.
            if (f.parentFile == File(context.filesDir, "covers")) f.delete()
        }
    }

    /** Frees open PDFs and the render thread. Call when the app screen is gone for good. */
    fun close() {
        executor.execute {
            docs.values.forEach { it.close() }
            docs.clear()
        }
        executor.shutdown()
        cache.evictAll()
    }

    private companion object {
        const val MAX_OPEN = 4
        const val MAX_DIM = 8192
        const val MAX_PIXELS = 5_000_000L
    }
}
