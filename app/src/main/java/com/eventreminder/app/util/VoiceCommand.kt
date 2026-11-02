package com.eventreminder.app.util

import android.os.Bundle
import android.speech.RecognitionListener
import java.util.Locale

enum class Command { SNOOZE, DISMISS }

object VoiceCommand {
    private val snooze = listOf("snooze", "snooz", "snoos", "later")
    private val dismiss = listOf("dismiss", "dismis", "stop", "done", "cancel")

    /** Looks through the recogniser's alternatives for "snooze" or "dismiss". */
    fun parse(options: List<String>?): Command? {
        options ?: return null
        for (o in options) {
            val s = o.lowercase(Locale.ENGLISH)
            if (snooze.any { s.contains(it) }) return Command.SNOOZE
            if (dismiss.any { s.contains(it) }) return Command.DISMISS
        }
        return null
    }
}

/** RecognitionListener with empty defaults, so callers only override what they need. */
open class RecogAdapter : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(error: Int) {}
    override fun onResults(results: Bundle?) {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
