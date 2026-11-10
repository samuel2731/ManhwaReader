package com.manhwareader

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch

/**
 * Every selected chapter as one long vertical scroll. Tap the screen to show/hide the top bar.
 */
@Composable
fun ReaderScreen(
    session: ReaderSession,
    engine: PdfEngine,
    onPosition: (chapterIndex: Int, page: Int) -> Unit,
    onClose: () -> Unit,
) {
    val view = LocalView.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = session.startGlobal)
    var showBar by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler { onClose() }

    // Full screen while reading, and don't let the screen turn off.
    DisposableEffect(view) {
        view.keepScreenOn = true
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val current by remember(session) {
        derivedStateOf {
            val g = listState.firstVisibleItemIndex.coerceIn(0, session.total - 1)
            val ci = session.chapterAt(g)
            ci to (g - session.offsets[ci])
        }
    }

    // Remember where the reader got to so "Continue reading" can pick up from here.
    LaunchedEffect(session) {
        snapshotFlow { current }.collect { (ci, pi) -> onPosition(ci, pi) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { showBar = !showBar }) }
    ) {
        val widthPx = constraints.maxWidth

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(count = session.total, key = { it }) { g ->
                PageItem(session, g, engine, widthPx)
            }
            item(key = "end") {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text("End of your selection", color = Color(0xFF777777))
                }
            }
        }

        AnimatedVisibility(visible = showBar, modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(color = Color(0xEE111111), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val (ci, pi) = current
                    val ch = session.chapters[ci]
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            ch.chapter.title,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Page ${pi + 1} of ${ch.pageCount}  ·  chapter ${ci + 1} of ${session.chapters.size}",
                            color = Color(0xFFAAAAAA),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { showChapters = true }) { Text("Chapters") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }

    if (showChapters) {
        AlertDialog(
            onDismissRequest = { showChapters = false },
            confirmButton = { TextButton(onClick = { showChapters = false }) { Text("Close") } },
            title = { Text("Jump to chapter") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(session.chapters) { i, c ->
                        Text(
                            c.chapter.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (i == current.first) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showChapters = false
                                    scope.launch { listState.scrollToItem(session.offsets[i]) }
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun PageItem(session: ReaderSession, global: Int, engine: PdfEngine, widthPx: Int) {
    val ci = remember(session, global) { session.chapterAt(global) }
    val ch = session.chapters[ci]
    val page = global - session.offsets[ci]
    val uri = ch.chapter.uri

    var bmp by remember(uri, page, widthPx) { mutableStateOf(engine.cached(uri, page, widthPx)) }
    var failed by remember(uri, page, widthPx) { mutableStateOf(false) }
    LaunchedEffect(uri, page, widthPx) {
        if (bmp == null) {
            val result = engine.render(uri, page, widthPx)
            bmp = result
            failed = result == null
        }
    }

    Column {
        if (page == 0) ChapterHeader(ch.chapter.title)

        val b = bmp
        val ratio = (b?.let { it.height.toFloat() / it.width } ?: ch.ratio).coerceIn(0.1f, 40f)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / ratio)
                .background(if (b == null) Color(0xFF151515) else Color.White),
            contentAlignment = Alignment.Center,
        ) {
            when {
                b != null -> Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                failed -> Text("Couldn't load page ${page + 1}", color = Color(0xFF888888))
                else -> CircularProgressIndicator(color = Color(0xFF666666))
            }
        }
    }
}

@Composable
private fun ChapterHeader(title: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1B1B1B))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            title,
            color = Color(0xFFDDDDDD),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
