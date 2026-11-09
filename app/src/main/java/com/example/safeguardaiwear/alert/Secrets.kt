package com.example.safeguardaiwear.alert

/**
 * Online services used for alerts.
 *
 * Leave a service empty to run it in SIMULATED mode: the app still works and
 * shows what it would have sent, but nothing leaves the watch.
 *
 * ⚠ The GitHub repository is public – NEVER push real values.
 *   (For a real product these would live on a server, not inside the app.)
 */
object Secrets {

    // ---------------- Twilio – sends the SMS (https://console.twilio.com → Account Info)
    const val TWILIO_ACCOUNT_SID = ""      // e.g. "ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    const val TWILIO_AUTH_TOKEN = ""      // e.g. "your_auth_token"
    const val TWILIO_FROM_NUMBER = ""      // your Twilio number, e.g. "+15551234567"

    // Twilio TRIAL accounts can only send predefined templates, not your own text.
    // Trial: "sms_account_alerts". After upgrading Twilio: set to "" to send the real alert text + audio link.
    const val TWILIO_TRIAL_TEMPLATE = "sms_account_alerts"

    // ---------------- Cloudinary – stores the audio so the SMS can link to it
    // (https://console.cloudinary.com → Settings → Upload → add an UNSIGNED upload preset)
    const val CLOUDINARY_CLOUD_NAME = ""   // e.g. "dxyz123ab"
    const val CLOUDINARY_UPLOAD_PRESET = "" // e.g. "safeguard_unsigned"

    fun twilioConfigured(): Boolean =
        TWILIO_ACCOUNT_SID.isNotBlank() && TWILIO_AUTH_TOKEN.isNotBlank() && TWILIO_FROM_NUMBER.isNotBlank()

    fun cloudinaryConfigured(): Boolean =
        CLOUDINARY_CLOUD_NAME.isNotBlank() && CLOUDINARY_UPLOAD_PRESET.isNotBlank()
}