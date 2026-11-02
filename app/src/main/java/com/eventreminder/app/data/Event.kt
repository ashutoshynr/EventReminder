package com.eventreminder.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class Category(val id: Int, val label: String, val single: String, val emoji: String, val color: Long) {
    BIRTHDAY(0, "Birthdays", "Birthday", "🎂", 0xFFE91E63),
    ANNIVERSARY(1, "Anniversaries", "Anniversary", "💍", 0xFF8E24AA),
    FESTIVAL(2, "Festivals", "Festival", "🎉", 0xFFFF9800),
    OTHER(3, "Others", "Event", "📌", 0xFF009688);

    companion object {
        val all: List<Category> = values().toList()
        fun of(id: Int): Category = values().firstOrNull { it.id == id } ?: OTHER
    }
}

/**
 * date       = the date the list is sorted on (for yearly events: the NEXT occurrence)
 * originDate = the date the user entered (e.g. the actual birth date), used for age/years
 * Dates are stored as epoch-days (Long) -> compact, indexed and fast to sort.
 */
@Entity(
    tableName = "events",
    indices = [Index("date"), Index("category", "date")]
)
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: Int,
    val date: Long,
    val originDate: Long,
    val yearly: Boolean,
    val notes: String = "",
    val alarmEnabled: Boolean = false,
    val alarmDaysBefore: Int = 1,
    val alarmMinuteOfDay: Int = 540,   // 09:00
    val snoozeMinutes: Int = 10,
    val createdAt: Long = System.currentTimeMillis()
)
