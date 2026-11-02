package com.eventreminder.app.data

import androidx.paging.PagingSource
import androidx.room.*

@Dao
interface EventDao {
    @Insert suspend fun insert(e: Event): Long
    @Update suspend fun update(e: Event)
    @Delete suspend fun delete(e: Event)

    @Query("SELECT * FROM events WHERE id = :id") suspend fun get(id: Long): Event?

    @Query(
        "SELECT * FROM events WHERE date >= :today AND (:cat = -1 OR category = :cat) " +
            "ORDER BY date ASC, title COLLATE NOCASE ASC"
    )
    fun upcoming(today: Long, cat: Int): PagingSource<Int, Event>

    @Query(
        "SELECT * FROM events WHERE date < :today AND (:cat = -1 OR category = :cat) " +
            "ORDER BY date DESC, title COLLATE NOCASE ASC"
    )
    fun past(today: Long, cat: Int): PagingSource<Int, Event>

    @Query("SELECT * FROM events ORDER BY date ASC") suspend fun all(): List<Event>
    @Query("SELECT * FROM events WHERE alarmEnabled = 1") suspend fun withAlarms(): List<Event>
    @Query("SELECT * FROM events WHERE yearly = 1 AND date < :today") suspend fun staleYearly(today: Long): List<Event>

    @Insert suspend fun insertAll(list: List<Event>)
    @Query("DELETE FROM events") suspend fun deleteAll()
    @Query("SELECT COUNT(*) FROM events") suspend fun countAll(): Int
    @Query("SELECT COUNT(*) FROM events WHERE date BETWEEN :from AND :to") suspend fun countBetween(from: Long, to: Long): Int
}
