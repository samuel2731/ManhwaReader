package com.manhwareader

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ShareMode { SEND, RECEIVE }

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    val engine = PdfEngine(app)

    var chapters by mutableStateOf<List<Chapter>>(emptyList())
        private set
    var selected by mutableStateOf<Set<Uri>>(emptySet())
        private set
    var scanning by mutableStateOf(false)
        private set
    var folders by mutableStateOf(prefs.trees)
        private set
    val hasFolder: Boolean get() = folders.isNotEmpty()
    /** (chapters checked so far, total) while the reader is being prepared. */
    var preparing by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var reader by mutableStateOf<ReaderSession?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var series by mutableStateOf<List<Series>>(emptyList())
        private set
    /** Name of the manhwa whose chapter list is open, or null on the home screen. */
    var openName by mutableStateOf<String?>(null)
        private set

    val currentSeries: Series?
        get() = openName?.let { n -> series.firstOrNull { it.name == n } }

    private var scanJob: Job? = null
    private var scanId = 0
    /** Cover pictures found inside the folders themselves (cover.png etc.). */
    private var folderCovers: Map<String, Uri> = emptyMap()

    init {
        rescan()
    }

    fun onFolderPicked(uri: Uri) {
        // Adds to the folders already there instead of replacing them.
        if (uri !in prefs.trees) {
            prefs.trees = prefs.trees + uri
            folders = prefs.trees
        }
        rescan()
    }

    fun rescan() {
        val trees = prefs.trees
        scanJob?.cancel()
        // Manhwa received from a friend count as a library even if no folder was added.
        if (trees.isEmpty() && !Share.receivedDir(getApplication<Application>()).exists()) {
            chapters = emptyList()
            series = emptyList()
            openName = null
            updateSelected(emptySet())
            scanning = false
            return
        }
        val myScan = ++scanId
        scanJob = viewModelScope.launch {
            scanning = true
            try {
                val found = withContext(Dispatchers.IO) {
                    runCatching { scanAll(trees) }
                }
                found.onSuccess { (data, failed, gone) ->
                    val list = data.chapters
                    // Folders that were deleted from the phone are dropped from the app by themselves.
                    if (gone.isNotEmpty()) {
                        prefs.trees = prefs.trees.filter { it !in gone }
                        folders = prefs.trees
                    }
                    chapters = list
                    folderCovers = data.covers
                    buildSeries()
                    if (series.none { it.name == openName }) openName = null
                    val saved = prefs.selection
                    selected = list.map { it.uri }.filter { it.toString() in saved }.toSet()
                    if (gone.isNotEmpty()) {
                        message = "A folder was deleted or moved on your phone, so it was taken out of the app."
                    } else if (failed > 0) {
                        message = "$failed folder(s) couldn't be opened."
                    } else if (list.isEmpty()) {
                        message = "No PDF files found in your folders."
                    }
                }.onFailure {
                    message = "Can't open that folder. Please pick it again."
                }
            } finally {
                // An older, cancelled scan must not hide the spinner of a newer one.
                if (myScan == scanId) scanning = false
            }
        }
    }

    /**
     * Scans every added folder and merges the results.
     * Returns the library, how many folders failed to open, and the folders that no longer exist.
     */
    private fun scanAll(trees: List<Uri>): Triple<LibraryData, Int, List<Uri>> {
        val app = getApplication<Application>()
        val all = ArrayList<Chapter>()
        val covers = HashMap<String, Uri>()
        val seen = HashSet<String>()
        val gone = ArrayList<Uri>()
        var failed = 0

        // The same chapter found twice (two folders, or one you were also sent) is listed once.
        fun addAll(list: List<Chapter>) {
            for (c in list) {
                val key = c.series + "|" + c.sortKey.removePrefix(c.series + "/").lowercase()
                if (seen.add(key)) all.add(c)
            }
        }

        for (t in trees) {
            if (!LibraryScanner.exists(app, t)) {
                gone.add(t)
                continue
            }
            val r = runCatching { LibraryScanner.scan(app, t) }.getOrNull()
            if (r == null) {
                failed++
            } else {
                addAll(r.chapters)
                for ((name, cover) in r.covers) if (name !in covers) covers[name] = cover
            }
        }

        val received = Share.receivedDir(app)
        if (received.isDirectory) {
            val r = runCatching { LibraryScanner.scanDir(received) }.getOrNull()
            if (r != null) {
                addAll(r.chapters)
                for ((name, cover) in r.covers) if (name !in covers) covers[name] = cover
            }
        }

        if (trees.isNotEmpty() && failed + gone.size == trees.size && all.isEmpty() && failed > 0) {
            throw IllegalStateException("No folder could be opened")
        }
        return Triple(LibraryData(all.sortedWith(LibraryScanner.ORDER), covers), failed, gone)
    }

    /** The theme chosen in the menu. */
    var darkTheme by mutableStateOf(prefs.darkTheme)
        private set

    fun setDarkTheme(on: Boolean) {
        darkTheme = on
        prefs.darkTheme = on
    }

    private fun updateSelected(set: Set<Uri>) {
        selected = set
        prefs.selection = set.map { it.toString() }.toSet()
    }

    fun toggle(uri: Uri) {
        updateSelected(if (uri in selected) selected - uri else selected + uri)
    }

    fun setGroup(uris: List<Uri>, on: Boolean) {
        updateSelected(if (on) selected + uris else selected - uris.toSet())
    }

    fun selectAll() = updateSelected(chapters.map { it.uri }.toSet())

    fun clearSelection() = updateSelected(emptySet())

    /** True when the saved reading position is inside the current selection. */
    fun canResume(): Boolean {
        val r = prefs.resume() ?: return false
        return selected.any { it.toString() == r.first }
    }

    /**
     * Opens every selected chapter as one continuous reader, in library order.
     * [fromStart] false jumps to the saved reading position when it is part of the selection.
     */
    fun startReading(fromStart: Boolean) {
        val ordered = chapters.filter { it.uri in selected }
        if (ordered.isEmpty() || preparing != null) return

        viewModelScope.launch {
            preparing = 0 to ordered.size
            val ready = ArrayList<ReadChapter>()
            val fresh = HashMap<String, PdfInfo>()
            var skipped = 0

            ordered.forEachIndexed { i, ch ->
                var info = prefs.pageInfo(ch.cacheKey)
                if (info == null) {
                    info = engine.info(ch.uri)
                    if (info != null) fresh[ch.cacheKey] = info
                }
                if (info != null && info.pageCount > 0) {
                    ready += ReadChapter(ch, info.pageCount, info.ratio)
                } else {
                    skipped++
                }
                preparing = (i + 1) to ordered.size
            }
            if (fresh.isNotEmpty()) prefs.savePageInfos(fresh)
            preparing = null

            if (ready.isEmpty()) {
                message = "None of the selected files could be opened."
                return@launch
            }

            val resume = if (fromStart) null else prefs.resume()
            val resumeIndex = resume?.let { r ->
                ready.indexOfFirst { it.chapter.uri.toString() == r.first }
            } ?: -1
            val startChapter = if (resumeIndex >= 0) resumeIndex else 0
            val startPage = if (resumeIndex >= 0) {
                (resume?.second ?: 0).coerceIn(0, ready[startChapter].pageCount - 1)
            } else 0

            reader = ReaderSession(ready, startChapter, startPage)
            if (skipped > 0) message = "$skipped file(s) couldn't be opened and were skipped."
        }
    }

    fun onPosition(chapterIndex: Int, page: Int) {
        val session = reader ?: return
        val ch = session.chapters.getOrNull(chapterIndex) ?: return
        prefs.saveResume(ch.chapter.uri.toString(), page)
    }

    fun closeReader() {
        reader = null
    }

    /** Groups chapters into manhwa and picks each one's cover: your own picture first, then a cover file in the folder. */
    private fun buildSeries() {
        val custom = prefs.customCovers
        series = chapters.groupBy { it.series }.map { (name, items) ->
            Series(name, items, custom[name]?.let { Uri.parse(it) } ?: folderCovers[name])
        }
    }

    fun hasCustomCover(name: String): Boolean = prefs.customCovers.containsKey(name)

    /** Sets the picture used as this manhwa's cover. Pass null to go back to the default. */
    fun setCover(name: String, uri: Uri?) {
        viewModelScope.launch {
            val current = prefs.customCovers
            if (uri == null) {
                current[name]?.let { engine.deleteCover(it) }
                prefs.customCovers = current - name
            } else {
                val saved = engine.importCover(uri, name)
                if (saved == null) {
                    message = "Couldn't use that picture."
                    return@launch
                }
                prefs.customCovers = current + (name to saved.toString())
            }
            buildSeries() // no need to scan the folders again
        }
    }

    /** Reads this chapter and everything after it in the same manhwa, starting right away. */
    fun readFrom(series: Series, index: Int) {
        val list = series.chapters.drop(index)
        if (list.isEmpty()) return
        updateSelected(list.map { it.uri }.toSet())
        startReading(true)
    }

    /** Reads just this one chapter. */
    fun readOnly(ch: Chapter) {
        updateSelected(setOf(ch.uri))
        startReading(true)
    }

    /** Send or Receive screen that is open, or null for the normal screens. */
    var shareMode by mutableStateOf<ShareMode?>(null)
        private set

    fun openShare(mode: ShareMode) {
        shareMode = mode
    }

    fun closeShare() {
        shareMode = null
    }

    fun openSeries(name: String) {
        openName = name
    }

    fun closeSeries() {
        openName = null
    }

    override fun onCleared() {
        engine.close()
    }
}
