package com.manhwareader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

data class Chapter(
    val uri: Uri,
    val title: String,
    val series: String,
    val sortKey: String,
    val sizeBytes: Long,
    val modified: Long,
) {
    /** Changes when the file changes, so cached page info is never stale. */
    val cacheKey: String get() = "$uri|$sizeBytes|$modified"
}

data class PdfInfo(val pageCount: Int, val ratio: Float)

data class ReadChapter(val chapter: Chapter, val pageCount: Int, val ratio: Float)

/** One manhwa: a folder of chapters, with an optional cover image found in that folder. */
data class Series(val name: String, val chapters: List<Chapter>, val coverImage: Uri?)

data class LibraryData(val chapters: List<Chapter>, val covers: Map<String, Uri>)

/** All selected chapters laid out as one long list of pages. */
class ReaderSession(
    val chapters: List<ReadChapter>,
    val startChapter: Int,
    val startPage: Int,
) {
    val offsets = IntArray(chapters.size)
    val total: Int

    init {
        var sum = 0
        for (i in chapters.indices) {
            offsets[i] = sum
            sum += chapters[i].pageCount
        }
        total = sum
    }

    val startGlobal: Int get() = offsets[startChapter] + startPage

    /** Index of the chapter that contains the given global page index. */
    fun chapterAt(global: Int): Int {
        var lo = 0
        var hi = chapters.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (offsets[mid] <= global) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/** Compares strings so that "Ch 2" comes before "Ch 10". */
fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i
            while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j
            while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0')
            val nb = b.substring(j, ej).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = ei
            j = ej
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}

object LibraryScanner {
    private const val PDF_MIME = "application/pdf"

    /** Library order: by manhwa name, then by chapter path (numbers sorted naturally). */
    val ORDER = Comparator<Chapter> { a, b ->
        val s = compareNatural(a.series, b.series)
        if (s != 0) s else compareNatural(a.sortKey, b.sortKey)
    }

    /**
     * Walks the picked folder (and all subfolders) and returns every PDF.
     * Top-level subfolders become "series"; PDFs directly in the folder go under the folder's own name.
     */
    fun scan(context: Context, tree: Uri): LibraryData {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = displayName(context, tree, rootId)
            ?: rootId.substringAfterLast('/').substringAfterLast(':').ifBlank { "Library" }
        val out = ArrayList<Chapter>()
        val covers = HashMap<String, Uri>()
        val coverRanks = HashMap<String, Int>()

        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        fun walk(docId: String, series: String, path: String, depth: Int) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cursor = runCatching { resolver.query(childrenUri, columns, null, null, null) }.getOrNull()
                ?: return
            cursor.use { c ->
                // If the folder you added has PDFs directly inside, it is one manhwa
                // (subfolders like "Season 1" stay part of it). Otherwise each subfolder is its own manhwa.
                var hasPdfHere = false
                if (depth == 0) {
                    while (c.moveToNext()) {
                        val n = c.getString(1)
                        val m = c.getString(2) ?: ""
                        if (n != null && m != DocumentsContract.Document.MIME_TYPE_DIR &&
                            (m == PDF_MIME || n.endsWith(".pdf", ignoreCase = true))
                        ) hasPdfHere = true
                    }
                    c.moveToPosition(-1)
                }
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1)
                    val mime = c.getString(2) ?: ""
                    if (id == null || name == null) continue
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val modified = if (c.isNull(4)) 0L else c.getLong(4)
                    val rel = if (path.isEmpty()) name else "$path/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(id, if (depth == 0 && !hasPdfHere) name else series, rel, depth + 1)
                    } else if (mime == PDF_MIME || name.endsWith(".pdf", ignoreCase = true)) {
                        out += Chapter(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            title = name.substringBeforeLast('.', name),
                            series = series,
                            sortKey = rel,
                            sizeBytes = size,
                            modified = modified,
                        )
                    } else if (depth <= 1 && isImage(mime, name)) {
                        // An image sitting in the manhwa's own folder can serve as its cover.
                        val rank = coverRank(name)
                        if (rank < (coverRanks[series] ?: Int.MAX_VALUE)) {
                            coverRanks[series] = rank
                            covers[series] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        }
                    }
                }
            }
        }

        walk(rootId, rootName, "", 0)

        return LibraryData(out.sortedWith(ORDER), covers)
    }

    private fun isImage(mime: String, name: String): Boolean {
        if (mime.startsWith("image/")) return true
        val n = name.lowercase()
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")
    }

    /** Lower is better: files named cover/folder/poster win over any other image. */
    private fun coverRank(name: String): Int {
        val base = name.substringBeforeLast('.').lowercase()
        return if (base in setOf("cover", "folder", "poster", "thumbnail", "thumb", "icon")) 0 else 1
    }

    private fun displayName(context: Context, tree: Uri, docId: String): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        return runCatching {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}
