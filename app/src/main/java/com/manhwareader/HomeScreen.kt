package com.manhwareader

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Home: every manhwa as a cover. Tap one to see its chapters and pick what to read.
 * Send and Receive share manhwa with another phone that has the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, onPickFolder: () -> Unit) {
    val snackbar = rememberMessageHost(vm)
    var showFolders by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("NUX READER") },
                actions = {
                    if (vm.hasFolder) {
                        TextButton(onClick = { vm.rescan() }) { Text("Refresh") }
                    }
                    TextButton(onClick = { if (vm.hasFolder) showFolders = true else onPickFolder() }) {
                        Text(if (vm.hasFolder) "Folders" else "Pick folder")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { vm.openShare(ShareMode.SEND) },
                    enabled = vm.series.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Send") }
                FilledTonalButton(
                    onClick = { vm.openShare(ShareMode.RECEIVE) },
                    modifier = Modifier.weight(1f),
                ) { Text("Receive") }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !vm.hasFolder && vm.series.isEmpty() -> EmptyState(
                        "Pick the folder where your manhwa are saved.\n\n" +
                            "Put each manhwa in its own folder, with its chapter PDFs inside. " +
                            "Optional: add a cover.png / cover.jpg to a manhwa's folder to use it as the icon.\n\n" +
                            "Or tap Receive to get manhwa from a friend.",
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
    }

    PreparingDialog(vm)

    if (showFolders && vm.hasFolder) {
        FoldersDialog(
            vm,
            onAdd = {
                showFolders = false
                onPickFolder()
            },
            onDismiss = { showFolders = false },
        )
    }
}

/** Lists the added folders. Add as many as you like; removing one never deletes your files. */
@Composable
private fun FoldersDialog(vm: AppViewModel, onAdd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your folders") },
        text = {
            Column {
                vm.folders.forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            folderLabel(f),
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { vm.removeFolder(f) }) { Text("Remove") }
                    }
                }
                Text(
                    "Removing a folder only takes it out of the app. Your files are not deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("Add folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun folderLabel(uri: Uri): String = runCatching {
    DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifBlank { "Phone storage" }
}.getOrDefault(uri.toString())

@Composable
private fun EmptyState(text: String, buttonLabel: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.padding(8.dp))
        Button(onClick = onClick) { Text(buttonLabel) }
    }
}

@Composable
private fun SeriesCard(vm: AppViewModel, s: Series) {
    // Chapters are picked inside the manhwa, but a badge shows which ones have something picked.
    val selCount = s.chapters.count { it.uri in vm.selected }
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
