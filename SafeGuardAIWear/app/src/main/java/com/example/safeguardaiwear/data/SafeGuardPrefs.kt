package com.example.safeguardaiwear.data

import android.content.Context

/**
 * Stores the user's consent decision and secret phrase in app-private storage.
 *
 * MVP note: the phrase is stored in private SharedPreferences (not readable by
 * other apps). For the final build, encrypt it with an Android Keystore key.
 */
class SafeGuardPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("safeguard_prefs", Context.MODE_PRIVATE)

    var consentGiven: Boolean
        get() = prefs.getBoolean(KEY_CONSENT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CONSENT, value).apply()
        }

    /** When consent was given (epoch ms) – useful for the audit trail. */
    var consentTimestamp: Long
        get() = prefs.getLong(KEY_CONSENT_TIME, 0L)
        set(value) {
            prefs.edit().putLong(KEY_CONSENT_TIME, value).apply()
        }

    /** Already normalised (lower case, single spaces). Null until the user sets one. */
    var secretPhrase: String?
        get() = prefs.getString(KEY_PHRASE, null)
        set(value) {
            prefs.edit().putString(KEY_PHRASE, value).apply()
        }

    /** Include the 5 seconds before the phrase in recordings (default: yes). */
    var includePreRoll: Boolean
        get() = prefs.getBoolean(KEY_PRE_ROLL, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PRE_ROLL, value).apply()
        }

    /** The user turned protection on (kept so listening restarts automatically). */
    var protectionOn: Boolean
        get() = prefs.getBoolean(KEY_PROTECTION_ON, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PROTECTION_ON, value).apply()
        }

    fun withdrawConsent() {
        prefs.edit()
            .putBoolean(KEY_CONSENT, false)
            .putLong(KEY_CONSENT_TIME, 0L)
            .putBoolean(KEY_PROTECTION_ON, false)
            .apply()
    }

    companion object {
        private const val KEY_CONSENT = "consent_given"
        private const val KEY_CONSENT_TIME = "consent_time"
        private const val KEY_PHRASE = "secret_phrase"
        private const val KEY_PRE_ROLL = "include_pre_roll"
        private const val KEY_PROTECTION_ON = "protection_on"

        const val MIN_WORDS = 2
        const val MAX_WORDS = 4

        /** Lower-case, letters/apostrophes only, single spaces. */
        fun normalise(raw: String): String =
            raw.lowercase()
                .replace(Regex("[^a-z' ]"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
    }
}