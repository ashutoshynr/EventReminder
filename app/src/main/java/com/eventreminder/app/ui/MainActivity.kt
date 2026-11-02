package com.eventreminder.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eventreminder.app.license.LicenseManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EventTheme {
                val vm: MainViewModel = viewModel()
                var splash by rememberSaveable { mutableStateOf(true) }
                var activated by remember { mutableStateOf(LicenseManager.isActivated(this)) }
                val stage = when { splash -> 0; !activated -> 1; else -> 2 }
                Crossfade(stage, animationSpec = tween(200), label = "stage") {
                    when (it) {
                        0 -> OpeningCard(vm, activated) { splash = false }
                        1 -> LicenseScreen(onActivated = { activated = true }, onExit = { finishAffinity() })
                        else -> AppContent(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppContent(vm: MainViewModel) {
    val ctx = LocalContext.current
    var route by rememberSaveable { mutableStateOf("list") }
    var editId by rememberSaveable { mutableLongStateOf(0L) }
    var askQuit by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Back on a sub-screen returns to the list; Back on the list asks before quitting.
    BackHandler { if (route != "list") route = "list" else askQuit = true }

    when (route) {
        "edit" -> EditScreen(vm, editId) { route = "list" }
        "settings" -> SettingsScreen(onBack = { route = "list" }, onQuit = { askQuit = true })
        else -> ListScreen(
            vm = vm,
            onAdd = { editId = 0L; route = "edit" },
            onEdit = { editId = it; route = "edit" },
            onSettings = { route = "settings" }
        )
    }

    if (askQuit) {
        AlertDialog(
            onDismissRequest = { askQuit = false },
            title = { Text("Quit Event Reminder?") },
            text = { Text("Your alarms will still ring even when the app is closed.") },
            confirmButton = { TextButton({ askQuit = false; (ctx as? Activity)?.finishAffinity() }) { Text("Quit") } },
            dismissButton = { TextButton({ askQuit = false }) { Text("Stay") } }
        )
    }
}
