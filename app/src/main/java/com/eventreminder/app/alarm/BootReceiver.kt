package com.eventreminder.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Re-arms every alarm after reboot, app update, or time/timezone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { AlarmScheduler.rescheduleAll(app) } finally { pr.finish() }
        }
    }
}
