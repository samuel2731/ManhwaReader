package com.manhwareader

import android.app.Application
import android.content.Intent
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
        if (trees.isEmpty()) {
            chapters = emptyList()
            series = emptyList()
            openName = null
            updateSelected(emptySet())
            scanning = false
            return
        }
        scanJob = viewModelScope.launch {
            scanning = true
            try {
                val found = withContext(Dispatchers.IO) {
                    runCatching { scanAll(trees) }
                }
                found.onSuccess { (data, failed) ->
                    val list = data.chapters
                    chapters = list
                    series = list.groupBy { it.series }
                        .map { (name, items) -> Series(name, items, data.covers[name]) }
                    if (series.none { it.name == openName }) openName = null
                    val saved = prefs.selection
                    selected = list.map { it.uri }.filter { it.toString() in saved }.toSet()
                    if (failed > 0) {
                        message = "$failed folder(s) couldn't be opened. Remove them and add them again."
                    } else if (list.isEmpty()) {
                        message = "No PDF files found in your folders."
                    }
                }.onFailure {
                    message = "Can't open that folder. Please pick it again."
                }
            } finally {
                scanning = false
            }
        }
    }

    /** Scans every added folder and merges the results. Returns the library and how many folders failed. */
    private fun scanAll(trees: List<Uri>): Pair<LibraryData, Int> {
        val app = getApplication<Application>()
        val all = ArrayList<Chapter>()
        val covers = HashMap<String, Uri>()
        var failed = 0
        for (t in trees) {
            val r = runCatching { LibraryScanner.scan(app, t) }.getOrNull()
            if (r == null) {
                failed++
            } else {
                all += r.chapters
                for ((name, cover) in r.covers) if (name !in covers) covers[name] = cover
            }
        }
        if (failed == trees.size) throw IllegalStateException("No folder could be opened")
        return LibraryData(all.sortedWith(LibraryScanner.ORDER), covers) to failed
    }

    fun removeFolder(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver
                .releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.trees = prefs.trees - uri
        folders = prefs.trees
        rescan()
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

    fun openSeries(name: String) {
        openName = name
    }

    fun closeSeries() {
        openName = null
    }
}
