package com.eventreminder.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_ID, -1)
        if (id < 0) return
        val app = context.applicationContext
        val started = try {
            ContextCompat.startForegroundService(
                app,
                Intent(app, AlarmService::class.java)
                    .setAction(AlarmService.ACTION_RING)
                    .putExtra(AlarmScheduler.EXTRA_ID, id)
            )
            true
        } catch (e: Exception) { false }

        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!started) AlarmService.postFallback(app, id)
                AlarmScheduler.rescheduleOne(app, id)     // queue tomorrow's ring (or next year's)
            } catch (e: Exception) {
                // never crash from a background alarm
            } finally {
                pr.finish()
            }
        }
    }
}
