package com.manhwareader

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background

/** Home: every manhwa in the chosen folder as a cover. Tap one to see its chapters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, onPickFolder: () -> Unit) {
    val snackbar = rememberMessageHost(vm)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("My Manhwa") },
                actions = {
                    if (vm.hasFolder) {
                        TextButton(onClick = { vm.rescan() }) { Text("Refresh") }
                    }
                    TextButton(onClick = onPickFolder) {
                        Text(if (vm.hasFolder) "Folder" else "Pick folder")
                    }
                },
            )
        },
        bottomBar = { if (vm.chapters.isNotEmpty()) SelectionBottomBar(vm, showGlobalButtons = true) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !vm.hasFolder -> EmptyState(
                    "Pick the folder where your manhwa are saved.\n\n" +
                        "Put each manhwa in its own folder, with its chapter PDFs inside. " +
                        "Optional: add a cover.png / cover.jpg to a manhwa's folder to use it as the icon.",
                    "Pick folder",
                    onPickFolder,
                )
                vm.series.isEmpty() && vm.scanning -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                vm.series.isEmpty() -> EmptyState(
                    "No PDF files found in that folder.",
                    "Pick another folder",
                    onPickFolder,
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.series, key = { it.name }) { s -> SeriesCard(vm, s) }
                }
            }
        }
    }

    PreparingDialog(vm)
}

@Composable
private fun EmptyState(text: String, buttonLabel: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
        Button(onClick = onClick) { Text(buttonLabel) }
    }
}

@Composable
private fun SeriesCard(vm: AppViewModel, s: Series) {
    val selCount = s.chapters.count { it.uri in vm.selected }
    val state = when {
        selCount == 0 -> ToggleableState.Off
        selCount == s.chapters.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    val shape = RoundedCornerShape(10.dp)

    Column(Modifier.clickable { vm.openSeries(s.name) }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .then(
                    if (selCount > 0) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                    else Modifier
                )
        ) {
            CoverImage(s, vm.engine, Modifier.fillMaxSize())

            // Tick the whole manhwa without opening it.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color(0x99000000), CircleShape)
            ) {
                TriStateCheckbox(
                    state = state,
                    onClick = { vm.setGroup(s.chapters.map { it.uri }, state != ToggleableState.On) },
                )
            }

            if (selCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(topEnd = 8.dp),
                    modifier = Modifier.align(Alignment.BottomStart),
                ) {
                    Text(
                        "$selCount selected",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Text(
            s.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "${s.chapters.size} chapters",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
