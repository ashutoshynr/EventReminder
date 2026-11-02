package com.eventreminder.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.eventreminder.app.data.AppDatabase
import com.eventreminder.app.data.Event
import com.eventreminder.app.data.EventDao
import com.eventreminder.app.ui.MainActivity
import com.eventreminder.app.util.Dates
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Alarm rule: starting `alarmDaysBefore` days before the event, the alarm rings once a day at the chosen
 * time until (and including) the event day. Snooze re-rings after `snoozeMinutes`.
 * Uses setAlarmClock(): exact, survives Doze and needs no special permission.
 */
object AlarmScheduler {
    const val ACTION = "com.eventreminder.app.ALARM"
    const val EXTRA_ID = "event_id"

    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(c: Context, id: Long, snooze: Boolean): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
            .setAction(ACTION)
            .setData(Uri.parse("eventalarm://$id/${if (snooze) "snooze" else "main"}"))
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAt(c: Context, id: Long, at: Long, snooze: Boolean) {
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val a = am(c)
        val pi = pending(c, id, snooze)
        val exactAllowed = Build.VERSION.SDK_INT < 31 || a.canScheduleExactAlarms()
        try {
            if (exactAllowed) a.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            // Exact alarms refused by the system/user: still ring, just possibly a few minutes late.
            runCatching { a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    fun schedule(c: Context, e: Event) {
        am(c).cancel(pending(c, e.id, false))
        val t = nextTrigger(e, System.currentTimeMillis()) ?: return
        setAt(c, e.id, t, false)
    }

    fun cancelAll(c: Context, id: Long) {
        am(c).cancel(pending(c, id, false))
        am(c).cancel(pending(c, id, true))
    }

    fun scheduleSnooze(c: Context, id: Long, minutes: Int) =
        setAt(c, id, System.currentTimeMillis() + minutes * 60_000L, true)

    suspend fun rescheduleOne(c: Context, id: Long) {
        AppDatabase.get(c).eventDao().get(id)?.let { schedule(c, it) }
    }

    suspend fun rescheduleAll(c: Context) {
        val dao = AppDatabase.get(c).eventDao()
        rollForward(dao)
        dao.withAlarms().forEach { schedule(c, it) }
    }

    /** Moves yearly events whose date has passed to their next occurrence. */
    suspend fun rollForward(dao: EventDao) {
        val today = Dates.today()
        dao.staleYearly(today).forEach { dao.update(it.copy(date = Dates.nextOccurrence(it.originDate, today))) }
    }

    fun nextTrigger(e: Event, now: Long): Long? {
        if (!e.alarmEnabled) return null
        var eventDay = e.date
        repeat(2) {
            triggerFor(eventDay, e, now)?.let { return it }
            if (!e.yearly) return null
            eventDay = Dates.nextOccurrence(e.originDate, eventDay + 1)
        }
        return null
    }

    private fun triggerFor(eventDay: Long, e: Event, now: Long): Long? {
        val zone = ZoneId.systemDefault()
        val time = LocalTime.of(e.alarmMinuteOfDay / 60, e.alarmMinuteOfDay % 60)
        var d = maxOf(eventDay - e.alarmDaysBefore, Dates.today())
        while (d <= eventDay) {
            val t = LocalDate.ofEpochDay(d).atTime(time).atZone(zone).toInstant().toEpochMilli()
            if (t > now) return t
            d++
        }
        return null
    }
}
