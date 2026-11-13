package com.manhwareader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Chapter list of one manhwa. Ticks stay when you go back and open another manhwa. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(vm: AppViewModel, series: Series, onPickCover: () -> Unit) {
    BackHandler { vm.closeSeries() }

    val snackbar = rememberMessageHost(vm)
    val uris = remember(series) { series.chapters.map { it.uri } }
    val selectedHere = series.chapters.count { it.uri in vm.selected }
    val allSelected = selectedHere == series.chapters.size
    // Position (in this manhwa's list) of the chapter whose options are showing.
    var optionsFor by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(series.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = { vm.closeSeries() }) { Text("Back") } },
                actions = {
                    TextButton(onClick = { vm.setGroup(uris, !allSelected) }) {
                        Text(if (allSelected) "Clear" else "Select all")
                    }
                },
            )
        },
        bottomBar = { SelectionBottomBar(vm, showGlobalButtons = false) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CoverImage(
                        series,
                        vm.engine,
                        Modifier.width(84.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(series.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text(
                            "${series.chapters.size} chapters",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "$selectedHere selected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row {
                            TextButton(onClick = onPickCover) { Text("Change cover") }
                            if (vm.hasCustomCover(series.name)) {
                                TextButton(onClick = { vm.setCover(series.name, null) }) { Text("Reset") }
                            }
                        }
                    }
                }
            }
            itemsIndexed(series.chapters, key = { _, ch -> ch.uri.toString() }) { index, ch ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { optionsFor = index }
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = ch.uri in vm.selected, onCheckedChange = { vm.toggle(ch.uri) })
                    Column(Modifier.weight(1f)) {
                        Text(ch.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val size = formatSize(ch.sizeBytes)
                        if (size.isNotEmpty()) {
                            Text(
                                size,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    PreparingDialog(vm)

    val idx = optionsFor
    val tapped = if (idx != null) series.chapters.getOrNull(idx) else null
    if (idx != null && tapped != null) {
        val laterCount = series.chapters.size - idx
        val ticked = tapped.uri in vm.selected
        AlertDialog(
            onDismissRequest = { optionsFor = null },
            title = { Text(tapped.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            optionsFor = null
                            vm.readFrom(series, idx)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (laterCount > 1) "Read from here to the end ($laterCount chapters)"
                            else "Read this chapter",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (laterCount > 1) {
                        TextButton(
                            onClick = {
                                optionsFor = null
                                vm.readOnly(tapped)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Read only this chapter", modifier = Modifier.fillMaxWidth()) }
                    }
                    TextButton(
                        onClick = {
                            optionsFor = null
                            vm.toggle(tapped.uri)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (ticked) "Untick this chapter" else "Tick this chapter (read it later with others)",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(
                        onClick = {
                            optionsFor = null
                            vm.setGroup(uris, true)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Select all chapters of this manhwa", modifier = Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { optionsFor = null }) { Text("Cancel") } },
        )
    }
}
