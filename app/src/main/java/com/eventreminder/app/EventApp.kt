package com.eventreminder.app

import android.app.Application
import com.eventreminder.app.data.AppDatabase
import kotlin.concurrent.thread

class EventApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Open the database in the background so the first screen never waits for it.
        thread(name = "warmup") {
            runCatching { AppDatabase.get(this).openHelper.writableDatabase }
            runCatching { com.eventreminder.app.data.Category.all.forEach { com.eventreminder.app.util.Prefs.soundTitle(this, it.id) } }
        }
    }
}
