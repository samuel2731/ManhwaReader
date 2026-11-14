package com.manhwareader

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.Color as ComposeColor

/** Light theme with the purple from the app icon. */
private val NuxLight = lightColorScheme(
    primary = ComposeColor(0xFF5B2EC9),
    onPrimary = ComposeColor.White,
    secondary = ComposeColor(0xFF7A5AD6),
    background = ComposeColor(0xFFFBF9FF),
    surface = ComposeColor(0xFFFBF9FF),
    surfaceVariant = ComposeColor(0xFFE8E3F2),
    onSurfaceVariant = ComposeColor(0xFF5B5668),
)

/** Dark theme, same purple. */
private val NuxDark = darkColorScheme(
    primary = ComposeColor(0xFFB69DFF),
    onPrimary = ComposeColor(0xFF2B0A73),
    secondary = ComposeColor(0xFFCFBDFF),
    background = ComposeColor(0xFF141218),
    surface = ComposeColor(0xFF141218),
    surfaceVariant = ComposeColor(0xFF2E2A38),
    onSurfaceVariant = ComposeColor(0xFFCAC4D6),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            val vm: AppViewModel = viewModel()
            val share: ShareViewModel = viewModel()
            val dark = vm.darkTheme

            // Status bar icons and the window colour follow the theme chosen in the menu.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
                window.decorView.setBackgroundColor(if (dark) 0xFF141218.toInt() else 0xFFFBF9FF.toInt())
                onDispose {}
            }

            MaterialTheme(colorScheme = if (dark) NuxDark else NuxLight) {
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    if (uri != null) {
                        // Keep access to the folder after the app is closed.
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        vm.onFolderPicked(uri)
                    }
                }

                // Pick a picture to use as the cover of the manhwa that is currently open.
                val coverPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    val name = vm.openName
                    // The picture is copied into the app, so no lasting permission is needed.
                    if (uri != null && name != null) vm.setCover(name, uri)
                }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val session = vm.reader
                    val open = vm.currentSeries
                    when {
                        session != null -> ReaderScreen(
                            session = session,
                            engine = vm.engine,
                            onPosition = { ci, page -> vm.onPosition(ci, page) },
                            onClose = { vm.closeReader() },
                        )
                        vm.shareMode == ShareMode.SEND -> SendScreen(vm, share)
                        vm.shareMode == ShareMode.RECEIVE -> ReceiveScreen(vm, share)
                        open != null -> SeriesScreen(
                            vm, open,
                            onPickCover = { coverPicker.launch(arrayOf("image/*")) },
                        )
                        else -> HomeScreen(vm, onPickFolder = { picker.launch(null) })
                    }
                }
            }
        }
    }
}
