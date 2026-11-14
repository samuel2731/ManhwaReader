package com.manhwareader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The About box: what the app is, what it can do, and where your files stay. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_art),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)),
                )
                Spacer(Modifier.height(12.dp))
                Text("NUX READER", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (version.isNotEmpty()) {
                    Text(
                        "Version $version",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "A reader for the manhwa you already have saved as PDF files.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )

                AboutSection(
                    "What it does",
                    "• Reads many chapters as one long scroll, so you never leave the app to find the next one.\n" +
                        "• Remembers where you stopped.\n" +
                        "• Tap a chapter to read from there to the end, or only that chapter.\n" +
                        "• Sends and receives manhwa with a friend's phone, with no internet.",
                )
                AboutSection(
                    "Your folders",
                    "Add the folders where your manhwa are saved. NUX READER only reads them. " +
                        "To remove a manhwa from the app, delete its folder in your Files app.",
                )
                AboutSection(
                    "Privacy",
                    "Everything stays on your phone. The network is used only when you send or receive " +
                        "manhwa with a phone nearby.",
                )
            }
        },
    )
}

@Composable
private fun AboutSection(title: String, body: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
}
