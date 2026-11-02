package com.eventreminder.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.eventreminder.app.R
import com.eventreminder.app.data.AppDatabase
import com.eventreminder.app.data.Category
import com.eventreminder.app.data.Event
import com.eventreminder.app.ui.MainActivity
import com.eventreminder.app.util.Dates
import com.eventreminder.app.util.Prefs
import com.eventreminder.app.util.Speech
import kotlinx.coroutines.*

/** Plays the category's sound (looping up to 60 s) with Snooze / Dismiss notification actions. */
class AlarmService : Service() {
    companion object {
        const val ACTION_RING = "ring"
        const val ACTION_SNOOZE = "snooze"
        const val ACTION_DISMISS = "dismiss"
        const val EXTRA_SNOOZE_MIN = "snooze_min"
        private const val CHANNEL = "event_alarms"
        private const val NOTIF_ID = 4242
        private const val MAX_RING_MS = 60_000L
        private const val INTRO_MS = 2_500L        // let the alarm sound be heard first, then speak
        private const val REPEAT_GAP_MS = 5_000L   // pause between announcements
        private const val MAX_SPEAKS = 4
        private const val DUCK = 0.25f             // alarm sound volume while speaking
        private const val FALLBACK_CHANNEL = "event_alarms_fallback"

        /** Plain heads-up notification with the alarm sound; used if the system won't let us start the ringing service. */
        suspend fun postFallback(ctx: Context, id: Long) {
            val e = AppDatabase.get(ctx).eventDao().get(id) ?: return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(FALLBACK_CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(FALLBACK_CHANNEL, "Event reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                        )
                    }
                )
            }
            val cat = Category.of(e.category)
            val today = Dates.today()
            val day = Dates.occurrence(e, today)
            val left = day - today
            val text = when {
                left <= 0L -> "${cat.single} is today! 🎈"
                left == 1L -> "Tomorrow • ${Dates.fmt(day)}"
                else -> "In $left days • ${Dates.fmt(day)}"
            }
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, FALLBACK_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${cat.emoji} ${e.title}")
                .setContentText(text)
                .setColor(cat.color.toInt())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(100_000 + e.id.toInt(), n) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private val autoSilence = Runnable { silence(); ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH); stopSelf() }

    // ---------- Voice announcement ----------
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var phrase: String? = null
    private var speechScheduled = false
    private var speakCount = 0
    private var speakErrors = 0
    private var ringStart = 0L
    private val speakRunnable = Runnable { speakNow() }

    /** Starts the speech engine immediately (it takes a moment), in parallel with loading the event. */
    private fun initSpeech() {
        if (tts != null || !Prefs.speak(this)) return
        tts = TextToSpeech(applicationContext) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                t.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                Speech.chooseEnglish(t)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { handler.post { speechFinished(true) } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { handler.post { speechFinished(false) } }
                    override fun onError(utteranceId: String?, errorCode: Int) { handler.post { speechFinished(false) } }
                })
                ttsReady = true
                maybeStartSpeaking()
            }
        }
    }

    private fun maybeStartSpeaking() {
        if (speechScheduled || !ttsReady || phrase == null) return
        speechScheduled = true
        val wait = (ringStart + INTRO_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        handler.postDelayed(speakRunnable, wait)
    }

    private fun speakNow() {
        val t = tts ?: return
        val p = phrase ?: return
        speakCount++
        runCatching { player?.setVolume(DUCK, DUCK) }
        if (t.speak(p, TextToSpeech.QUEUE_FLUSH, null, "alarm") != TextToSpeech.SUCCESS) speechFinished(false)
    }

    private fun speechFinished(ok: Boolean) {
        runCatching { player?.setVolume(1f, 1f) }
        if (ok) speakErrors = 0 else speakErrors++
        if (speakCount < MAX_SPEAKS && speakErrors < 2) handler.postDelayed(speakRunnable, REPEAT_GAP_MS)
    }

    private fun stopSpeech() {
        handler.removeCallbacks(speakRunnable)
        tts?.stop()
    }

    private fun resetSpeech() {
        stopSpeech()
        phrase = null; speechScheduled = false; speakCount = 0; speakErrors = 0
        ringStart = SystemClock.elapsedRealtime()
    }

    private fun releaseSpeech() {
        stopSpeech()
        tts?.shutdown()
        tts = null; ttsReady = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(AlarmScheduler.EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_RING -> ring(id)
            ACTION_SNOOZE -> {
                val min = intent.getIntExtra(EXTRA_SNOOZE_MIN, 10)
                AlarmScheduler.scheduleSnooze(this, id, min)
                stopAlarm()
            }
            else -> stopAlarm()
        }
        return START_NOT_STICKY
    }

    private fun ring(id: Long) {
        createChannel()
        val placeholder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Event reminder").build()
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            val app = applicationContext
            CoroutineScope(Dispatchers.IO).launch { postFallback(app, id) }
            stopSelf()
            return
        }

        resetSpeech()
        initSpeech()

        scope.launch {
            val e = withContext(Dispatchers.IO) { AppDatabase.get(applicationContext).eventDao().get(id) }
            if (e == null) { stopAlarm(); return@launch }
            NotificationManagerCompat.from(this@AlarmService).let {
                runCatching { it.notify(NOTIF_ID, buildNotification(e)) }
            }
            if (Prefs.speak(this@AlarmService)) phrase = Dates.announcement(e, Dates.today())
            play(Prefs.soundUri(this@AlarmService, e.category))
            vibrate()
            handler.removeCallbacks(autoSilence)
            handler.postDelayed(autoSilence, MAX_RING_MS)
            maybeStartSpeaking()
        }
    }

    private fun buildNotification(e: Event): Notification {
        val cat = Category.of(e.category)
        val today = Dates.today()
        val day = Dates.occurrence(e, today)
        val left = day - today
        val text = when {
            left <= 0L -> "${cat.single} is today! 🎈"
            left == 1L -> "Tomorrow • ${Dates.fmt(day)}"
            else -> "In $left days • ${Dates.fmt(day)}"
        }
        fun svc(action: String, code: Int) = PendingIntent.getService(
            this, code,
            Intent(this, AlarmService::class.java).setAction(action)
                .putExtra(AlarmScheduler.EXTRA_ID, e.id).putExtra(EXTRA_SNOOZE_MIN, e.snoozeMinutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${cat.emoji} ${e.title}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (e.notes.isBlank()) text else "$text\n${e.notes}"))
            .setColor(cat.color.toInt())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Snooze ${e.snoozeMinutes} min", svc(ACTION_SNOOZE, 1))
            .addAction(0, "Dismiss", svc(ACTION_DISMISS, 2))
            .build()
    }

    private fun play(uri: android.net.Uri?) {
        silence()
        uri ?: return
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()          // do not block the main thread while the sound loads
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 400), 0))
    }

    private fun silence() {
        handler.removeCallbacks(autoSilence)
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        player = null
        stopSpeech()
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
    }

    private fun stopAlarm() {
        silence()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIF_ID)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Event alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)   // sound is played by the service (per-category)
                    enableVibration(false)
                    description = "Birthday, anniversary and festival reminders"
                }
            )
        }
    }

    override fun onDestroy() {
        silence()
        releaseSpeech()
        scope.cancel()
        super.onDestroy()
    }
}
