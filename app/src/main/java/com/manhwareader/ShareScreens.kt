package com.manhwareader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val HOW_TO_CONNECT =
    "Both phones must be on the same Wi-Fi, or one phone's hotspot must be on with the other phone connected to it. " +
        "No internet or mobile data is used."

private fun seriesBytes(s: Series): Long = s.chapters.sumOf { it.sizeBytes }

// ======================================================================= SEND

/** Pick one or more manhwa, then pick the phone to send them to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(vm: AppViewModel, share: ShareViewModel) {
    val view = LocalView.current
    var step by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var manual by remember { mutableStateOf("") }
    val status = share.send

    val chosen = vm.series.filter { it.name in picked }
    val totalBytes = chosen.sumOf { seriesBytes(it) }
    val busy = status is SendStatus.Connecting ||
        status is SendStatus.WaitingAccept ||
        status is SendStatus.Sending
    val allPicked = vm.series.isNotEmpty() && picked.size == vm.series.size

    fun leave() {
        share.stopSending()
        vm.closeShare()
    }

    fun back() {
        when {
            busy -> share.cancelSend()
            step == 1 -> {
                share.stopSending()
                step = 0
            }
            else -> leave()
        }
    }

    BackHandler { back() }

    // Keep the screen on while sending, and stop everything when this screen goes away.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            share.stopSending()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send manhwa") },
                navigationIcon = { TextButton(onClick = { back() }) { Text("Back") } },
            )
        },
    ) { padding ->
        if (step == 0) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Tick the manhwa you want to send",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        picked = if (allPicked) emptySet() else vm.series.map { it.name }.toSet()
                    }) { Text(if (allPicked) "Clear" else "Select all") }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(vm.series, key = { it.name }) { s ->
                        val on = s.name in picked
                        val info = listOfNotNull(
                            "${s.chapters.size} chapters",
                            formatSize(seriesBytes(s)).ifEmpty { null },
                        ).joinToString(" · ")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { picked = if (on) picked - s.name else picked + s.name }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = { picked = if (it) picked + s.name else picked - s.name },
                            )
                            CoverImage(
                                s,
                                vm.engine,
                                Modifier.width(44.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    info,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = {
                            step = 1
                            share.startLooking()
                        },
                        enabled = picked.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp),
                    ) {
                        val size = formatSize(totalBytes)
                        Text(
                            if (picked.isEmpty()) "Pick at least one manhwa"
                            else if (size.isEmpty()) "Next: choose a phone"
                            else "Next: choose a phone ($size)"
                        )
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                when (status) {
                    is SendStatus.Connecting -> StatusBlock("Connecting to ${status.name}…", null) {
                        OutlinedButton(onClick = { share.cancelSend() }) { Text("Cancel") }
                    }
                    is SendStatus.WaitingAccept -> StatusBlock(
                        "Waiting for ${status.name} to press Accept…",
                        null,
                    ) {
                        OutlinedButton(onClick = { share.cancelSend() }) { Text("Cancel") }
                    }
                    is SendStatus.Sending -> {
                        val frac = if (status.total > 0) (status.sent.toFloat() / status.total).coerceIn(0f, 1f) else 0f
                        StatusBlock("Sending to ${status.name}…", frac) {
                            Text(
                                "${formatSize(status.sent).ifEmpty { "0 KB" }} of ${formatSize(status.total)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { share.cancelSend() }) { Text("Cancel") }
                        }
                    }
                    is SendStatus.Done -> {
                        Text("Sent to ${status.name} ✓", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { leave() }) { Text("Done") }
                    }
                    is SendStatus.Declined -> {
                        Text("${status.name} declined.", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { share.startLooking() }) { Text("Try again") }
                    }
                    is SendStatus.Failed -> {
                        Text(status.message, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { share.startLooking() }) { Text("Try again") }
                    }
                    else -> {
                        Text(
                            "Sending ${chosen.size} manhwa" +
                                (formatSize(totalBytes).ifEmpty { null }?.let { " ($it)" } ?: ""),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "On the other phone, open NUX READER and tap Receive.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            HOW_TO_CONNECT,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (share.peers.isEmpty()) "Looking for phones…" else "Tap a phone to send:",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        share.peers.forEach { p ->
                            Button(
                                onClick = { share.sendTo(p, chosen) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) { Text("Send to ${p.name}") }
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Phone doesn't show up? Type the address shown on its Receive screen:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manual,
                            onValueChange = { manual = it },
                            singleLine = true,
                            label = { Text("Address, e.g. 192.168.1.5") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { share.sendManual(manual, chosen) },
                            enabled = manual.isNotBlank(),
                        ) { Text("Send to this address") }
                    }
                }
            }
        }
    }
}

/** A message, an optional progress bar, and whatever buttons go underneath. */
@Composable
private fun StatusBlock(title: String, progress: Float?, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    if (progress == null) {
        CircularProgressIndicator()
    } else {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(12.dp))
    content()
}

// ===================================================================== RECEIVE

/** Waits for another phone. When it offers manhwa, you press Accept to collect them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(vm: AppViewModel, share: ShareViewModel) {
    val view = LocalView.current
    val status = share.recv
    val offer = share.incoming

    fun leave() {
        share.stopReceiving()
        vm.closeShare()
    }

    BackHandler { leave() }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        share.startReceiving()
        onDispose {
            view.keepScreenOn = false
            share.stopReceiving()
        }
    }

    // New manhwa arrived: show them in the library.
    LaunchedEffect(status) {
        if (status is RecvStatus.Finished || status is RecvStatus.Failed) vm.rescan()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive manhwa") },
                navigationIcon = { TextButton(onClick = { leave() }) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (status) {
                is RecvStatus.Waiting -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(20.dp))
                    Text("Ready to receive", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This phone shows up as \"${status.name}\".\n" +
                            "On the other phone: open NUX READER, tap Send, tick the manhwa and choose this phone.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        HOW_TO_CONNECT,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status.addresses.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        val port = if (status.port == Share.PORT) "" else ":${status.port}"
                        Text(
                            "If this phone doesn't appear on their screen, they can type this address: " +
                                status.addresses.joinToString(" or ") { it + port },
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is RecvStatus.Receiving -> {
                    Text("Receiving from ${status.sender}…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    if (status.total > 0) {
                        val frac = (status.done.toFloat() / status.total).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${formatSize(status.done).ifEmpty { "0 KB" }} of ${formatSize(status.total)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Keep this screen open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is RecvStatus.Finished -> {
                    Text("Received ✓", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        status.manhwa.joinToString(", ") + "\n${status.files} files saved to your library.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { leave() }) { Text("Open my library") }
                }
                is RecvStatus.Failed -> {
                    Text(
                        status.message,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { leave() }) { Text("Back") }
                }
                is RecvStatus.Idle -> CircularProgressIndicator()
            }
        }
    }

    if (offer != null) {
        val size = formatSize(offer.totalBytes)
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${offer.sender} wants to send you manhwa") },
            text = {
                Column {
                    offer.manhwa.take(8).forEach { Text("• $it", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (offer.manhwa.size > 8) Text("• and ${offer.manhwa.size - 8} more")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${offer.fileCount} files" + if (size.isEmpty()) "" else " · $size",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { Button(onClick = { share.answer(true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { share.answer(false) }) { Text("Decline") } },
        )
    }
}
