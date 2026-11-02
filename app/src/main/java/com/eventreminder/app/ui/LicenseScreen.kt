package com.eventreminder.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eventreminder.app.license.LicenseManager

@Composable
fun LicenseScreen(onActivated: () -> Unit, onExit: () -> Unit) {
    val ctx = LocalContext.current
    var key by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }

    fun submit() { if (LicenseManager.activate(ctx, key)) onActivated() else error = true }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF5B4BDB), Color(0xFF2B1F8F)))),
        contentAlignment = Alignment.Center
    ) {
        Card(Modifier.padding(24.dp).fillMaxWidth().systemBarsPadding().imePadding()) {
            Column(
                Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("🔐", fontSize = 48.sp)
                Text("Activate Event Reminder", fontWeight = FontWeight.Bold, fontSize = 22.sp, textAlign = TextAlign.Center)
                Text(
                    "Enter your licence key to complete the installation.",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it; error = false },
                    label = { Text("Licence key") }, singleLine = true, isError = error,
                    supportingText = { if (error) Text("Invalid licence key. Please try again.") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = ::submit, enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text("Activate", fontSize = 16.sp)
                }
                TextButton(onClick = onExit) { Text("Exit") }
            }
        }
    }
}
