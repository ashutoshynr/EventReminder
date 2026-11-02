package com.eventreminder.app.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Parcelable
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.eventreminder.app.backup.BackupManager
import com.eventreminder.app.data.Category
import com.eventreminder.app.data.Event
import com.eventreminder.app.util.Dates
import com.eventreminder.app.util.Speech
import com.eventreminder.app.util.Mailer
import com.eventreminder.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private const val BACKUP_NAME = "event_reminder_backup.json"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onQuit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(Prefs.email(ctx)) }
    var direct by remember { mutableStateOf(Prefs.directMode(ctx)) }
    var smtpHost by remember { mutableStateOf(Prefs.smtpHost(ctx)) }
    var smtpPort by remember { mutableStateOf(Prefs.smtpPort(ctx)) }
    var smtpUser by remember { mutableStateOf(Prefs.smtpUser(ctx)) }
    var smtpPass by remember { mutableStateOf("") }
    var passLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { smtpPass = withContext(Dispatchers.IO) { Prefs.smtpPass(ctx) }; passLoaded = true }
    LaunchedEffect(smtpPass) {                       // save (encrypt) 0.5 s after the last keystroke
        if (passLoaded) { delay(500); withContext(Dispatchers.IO) { Prefs.setSmtpPass(ctx, smtpPass) } }
    }
    val latestPass by rememberUpdatedState(smtpPass)
    DisposableEffect(Unit) { onDispose { if (passLoaded) Prefs.setSmtpPass(ctx, latestPass) } }
    var sending by remember { mutableStateOf(false) }
    var mailStatus by remember { mutableStateOf("") }
    var picking by remember { mutableIntStateOf(-1) }
    var refresh by remember { mutableIntStateOf(0) }

    // ---- Voice announcement ----
    var speak by remember { mutableStateOf(Prefs.speak(ctx)) }
    var voiceStatus by remember { mutableStateOf("") }
    val ttsHolder = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) { onDispose { ttsHolder[0]?.shutdown() } }

    fun hearSample() {
        ttsHolder[0]?.shutdown(); ttsHolder[0] = null
        voiceStatus = "Loading voice…"
        val today = Dates.today()
        val sample = Event(title = "Anna's birthday", category = 0, date = today + 1, originDate = today + 1 - 365L * 30, yearly = true)
        var engine: TextToSpeech? = null
        engine = TextToSpeech(ctx.applicationContext) { st ->
            val t = engine
            if (st == TextToSpeech.SUCCESS && t != null) {
                Speech.chooseEnglish(t)
                voiceStatus = ""
                t.speak(Dates.announcement(sample, Dates.today()), TextToSpeech.QUEUE_FLUSH, null, "sample")
            } else {
                voiceStatus = "❌ No text-to-speech voice is available on this phone. Install “Speech Services by Google” from the Play Store."
            }
        }
        ttsHolder[0] = engine
    }

    // ---- Cloud backup (Google Drive via the system picker) ----
    var savedUri by remember { mutableStateOf(Prefs.backupUri(ctx)) }
    var lastBackup by remember { mutableLongStateOf(Prefs.lastBackup(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf(false) }

    fun doBackup(uri: Uri) {
        scope.launch {
            busy = true; status = "Backing up…"
            try {
                val n = BackupManager.backupTo(ctx, uri)
                lastBackup = Prefs.lastBackup(ctx)
                status = "✅ Backed up $n events"
            } catch (e: Exception) {
                // The saved file may have been deleted or access revoked: forget it so the user can pick again.
                Prefs.setBackupUri(ctx, null); savedUri = null
                status = "❌ ${e.message ?: "Backup failed"}. Tap “Back up now” to choose the location again."
            } finally { busy = false }
        }
    }

    fun doRestore(uri: Uri) {
        scope.launch {
            busy = true; status = "Restoring…"
            try {
                val n = BackupManager.restoreFrom(ctx, uri)
                status = "✅ Restored $n events"
            } catch (e: Exception) {
                status = "❌ ${e.message ?: "Restore failed"}"
            } finally { busy = false }
        }
    }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) { status = "Cancelled"; return@rememberLauncherForActivityResult }
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        Prefs.setBackupUri(ctx, uri); savedUri = uri
        doBackup(uri)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doRestore(uri) else status = "Cancelled"
    }

    val ringtone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && picking >= 0) {
            val uri = r.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            Prefs.setSound(ctx, picking, uri)
            refresh++
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("☁️  Backup & restore (Google Drive)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (savedUri == null)
                            "Tap “Back up now”, then choose Google Drive in the screen that opens and press Save. " +
                                "After that, every backup is a single tap."
                        else "Backups are saved to the file you chose. Tap “Back up now” to update it.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (lastBackup > 0) Text(
                        "Last backup: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastBackup)),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { savedUri?.let { doBackup(it) } ?: createFile.launch(BACKUP_NAME) },
                            enabled = !busy, modifier = Modifier.weight(1f)
                        ) { Text("Back up now") }
                        OutlinedButton(onClick = { confirmRestore = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text("Restore")
                        }
                    }
                    if (savedUri != null) TextButton(onClick = { createFile.launch(BACKUP_NAME) }, enabled = !busy) {
                        Text("Choose a different location")
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text("📧  Email my events", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = email, onValueChange = { email = it; Prefs.setEmail(ctx, it) },
                label = { Text("Send events to (your email address)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!direct, { direct = false; Prefs.setDirectMode(ctx, false) }, { Text("Use my mail app") })
                FilterChip(direct, { direct = true; Prefs.setDirectMode(ctx, true) }, { Text("Send directly") })
            }
            if (!direct) {
                Text(
                    "Opens your email app with events.csv and the database file attached. " +
                        "Needs an email app (Gmail, Outlook…) installed and signed in.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "Sends the email from this app, with no mail app needed. It uses a sender account that you provide below.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Gmail" to ("smtp.gmail.com" to 465), "Yahoo" to ("smtp.mail.yahoo.com" to 465)).forEach { (n, hp) ->
                        FilterChip(
                            selected = smtpHost == hp.first,
                            onClick = {
                                smtpHost = hp.first; smtpPort = hp.second.toString()
                                Prefs.setSmtp(ctx, "host", smtpHost); Prefs.setSmtp(ctx, "port", smtpPort)
                            },
                            label = { Text(n) }
                        )
                    }
                }
                OutlinedTextField(
                    smtpHost, { smtpHost = it; Prefs.setSmtp(ctx, "host", it) },
                    label = { Text("SMTP server") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    smtpPort, { smtpPort = it.filter(Char::isDigit); Prefs.setSmtp(ctx, "port", smtpPort) },
                    label = { Text("Port (465 SSL or 587 STARTTLS)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    smtpUser, { smtpUser = it; Prefs.setSmtp(ctx, "user", it) },
                    label = { Text("Sender email / username") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    smtpPass, { smtpPass = it },
                    label = { Text("App password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Gmail/Yahoo do not accept your normal password here. Turn on 2-Step Verification, create an " +
                        "“App password” in your account's security settings, and paste it above. The password is " +
                        "encrypted on this phone and is not included in any backup.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        sending = true; mailStatus = ""
                        if (direct) withContext(Dispatchers.IO) { Prefs.setSmtpPass(ctx, smtpPass) }
                        mailStatus = Mailer.send(ctx) ?: ""
                        sending = false
                    }
                },
                enabled = email.contains("@") && !sending, modifier = Modifier.fillMaxWidth()
            ) { Text("Send my events now") }
            if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (mailStatus.isNotEmpty()) Text(mailStatus, style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider()
            Text("🗣️  Voice announcement", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Read out the event name and date when the alarm rings",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium
                )
                Switch(speak, { speak = it; Prefs.setSpeak(ctx, it) })
            }
            OutlinedButton(onClick = ::hearSample, modifier = Modifier.fillMaxWidth()) { Text("▶  Hear a sample") }
            if (voiceStatus.isNotEmpty()) Text(voiceStatus, style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("🔔  Alarm sound per category", style = MaterialTheme.typography.titleMedium)
            Category.all.forEach { c ->
                val label by produceState("…", refresh) { value = withContext(Dispatchers.IO) { Prefs.soundTitle(ctx, c.id) } }
                OutlinedCard(
                    onClick = {
                        picking = c.id
                        ringtone.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Sound for ${c.label}")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Prefs.soundUri(ctx, c.id) as Parcelable?)
                        })
                    },
                    Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.emoji, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.label, style = MaterialTheme.typography.titleSmall, color = Color(c.color))
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("Change", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text(
                "Tip: on some phones (Xiaomi, Oppo, Vivo, Samsung) allow the app to run in the background / " +
                    "disable battery optimisation so alarms are never delayed.",
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider()
            OutlinedButton(onClick = onQuit, modifier = Modifier.fillMaxWidth()) { Text("⏻  Quit app") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore a backup?") },
            text = { Text("Pick your backup file (event_reminder_backup.json) from Google Drive. " +
                "All events currently on this phone will be replaced.") },
            confirmButton = { TextButton({ confirmRestore = false; openFile.launch(arrayOf("*/*")) }) { Text("Choose file") } },
            dismissButton = { TextButton({ confirmRestore = false }) { Text("Cancel") } }
        )
    }
}
