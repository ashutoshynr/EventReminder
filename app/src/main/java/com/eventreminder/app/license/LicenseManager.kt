package com.eventreminder.app.license

import android.content.Context

/**
 * The licence key is hardcoded here, as requested. Change it in this one place.
 * Once the correct key is entered, activation is remembered on the device.
 */
object LicenseManager {
    private const val LICENSE_KEY = "01-01-1900"
    private const val PREF = "license"
    private const val FLAG = "activated"

    fun isActivated(c: Context): Boolean =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(FLAG, false)

    /** Returns true (and stores activation) if the entered key matches. */
    fun activate(c: Context, entered: String): Boolean {
        if (entered.trim() != LICENSE_KEY) return false
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(FLAG, true).apply()
        return true
    }
}
