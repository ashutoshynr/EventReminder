package com.eventreminder.app.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.eventreminder.app.R
import com.eventreminder.app.data.AppDatabase
import com.eventreminder.app.data.Category
import com.eventreminder.app.data.Event
import com.eventreminder.app.ui.MainActivity
import com.eventreminder.app.util.Command
import com.eventreminder.app.util.Dates
import com.eventreminder.app.util.Prefs
import com.eventreminder.app.util.RecogAdapter
import com.eventreminder.app.util.Speech
import com.eventreminder.app.util.VoiceCommand
import kotlinx.coroutines.*
import java.util.Locale

/**
 * Rings an event alarm by VOICE: speaks the event name and date, then listens for "snooze" / "dismiss".
 * The category tone is only used if the user asked for it, if voice is switched off, or if voice fails.
 */
class AlarmService : Service() {
    companion object {
        const val ACTION_RING = "ring"
        const val ACTION_SNOOZE = "snooze"
        const val ACTION_DISMISS = "dismiss"
        const val EXTRA_SNOOZE_MIN = "snooze_min"
        private const val CHANNEL = "event_alarms"
        private const val NOTIF_ID = 4242
        private const val MAX_RING_MS = 90_000L     // overall limit for one ring
        private const val MAX_ROUNDS = 5            // announce + listen cycles
        private const val GAP_MS = 1_200L           // pause between cycles
        private const val LISTEN_MS = 7_000L        // how long to wait for a command
        private const val TTS_TIMEOUT_MS = 6_000L   // voice engine did not start -> use the tone
        private const val TONE_INTRO_MS = 2_500L
        private const val DUCK = 0.12f
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

    // current ring
    private var currentId = -1L
    private var currentSnooze = 10
    private var soundUri: Uri? = null
    private var eventLoaded = false
    private var finishing = false
    private var speakEnabled = true
    private var micAllowed = false
    private var micFgOk = false

    // tone
    private var player: MediaPlayer? = null
    private var toneActive = false

    // voice out
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceDead = false
    private var speechLocale: Locale = Locale.US
    private var fullPhrase: String? = null
    private var shortPhrase: String? = null
    private var round = 0
    private var roundScheduled = false
    private var speakErrors = 0
    private var ringStart = 0L

    // voice in
    private var recognizer: SpeechRecognizer? = null
    private var canListen = false
    private var listening = false
    private var preferOffline = true
    private var hardErrors = 0

    private val autoSilence = Runnable {
        stopAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }
    private val roundRunnable = Runnable { speakNow() }
    private val listenTimeout = Runnable {
        if (listening) { listening = false; runCatching { recognizer?.cancel() }; afterListen() }
    }
    private val ttsTimeout = Runnable { if (!ttsReady && speakEnabled) voiceFailed() }
    private val byeTimeout = Runnable { stopAlarm() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id: Long = intent?.getLongExtra(AlarmScheduler.EXTRA_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_RING -> ring(id)
            ACTION_SNOOZE -> {
                val min = intent?.getIntExtra(EXTRA_SNOOZE_MIN, 10) ?: 10
                AlarmScheduler.scheduleSnooze(this, id, min)
                stopAlarm()
            }
            else -> stopAlarm()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- ring
    private fun ring(id: Long) {
        createChannel()
        speakEnabled = Prefs.speak(this)
        micAllowed = speakEnabled && Prefs.voiceCommands(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val placeholder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Event reminder").build()
        if (!startForegroundSafely(placeholder)) {
            val app = applicationContext
            CoroutineScope(Dispatchers.IO).launch { postFallback(app, id) }
            stopSelf()
            return
        }

        stopAll()                       // clean slate if another ring was active
        currentId = id; eventLoaded = false; finishing = false; voiceDead = false
        round = 0; roundScheduled = false; speakErrors = 0; hardErrors = 0
        fullPhrase = null; shortPhrase = null; listening = false
        ringStart = SystemClock.elapsedRealtime()

        if (speakEnabled) {
            initSpeech()                // start the voice engine now, in parallel with loading the event
            handler.postDelayed(ttsTimeout, TTS_TIMEOUT_MS)
        }
        handler.postDelayed(autoSilence, MAX_RING_MS)

        scope.launch {
            val e = withContext(Dispatchers.IO) { AppDatabase.get(applicationContext).eventDao().get(id) }
            if (e == null) { stopAlarm(); return@launch }
            currentSnooze = e.snoozeMinutes
            soundUri = Prefs.soundUri(this@AlarmService, e.category)
            eventLoaded = true
            runCatching { NotificationManagerCompat.from(this@AlarmService).notify(NOTIF_ID, buildNotification(e)) }
            if (speakEnabled) {
                val prompt = if (canListen) " Say snooze or dismiss." else ""
                fullPhrase = Dates.announcement(e, Dates.today()) + prompt
                shortPhrase = "Reminder: ${e.title}.$prompt"
            }
            vibrateOnce()
            if (!speakEnabled || Prefs.toneAlso(this@AlarmService) || voiceDead) startTone()
            maybeStartRound()
        }
    }

    /** Tries to run as media + microphone service (needed for listening); falls back to media only. */
    private fun startForegroundSafely(n: Notification): Boolean {
        val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        micFgOk = false
        if (micAllowed) {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, n, media or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                micFgOk = true
                return true
            } catch (e: Exception) { /* not allowed right now: continue without voice commands */ }
        }
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, media)
            true
        } catch (e: Exception) { false }
    }

    // ---------------------------------------------------------------- voice out
    private fun initSpeech() {
        if (micAllowed && micFgOk && recognizer == null && SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(recListener) }
        }
        canListen = micAllowed && micFgOk && recognizer != null

        if (tts == null) {
            tts = TextToSpeech(applicationContext) { status ->
                val t = tts
                if (status == TextToSpeech.SUCCESS && t != null) {
                    t.setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                    )
                    speechLocale = Speech.chooseEnglish(t)
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { handler.post { onUtterance(utteranceId, true) } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { handler.post { onUtterance(utteranceId, false) } }
                        override fun onError(utteranceId: String?, errorCode: Int) { handler.post { onUtterance(utteranceId, false) } }
                    })
                    ttsReady = true
                    handler.removeCallbacks(ttsTimeout)
                    maybeStartRound()
                } else {
                    voiceFailed()
                }
            }
        }
    }

    private fun maybeStartRound() {
        if (roundScheduled || !ttsReady || fullPhrase == null || finishing || voiceDead) return
        roundScheduled = true
        val wait = if (toneActive) (ringStart + TONE_INTRO_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L) else 0L
        handler.postDelayed(roundRunnable, wait)
    }

    private fun speakNow() {
        if (finishing || voiceDead) return
        val t = tts ?: return
        val text = (if (round == 0) fullPhrase else shortPhrase) ?: return
        round++
        duck(true)
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "round") != TextToSpeech.SUCCESS) onUtterance("round", false)
    }

    private fun onUtterance(id: String?, ok: Boolean) {
        if (id == "bye") { stopAlarm(); return }
        if (finishing) return
        if (ok) speakErrors = 0 else if (++speakErrors >= 2) { voiceFailed(); return }
        if (canListen) startListening() else afterListen()
    }

    /** Voice is unusable: make sure the user still gets an audible alarm. */
    private fun voiceFailed() {
        if (voiceDead) return
        voiceDead = true
        handler.removeCallbacks(roundRunnable)
        runCatching { recognizer?.cancel() }
        listening = false
        if (eventLoaded) startTone()      // otherwise the tone starts as soon as the event is loaded
    }

    // ---------------------------------------------------------------- voice in
    private val recListener = object : RecogAdapter() {
        override fun onError(error: Int) {
            if (!listening) return
            listening = false
            handler.removeCallbacks(listenTimeout)
            if (error == 12 || error == 13) preferOffline = false           // offline language pack missing: use online
            else if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                if (++hardErrors >= 2) canListen = false                    // give up listening, keep announcing
            }
            afterListen()
        }

        override fun onResults(results: Bundle?) {
            if (!listening) return
            listening = false
            handler.removeCallbacks(listenTimeout)
            hardErrors = 0
            if (!handle(results)) afterListen()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (listening) handle(partialResults)       // act as soon as the word is heard
        }
    }

    private fun handle(b: Bundle?): Boolean {
        val cmd = VoiceCommand.parse(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)) ?: return false
        voiceCommand(cmd == Command.SNOOZE)
        return true
    }

    private fun startListening() {
        val r = recognizer
        if (r == null || finishing) { afterListen(); return }
        listening = true
        duck(true)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLocale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            r.startListening(i)
            handler.postDelayed(listenTimeout, LISTEN_MS)
        } catch (e: Exception) {
            listening = false
            afterListen()
        }
    }

    /** No command heard: announce again, or give up after the last round. */
    private fun afterListen() {
        duck(false)
        if (finishing) return
        if (round >= MAX_ROUNDS) handler.post(autoSilence)
        else handler.postDelayed(roundRunnable, GAP_MS)
    }

    private fun voiceCommand(snooze: Boolean) {
        if (finishing) return
        finishing = true
        listening = false
        handler.removeCallbacks(roundRunnable)
        handler.removeCallbacks(listenTimeout)
        handler.removeCallbacks(autoSilence)
        handler.removeCallbacks(ttsTimeout)
        runCatching { recognizer?.cancel() }
        stopTone()
        if (snooze) AlarmScheduler.scheduleSnooze(this, currentId, currentSnooze)
        val len = if (currentSnooze == 60) "1 hour" else "$currentSnooze minutes"
        val msg = if (snooze) "Snoozed for $len." else "Dismissed."
        val t = tts
        if (t != null && ttsReady && !voiceDead) {
            handler.postDelayed(byeTimeout, 4_000L)
            if (t.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "bye") != TextToSpeech.SUCCESS) stopAlarm()
        } else {
            stopAlarm()
        }
    }

    // ---------------------------------------------------------------- tone, vibration
    private fun startTone() {
        if (toneActive) return
        toneActive = true
        val uri = soundUri ?: return          // the user chose "silent" for this category
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
                prepareAsync()
            }
        }.getOrNull()
    }

    private fun stopTone() {
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        player = null
        toneActive = false
    }

    private fun duck(on: Boolean) {
        val v = if (on) DUCK else 1f
        runCatching { player?.setVolume(v, v) }
    }

    private fun vibrateOnce() {
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            ?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ---------------------------------------------------------------- stop / notification
    private fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        listening = false
        runCatching { tts?.stop() }
        runCatching { recognizer?.cancel() }
        stopTone()
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
    }

    private fun stopAlarm() {
        stopAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIF_ID)
        stopSelf()
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

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Event alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)   // sound/voice is produced by the service itself
                    enableVibration(false)
                    description = "Birthday, anniversary and festival reminders"
                }
            )
        }
    }

    override fun onDestroy() {
        stopAll()
        runCatching { tts?.shutdown() }
        tts = null
        runCatching { recognizer?.destroy() }
        recognizer = null
        scope.cancel()
        super.onDestroy()
    }
}
