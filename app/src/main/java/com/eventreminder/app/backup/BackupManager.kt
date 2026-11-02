package com.eventreminder.app.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.eventreminder.app.alarm.AlarmScheduler
import com.eventreminder.app.data.AppDatabase
import com.eventreminder.app.data.Event
import com.eventreminder.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Backup/restore through Android's document picker (Storage Access Framework).
 * The user picks "Drive" (or any other location) in the system picker, so no Google Cloud setup,
 * API keys or internet permission are needed. The backup is a small JSON file.
 */
object BackupManager {

    /** Writes all events to [uri]; returns the number of events saved. */
    suspend fun backupTo(ctx: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val events = AppDatabase.get(ctx).eventDao().all()
        val bytes = toJson(events).toByteArray(Charsets.UTF_8)
        val cr = ctx.contentResolver
        val out = try { cr.openOutputStream(uri, "wt") } catch (e: Exception) { cr.openOutputStream(uri, "w") }
            ?: throw IOException("Cannot open the backup file")
        out.use { it.write(bytes) }
        Prefs.setLastBackup(ctx, System.currentTimeMillis())
        events.size
    }

    /** Replaces all events with the ones in [uri]; returns how many were restored. */
    suspend fun restoreFrom(ctx: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Cannot open the selected file")
        val events = try { fromJson(text) } catch (e: Exception) { throw IOException("This is not a valid Event Reminder backup") }
        val db = AppDatabase.get(ctx)
        val dao = db.eventDao()
        dao.all().forEach { AlarmScheduler.cancelAll(ctx, it.id) }
        db.withTransaction { dao.deleteAll(); dao.insertAll(events) }
        AlarmScheduler.rescheduleAll(ctx)
        events.size
    }

    private fun toJson(events: List<Event>): String {
        val arr = JSONArray()
        events.forEach { e ->
            arr.put(JSONObject()
                .put("id", e.id).put("title", e.title).put("category", e.category)
                .put("date", e.date).put("originDate", e.originDate).put("yearly", e.yearly)
                .put("notes", e.notes).put("alarmEnabled", e.alarmEnabled)
                .put("alarmDaysBefore", e.alarmDaysBefore).put("alarmMinuteOfDay", e.alarmMinuteOfDay)
                .put("snoozeMinutes", e.snoozeMinutes).put("createdAt", e.createdAt))
        }
        return JSONObject().put("app", "EventReminder").put("version", 1)
            .put("exportedAt", System.currentTimeMillis()).put("events", arr).toString()
    }

    private fun fromJson(text: String): List<Event> {
        val root = JSONObject(text)
        if (root.optString("app") != "EventReminder") throw IOException("bad file")
        val arr = root.getJSONArray("events")
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Event(
                id = o.getLong("id"), title = o.getString("title"), category = o.getInt("category"),
                date = o.getLong("date"), originDate = o.getLong("originDate"), yearly = o.getBoolean("yearly"),
                notes = o.optString("notes", ""), alarmEnabled = o.optBoolean("alarmEnabled", false),
                alarmDaysBefore = o.optInt("alarmDaysBefore", 1), alarmMinuteOfDay = o.optInt("alarmMinuteOfDay", 540),
                snoozeMinutes = o.optInt("snoozeMinutes", 10), createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}
