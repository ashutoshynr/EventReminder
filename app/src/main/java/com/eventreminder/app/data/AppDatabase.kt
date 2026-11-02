package com.eventreminder.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Event::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        const val NAME = "events.db"
        @Volatile private var inst: AppDatabase? = null

        fun get(c: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build().also { inst = it }
        }
    }
}
