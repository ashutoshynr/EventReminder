package com.eventreminder.app.util

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    // Direct-send (SMTP) settings: separate file, deliberately excluded from Android cloud backup.
    private fun mail(c: Context) = c.getSharedPreferences("mail", Context.MODE_PRIVATE)
    fun directMode(c: Context) = mail(c).getBoolean("direct", false)
    fun setDirectMode(c: Context, v: Boolean) = mail(c).edit().putBoolean("direct", v).apply()
    fun smtpHost(c: Context) = mail(c).getString("host", "smtp.gmail.com") ?: ""
    fun smtpPort(c: Context) = mail(c).getString("port", "465") ?: "465"
    fun smtpUser(c: Context) = mail(c).getString("user", "") ?: ""
    /** Stored encrypted (key "pass_enc"). An old plain-text value from earlier versions is migrated on first read. */
    fun smtpPass(c: Context): String {
        val m = mail(c)
        m.getString("pass_enc", null)?.let { return SecureStore.decrypt(it) }
        val legacy = m.getString("pass", null) ?: return ""
        setSmtpPass(c, legacy)
        return legacy
    }
    fun setSmtpPass(c: Context, v: String) {
        val enc = runCatching { SecureStore.encrypt(v) }.getOrNull() ?: return   // never fall back to plain text
        mail(c).edit().putString("pass_enc", enc).remove("pass").apply()
    }
    fun setSmtp(c: Context, key: String, v: String) = mail(c).edit().putString(key, v).apply()

    /** Read out the event name and date when an alarm rings (default on). */
    fun speak(c: Context): Boolean = sp(c).getBoolean("speak", true)
    fun setSpeak(c: Context, v: Boolean) = sp(c).edit().putBoolean("speak", v).apply()

    /** Let the user say "snooze" / "dismiss" after the announcement (default on). */
    fun voiceCommands(c: Context): Boolean = sp(c).getBoolean("voice_cmd", true)
    fun setVoiceCommands(c: Context, v: Boolean) = sp(c).edit().putBoolean("voice_cmd", v).apply()
    /** Also play the category tone together with the voice (default off: voice replaces the tone). */
    fun toneAlso(c: Context): Boolean = sp(c).getBoolean("tone_also", false)
    fun setToneAlso(c: Context, v: Boolean) = sp(c).edit().putBoolean("tone_also", v).apply()

    fun email(c: Context): String = sp(c).getString("email", "") ?: ""
    fun setEmail(c: Context, v: String) = sp(c).edit().putString("email", v.trim()).apply()

    fun backupUri(c: Context): Uri? = sp(c).getString("backup_uri", null)?.let(Uri::parse)
    fun setBackupUri(c: Context, u: Uri?) = sp(c).edit().putString("backup_uri", u?.toString()).apply()
    fun lastBackup(c: Context): Long = sp(c).getLong("last_backup", 0L)
    fun setLastBackup(c: Context, t: Long) = sp(c).edit().putLong("last_backup", t).apply()

    /** null = silent. Each category has its own sound (default differs per category). */
    fun soundUri(c: Context, cat: Int): Uri? {
        val s = sp(c).getString("sound_$cat", null)
        return when {
            s == null -> defaultSound(c, cat)
            s.isEmpty() -> null
            else -> Uri.parse(s)
        }
    }

    private val titles = java.util.concurrent.ConcurrentHashMap<Int, String>()

    fun setSound(c: Context, cat: Int, uri: Uri?) {
        titles.remove(cat)
        sp(c).edit().putString("sound_$cat", uri?.toString() ?: "").apply()
    }

    fun soundTitle(c: Context, cat: Int): String {
        titles[cat]?.let { return it }
        val u = soundUri(c, cat) ?: return "Silent"
        val t = runCatching { RingtoneManager.getRingtone(c, u)?.getTitle(c) }.getOrNull() ?: "Custom sound"
        titles[cat] = t
        return t
    }

    private fun defaultSound(c: Context, cat: Int): Uri = when (cat) {
        0 -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        1 -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        2 -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        else -> runCatching {
            val rm = RingtoneManager(c).apply { setType(RingtoneManager.TYPE_NOTIFICATION) }
            rm.cursor.use { if (it.count > 3) rm.getRingtoneUri(3) else null }
        }.getOrNull() ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    }
}
