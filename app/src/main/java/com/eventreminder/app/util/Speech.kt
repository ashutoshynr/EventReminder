package com.eventreminder.app.util

import android.speech.tts.TextToSpeech
import java.util.Locale

object Speech {
    /** Picks an English voice (the announcement is English): your phone's own English, else India/UK/US. Returns the locale used. */
    fun chooseEnglish(t: TextToSpeech): Locale {
        val d = Locale.getDefault()
        val options = listOfNotNull(d.takeIf { it.language == "en" }, Locale("en", "IN"), Locale.UK, Locale.US)
        for (l in options) {
            if (t.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE) { t.language = l; return l }
        }
        t.language = Locale.US
        return Locale.US
    }
}
