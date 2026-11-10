package com.manhwareader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

private const val COVER_W = 360

/** Shows messages from the view model (e.g. "2 files were skipped") as a snackbar. */
@Composable
fun rememberMessageHost(vm: AppViewModel): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            host.showSnackbar(m)
            vm.message = null
        }
    }
    return host
}

/** Poster-style cover: a cover image from the folder if there is one, otherwise the first page of chapter 1. */
@Composable
fun CoverImage(series: Series, engine: PdfEngine, modifier: Modifier = Modifier) {
    val firstPdf = series.chapters.firstOrNull()?.uri
    var bmp by remember(series.name, series.coverImage, firstPdf) {
        mutableStateOf(
            if (series.coverImage != null) engine.cachedImage(series.coverImage, COVER_W)
            else firstPdf?.let { engine.cachedCover(it, COVER_W) }
        )
    }
    LaunchedEffect(series.name, series.coverImage, firstPdf) {
        if (bmp == null) {
            bmp = series.coverImage?.let { engine.loadImage(it, COVER_W) }
                ?: firstPdf?.let { engine.renderCover(it, COVER_W) }
        }
    }
    Box(modifier.background(Color(0xFF26262B)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = series.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
        } else {
            Text(
                series.name.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFF8E8E99),
            )
        }
    }
}

/** Bottom bar shared by the home screen and the chapter list: selection summary and the read buttons. */
@Composable
fun SelectionBottomBar(vm: AppViewModel, showGlobalButtons: Boolean) {
    val selectedCount = vm.selected.size
    val manhwaCount = vm.series.count { s -> s.chapters.any { it.uri in vm.selected } }
    val busy = vm.preparing != null

    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selectedCount == 0) "Nothing selected"
                    else "$selectedCount chapters from $manhwaCount manhwa",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (showGlobalButtons) {
                    TextButton(onClick = { vm.selectAll() }) { Text("Select all") }
                    TextButton(onClick = { vm.clearSelection() }, enabled = selectedCount > 0) {
                        Text("Clear")
                    }
                }
            }
            if (vm.canResume()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.startReading(false) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Continue reading") }
                    OutlinedButton(
                        onClick = { vm.startReading(true) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("From start") }
                }
            } else {
                Button(
                    onClick = { vm.startReading(true) },
                    enabled = selectedCount > 0 && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Read selected") }
            }
        }
    }
}

@Composable
fun PreparingDialog(vm: AppViewModel) {
    val p = vm.preparing ?: return
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("Getting your chapters ready") },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = { p.first / p.second.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("${p.first} of ${p.second}")
            }
        },
    )
}
