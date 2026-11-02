package com.eventreminder.app.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.eventreminder.app.data.AppDatabase
import com.eventreminder.app.data.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

object Exporter {
    const val BODY = "Attached: events.csv (readable in Excel/Sheets) and events_backup.db (database file)."

    /**
     * Opens an email app with the files attached. Returns null on success, or a message to show the user.
     * Only real email apps are offered (mailto selector); if none is installed we fall back to the general
     * share sheet and tell the user about "Send directly".
     */
    suspend fun sendViaApp(ctx: Context, email: String): String? {
        val (csv, db) = withContext(Dispatchers.IO) { build(ctx) }
        val auth = "${ctx.packageName}.fileprovider"
        val uris = arrayListOf(FileProvider.getUriForFile(ctx, auth, csv), FileProvider.getUriForFile(ctx, auth, db))
        val mailto = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        val hasMailApp = ctx.packageManager.queryIntentActivities(mailto, 0).isNotEmpty()

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, "My events backup - ${LocalDate.now()}")
            putExtra(Intent.EXTRA_TEXT, BODY)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri("events", uris[0]).also { c -> c.addItem(ClipData.Item(uris[1])) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (hasMailApp) selector = mailto            // show email apps only
        }
        return try {
            ctx.startActivity(Intent.createChooser(intent, "Send events to $email").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (hasMailApp) null
            else "No email app found on this phone. Install/sign in to one, or switch to “Send directly” in Settings."
        } catch (e: Exception) {
            "No app could handle the email. Switch to “Send directly” in Settings."
        }
    }

    /** Creates events.csv and a consistent copy of the database in the cache folder. */
    suspend fun build(ctx: Context): Pair<File, File> {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val database = AppDatabase.get(ctx)
        val events = database.eventDao().all()

        val csv = File(dir, "events.csv")
        csv.bufferedWriter().use { w ->
            w.appendLine("Category,Title,Date,Original Date,Repeats Yearly,Notes,Alarm,Days Before,Alarm Time,Snooze (min)")
            for (e in events) {
                fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
                w.appendLine(
                    listOf(
                        q(Category.of(e.category).label), q(e.title),
                        LocalDate.ofEpochDay(e.date), LocalDate.ofEpochDay(e.originDate),
                        if (e.yearly) "Yes" else "No", q(e.notes),
                        if (e.alarmEnabled) "On" else "Off", e.alarmDaysBefore,
                        Dates.timeLabel(e.alarmMinuteOfDay), e.snoozeMinutes
                    ).joinToString(",")
                )
            }
        }
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val db = File(dir, "events_backup.db")
        ctx.getDatabasePath(AppDatabase.NAME).copyTo(db, overwrite = true)
        return csv to db
    }
}
