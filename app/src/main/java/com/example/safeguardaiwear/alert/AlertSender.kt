package com.example.safeguardaiwear.alert

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sends the emergency SMS through Twilio's web API.
 * The Wear OS emulator has no SIM card, so the text goes over the internet.
 *
 * Blocking network call – always call from a background thread.
 */
object AlertSender {

    private const val TAG = "SafeGuardAlert"

    data class Outcome(val sent: Boolean, val simulated: Boolean, val detail: String)

    fun sendSms(toNumber: String, body: String): Outcome {
        if (!Secrets.twilioConfigured()) {
            Log.i(TAG, "SIMULATED SMS to $toNumber: $body")
            return Outcome(sent = false, simulated = true, detail = "Simulated – SMS service not set up")
        }
        val sid = Secrets.TWILIO_ACCOUNT_SID
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api.twilio.com/2010-04-01/Accounts/$sid/Messages.json")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
                val auth = Base64.encodeToString("$sid:${Secrets.TWILIO_AUTH_TOKEN}".toByteArray(), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $auth")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val form = listOf(
                "To" to toNumber,
                "From" to Secrets.TWILIO_FROM_NUMBER,
                "Body" to Secrets.TWILIO_TRIAL_TEMPLATE.ifBlank { body }
            ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            connection.outputStream.use { it.write(form.toByteArray()) }

            val code = connection.responseCode
            if (code in 200..299) {
                Log.i(TAG, "SMS sent to $toNumber")
                Outcome(sent = true, simulated = false, detail = "SMS sent")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = try { JSONObject(error).optString("message") } catch (_: Exception) { error }
                Log.e(TAG, "Twilio error $code: $error")
                Outcome(sent = false, simulated = false, detail = "SMS failed ($code): ${message.take(120)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS failed", e)
            Outcome(sent = false, simulated = false, detail = "SMS failed: ${e.message ?: "no internet?"}")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Converts what the user typed into international format, e.g.
     * "0412 345 678" -> "+61412345678" (Australian mobile). Returns null if invalid.
     */
    fun normalisePhone(raw: String, defaultCountryCode: String = "61"): String? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        val number = when {
            trimmed.startsWith("+") -> "+$digits"
            trimmed.startsWith("00") -> "+" + digits.drop(2)
            digits.startsWith("0") -> "+$defaultCountryCode" + digits.drop(1)
            digits.startsWith(defaultCountryCode) && digits.length >= 11 -> "+$digits"
            else -> "+$defaultCountryCode$digits"
        }
        return if (number.length in 9..16) number else null
    }
}