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
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val vm: AppViewModel = viewModel()

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
