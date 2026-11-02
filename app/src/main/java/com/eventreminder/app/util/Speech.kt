package com.eventreminder.app.util

import android.speech.tts.TextToSpeech
import java.util.Locale

object Speech {
    /** Picks an English voice (the announcement text is English): your phone's own English, else India/UK/US. */
    fun chooseEnglish(t: TextToSpeech) {
        val d = Locale.getDefault()
        val options = listOfNotNull(d.takeIf { it.language == "en" }, Locale("en", "IN"), Locale.UK, Locale.US)
        for (l in options) {
            if (t.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE) { t.language = l; return }
        }
        t.language = Locale.US
    }
}
