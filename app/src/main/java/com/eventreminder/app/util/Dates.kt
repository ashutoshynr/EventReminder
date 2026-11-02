package com.eventreminder.app.util

import com.eventreminder.app.data.Category
import com.eventreminder.app.data.Event
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Dates {
    private val dateFmt = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy")
    private val timeFmt = DateTimeFormatter.ofPattern("hh:mm a")
    private val spokenFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)

    fun today(): Long = LocalDate.now().toEpochDay()
    fun fmt(day: Long): String = LocalDate.ofEpochDay(day).format(dateFmt)
    fun timeLabel(minuteOfDay: Int): String = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(timeFmt)
    fun year(day: Long): Int = LocalDate.ofEpochDay(day).year

    /** First occurrence of origin's month/day that is >= from (Feb 29 -> Feb 28 in non-leap years). */
    fun nextOccurrence(origin: Long, from: Long): Long {
        if (origin >= from) return origin
        val o = LocalDate.ofEpochDay(origin)
        val y = LocalDate.ofEpochDay(from).year
        val c = safe(y, o).toEpochDay()
        return if (c >= from) c else safe(y + 1, o).toEpochDay()
    }

    private fun safe(y: Int, o: LocalDate): LocalDate {
        val first = LocalDate.of(y, o.month, 1)
        return first.withDayOfMonth(minOf(o.dayOfMonth, first.lengthOfMonth()))
    }

    fun occurrence(e: Event, today: Long): Long =
        if (e.yearly) nextOccurrence(e.originDate, today) else e.date

    fun countdown(d: Long): String = when {
        d == 0L -> "Today"
        d == 1L -> "Tomorrow"
        d > 1 -> "in $d days"
        d == -1L -> "Yesterday"
        else -> "${-d} days ago"
    }

    fun ordinal(n: Int): String = n.toString() +
        if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

    /** "Turns 30", "5th anniversary", "12 years" ... */
    fun milestone(e: Event, day: Long = e.date): String? {
        if (!e.yearly) return null
        val years = year(day) - year(e.originDate)
        if (years <= 0) return null
        return when (Category.of(e.category)) {
            Category.BIRTHDAY -> "Turns $years"
            Category.ANNIVERSARY -> "${ordinal(years)} anniversary"
            else -> "$years years"
        }
    }

    /** The sentence the alarm reads aloud, e.g. "Reminder: Anna's birthday. This birthday is on Saturday, 14 November 2026, in 3 days. Turns 30." */
    fun announcement(e: Event, today: Long): String {
        val cat = Category.of(e.category)
        val day = occurrence(e, today)
        val left = day - today
        val date = LocalDate.ofEpochDay(day).format(spokenFmt)
        val what = cat.single.lowercase(Locale.ENGLISH)
        val sentence = when {
            left <= 0L -> "This $what is today, $date."
            left == 1L -> "This $what is tomorrow, $date."
            else -> "This $what is on $date, in $left days."
        }
        val extra = if (cat == Category.BIRTHDAY || cat == Category.ANNIVERSARY) milestone(e, day)?.let { " $it." } ?: "" else ""
        return "Reminder: ${e.title}. $sentence$extra"
    }
}
