package com.example.safeguardaiwear.data

import android.content.Context
import org.json.JSONArray

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

    // ------------------------------------------------------------ emergency contact

    var contactName: String
        get() = prefs.getString(KEY_CONTACT_NAME, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_CONTACT_NAME, value).apply()
        }

    /** International format, e.g. +61412345678. */
    var contactPhone: String
        get() = prefs.getString(KEY_CONTACT_PHONE, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_CONTACT_PHONE, value).apply()
        }

    val hasContact: Boolean get() = contactPhone.isNotBlank()

    /** AI listens for signs of danger (shouting, screaming, crying, impacts). */
    var riskDetectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_RISK_ON, true)
        set(value) {
            prefs.edit().putBoolean(KEY_RISK_ON, value).apply()
        }

    /** Alert history, newest first, max 20 entries. */
    val alertLog: List<String>
        get() {
            val arr = JSONArray(prefs.getString(KEY_ALERT_LOG, "[]"))
            return List(arr.length()) { arr.getString(it) }
        }

    @Synchronized
    fun addAlertLog(entry: String) {
        val updated = (listOf(entry) + alertLog).take(20)
        prefs.edit().putString(KEY_ALERT_LOG, JSONArray(updated).toString()).apply()
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
        private const val KEY_CONTACT_NAME = "contact_name"
        private const val KEY_CONTACT_PHONE = "contact_phone"
        private const val KEY_RISK_ON = "risk_detection_on"
        private const val KEY_ALERT_LOG = "alert_log"

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