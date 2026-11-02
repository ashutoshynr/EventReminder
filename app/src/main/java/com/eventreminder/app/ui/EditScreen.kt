package com.eventreminder.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.eventreminder.app.data.Category
import com.eventreminder.app.data.Event
import com.eventreminder.app.util.Dates
import com.eventreminder.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val DAY_OPTIONS = listOf(0 to "On the day", 1 to "1 day before", 2 to "2 days before", 3 to "3 days before",
    5 to "5 days before", 7 to "1 week before", 14 to "2 weeks before", 30 to "1 month before")
private val SNOOZE_OPTIONS = listOf(5 to "5 minutes", 10 to "10 minutes", 15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(vm: MainViewModel, id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var title by rememberSaveable { mutableStateOf("") }
    var catId by rememberSaveable { mutableIntStateOf(0) }
    var day by rememberSaveable { mutableLongStateOf(Dates.today()) }
    var yearly by rememberSaveable { mutableStateOf(true) }
    var notes by rememberSaveable { mutableStateOf("") }
    var alarmOn by rememberSaveable { mutableStateOf(true) }
    var daysBefore by rememberSaveable { mutableIntStateOf(1) }
    var minute by rememberSaveable { mutableIntStateOf(540) }
    var snooze by rememberSaveable { mutableIntStateOf(10) }
    var createdAt by rememberSaveable { mutableLongStateOf(0L) }
    var loaded by rememberSaveable { mutableStateOf(id == 0L) }

    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var titleError by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (!loaded) vm.load(id)?.let { e ->
            title = e.title; catId = e.category; day = e.originDate; yearly = e.yearly; notes = e.notes
            alarmOn = e.alarmEnabled; daysBefore = e.alarmDaysBefore; minute = e.alarmMinuteOfDay
            snooze = e.snoozeMinutes; createdAt = e.createdAt; loaded = true
        }
    }

    val cat = Category.of(catId)
    val soundName by produceState("…", catId) { value = withContext(Dispatchers.IO) { Prefs.soundTitle(ctx, catId) } }
    val color = Color(cat.color)

    fun build() = Event(
        id = id, title = title.trim(), category = catId,
        date = if (yearly) Dates.nextOccurrence(day, Dates.today()) else day,
        originDate = day, yearly = yearly, notes = notes.trim(),
        alarmEnabled = alarmOn, alarmDaysBefore = daysBefore, alarmMinuteOfDay = minute, snoozeMinutes = snooze,
        createdAt = if (createdAt == 0L) System.currentTimeMillis() else createdAt
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == 0L) "New event" else "Edit event") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (id != 0L) IconButton({ confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Colourful header that follows the chosen category
            Box(
                Modifier.fillMaxWidth().clip18().background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.55f))))
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cat.emoji, fontSize = 40.sp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(cat.single, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(Dates.fmt(day), color = Color.White.copy(alpha = 0.9f))
                    }
                }
            }

            OutlinedTextField(
                value = title, onValueChange = { title = it; titleError = false },
                label = { Text("Title / Name") }, singleLine = true, isError = titleError,
                supportingText = { if (titleError) Text("Please enter a title") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth()
            )

            Dropdown(
                "Category", Category.all.map { it.id to "${it.emoji}  ${it.label}" }, catId
            ) { catId = it; if (id == 0L) yearly = it != Category.OTHER.id }

            OutlinedCard(onClick = { showDate = true }, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Date", style = MaterialTheme.typography.labelMedium)
                    Text("📅  ${Dates.fmt(day)}", style = MaterialTheme.typography.titleMedium)
                }
            }

            SwitchRow("Repeat every year", yearly) { yearly = it }

            OutlinedTextField(
                value = notes, onValueChange = { notes = it }, label = { Text("Notes (optional)") },
                minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()
            )

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SwitchRow("🔔  Alarm", alarmOn) { alarmOn = it }
                    if (alarmOn) {
                        Dropdown("Start ringing", DAY_OPTIONS, daysBefore) { daysBefore = it }
                        OutlinedCard(onClick = { showTime = true }, Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Ring at", style = MaterialTheme.typography.labelMedium)
                                Text("⏰  ${Dates.timeLabel(minute)}", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Dropdown("Snooze for", SNOOZE_OPTIONS, snooze) { snooze = it }
                        Text(
                            "Sound: ${soundName}  (change per category in Settings)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Rings daily from ${DAY_OPTIONS.firstOrNull { it.first == daysBefore }?.second?.lowercase()} " +
                                "until the event day.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Button(
                onClick = {
                    if (title.isBlank()) titleError = true else vm.save(build(), onBack)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = color)
            ) { Text("Save event", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = day * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { day = Math.floorDiv(it, 86_400_000L) }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ showDate = false }) { Text("Cancel") } }
        ) { DatePicker(state) }
    }

    if (showTime) {
        val state = rememberTimePickerState(minute / 60, minute % 60, false)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = { TextButton({ minute = state.hour * 60 + state.minute; showTime = false }) { Text("OK") } },
            dismissButton = { TextButton({ showTime = false }) { Text("Cancel") } },
            text = { TimePicker(state) }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete event?") },
            text = { Text("“${title}” and its alarm will be removed.") },
            confirmButton = { TextButton({ confirmDelete = false; vm.delete(build(), onBack) }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

private fun Modifier.clip18() = this.then(Modifier.clip(RoundedCornerShape(18.dp)))

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "",
            onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) ->
                DropdownMenuItem(text = { Text(l) }, onClick = { onSelect(v); open = false })
            }
        }
    }
}
