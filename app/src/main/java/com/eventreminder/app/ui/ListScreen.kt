package com.eventreminder.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.eventreminder.app.data.Category
import com.eventreminder.app.data.Event
import com.eventreminder.app.util.Dates
import com.eventreminder.app.util.Mailer
import com.eventreminder.app.util.Prefs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(vm: MainViewModel, onAdd: () -> Unit, onEdit: (Long) -> Unit, onSettings: () -> Unit) {
    val items = vm.events.collectAsLazyPagingItems()
    val cat by vm.category.collectAsStateWithLifecycle()
    val past by vm.showPast.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = remember { Dates.today() }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🎈 Event Reminder", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            if (Prefs.email(ctx).isBlank()) onSettings()
                            else {
                                if (Prefs.directMode(ctx)) Toast.makeText(ctx, "Sending…", Toast.LENGTH_SHORT).show()
                                Mailer.send(ctx)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                            }
                        }
                    }) { Icon(Icons.Default.Email, "Email my events") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add event") }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(selected = cat == -1, onClick = { vm.category.value = -1 }, label = { Text("All") })
                }
                items(Category.all, key = { it.id }) { c ->
                    val col = Color(c.color)
                    FilterChip(
                        selected = cat == c.id,
                        onClick = { vm.category.value = c.id },
                        label = { Text("${c.emoji} ${c.label}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = col.copy(alpha = 0.25f)
                        )
                    )
                }
            }
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = !past, onClick = { vm.showPast.value = false }, label = { Text("Upcoming") })
                FilterChip(selected = past, onClick = { vm.showPast.value = true }, label = { Text("Past") })
            }

            Box(Modifier.weight(1f)) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        count = items.itemCount,
                        key = items.itemKey {
                            when (it) {
                                is ListItem.Row -> it.event.id
                                is ListItem.Header -> "h-" + it.label
                            }
                        },
                        contentType = items.itemContentType { it::class }
                    ) { index ->
                        when (val item = items[index]) {
                            is ListItem.Header -> Text(
                                item.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, start = 4.dp)
                            )
                            is ListItem.Row -> EventCard(item.event, today) { onEdit(item.event.id) }
                            null -> Spacer(Modifier.height(1.dp))
                        }
                    }
                }
                if (items.loadState.refresh is LoadState.NotLoading && items.itemCount == 0) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🗓️", fontSize = 56.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (past) "No past events" else "No upcoming events.\nTap “Add event” to create one.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCard(e: Event, today: Long, onClick: () -> Unit) {
    val cat = Category.of(e.category)
    val color = Color(cat.color)
    val days = e.date - today
    val countdown = remember(e.date, today) { Dates.countdown(days) }
    val milestone = remember(e) { Dates.milestone(e) }
    val surface = MaterialTheme.colorScheme.surface
    val bg = remember(cat, surface) { color.copy(alpha = 0.12f).compositeOver(surface) }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.drawBehind { drawRect(color, size = Size(6.dp.toPx(), size.height)) }.padding(start = 6.dp)) {
            Box(
                Modifier.padding(12.dp).size(46.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) { Text(cat.emoji, fontSize = 24.sp) }
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(
                    e.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(Dates.fmt(e.date), style = MaterialTheme.typography.bodySmall)
                val extra = listOfNotNull(
                    milestone,
                    if (e.alarmEnabled) "🔔 ${Dates.timeLabel(e.alarmMinuteOfDay)}" else null
                ).joinToString("  •  ")
                if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.labelMedium, color = color)
            }
            Box(
                Modifier.align(Alignment.CenterVertically).padding(end = 12.dp)
                    .clip(RoundedCornerShape(50)).background(color.copy(alpha = if (days == 0L) 0.9f else 0.2f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    countdown, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    color = if (days == 0L) Color.White else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
