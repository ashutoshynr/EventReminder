package com.eventreminder.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import com.eventreminder.app.alarm.AlarmScheduler
import com.eventreminder.app.data.AppDatabase
import com.eventreminder.app.data.Event
import com.eventreminder.app.util.Dates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

sealed interface ListItem {
    data class Header(val label: String) : ListItem
    data class Row(val event: Event) : ListItem
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).eventDao()
    private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

    val category = MutableStateFlow(-1)       // -1 = all
    val showPast = MutableStateFlow(false)

    /** Paged (20 rows at a time), sorted by date, with month headers. Cached across rotation. */
    val events: Flow<PagingData<ListItem>> = combine(category, showPast) { c, p -> c to p }
        .flatMapLatest { (c, p) ->
            Pager(PagingConfig(pageSize = 20, initialLoadSize = 20, prefetchDistance = 10, enablePlaceholders = false)) {
                val today = Dates.today()
                if (p) dao.past(today, c) else dao.upcoming(today, c)
            }.flow
        }
        .map { pd ->
            pd.map { ListItem.Row(it) }
                .insertSeparators<ListItem.Row, ListItem> { before, after ->
                    if (after == null) null else {
                        val m = YearMonth.from(LocalDate.ofEpochDay(after.event.date))
                        if (before == null || YearMonth.from(LocalDate.ofEpochDay(before.event.date)) != m)
                            ListItem.Header(m.format(monthFmt)) else null
                    }
                }
        }
        .cachedIn(viewModelScope)

    // Re-arm alarms in the background, after the first screen is already on display.
    init { viewModelScope.launch(Dispatchers.IO) { delay(2000); runCatching { AlarmScheduler.rescheduleAll(app) } } }

    fun refresh() { viewModelScope.launch(Dispatchers.IO) { AlarmScheduler.rollForward(dao) } }

    /** One-line status shown on the opening card. */
    suspend fun summary(): String = withContext(Dispatchers.IO) {
        AlarmScheduler.rollForward(dao)
        val t = Dates.today()
        val today = dao.countBetween(t, t)
        val week = dao.countBetween(t + 1, t + 7)
        fun s(n: Int) = if (n == 1) "event" else "events"
        when {
            today > 0 -> "🎉 $today ${s(today)} today!"
            week > 0 -> "📅 $week ${s(week)} in the next 7 days"
            dao.countAll() > 0 -> "😌 Nothing in the next 7 days"
            else -> "✨ Add your first event to get started"
        }
    }

    suspend fun load(id: Long): Event? = dao.get(id)

    fun save(e: Event, done: () -> Unit) {
        viewModelScope.launch {
            val id = if (e.id == 0L) dao.insert(e) else { dao.update(e); e.id }
            runCatching { AlarmScheduler.schedule(app, e.copy(id = id)) }
            done()
        }
    }

    fun delete(e: Event, done: () -> Unit) {
        viewModelScope.launch {
            dao.delete(e)
            AlarmScheduler.cancelAll(app, e.id)
            done()
        }
    }
}
