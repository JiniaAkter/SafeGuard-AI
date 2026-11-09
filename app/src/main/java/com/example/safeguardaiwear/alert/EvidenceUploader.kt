package com.example.safeguardaiwear.alert

import android.util.Log
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads an audio recording to Cloudinary (free cloud storage) and returns a
 * web link the emergency contact can open on any phone to listen.
 *
 * Uses an "unsigned upload preset", so no secret key is stored in the app.
 * Blocking network call – always call from a background thread.
 */
object EvidenceUploader {

    private const val TAG = "SafeGuardUpload"

    data class Outcome(val url: String?, val detail: String)

    fun upload(file: File): Outcome {
        if (!Secrets.cloudinaryConfigured()) {
            return Outcome(null, "Upload not set up (Cloudinary details missing in Secrets.kt)")
        }
        if (!file.exists() || file.length() == 0L) {
            return Outcome(null, "Recording file is missing")
        }

        val boundary = "----SafeGuard${System.currentTimeMillis()}"
        // Audio files use Cloudinary's "video" resource type.
        val url = URL("https://api.cloudinary.com/v1_1/${Secrets.CLOUDINARY_CLOUD_NAME}/video/upload")
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 60_000
                setChunkedStreamingMode(64 * 1024)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(connection.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.write(value.toByteArray())
                    out.writeBytes("\r\n")
                }
                field("upload_preset", Secrets.CLOUDINARY_UPLOAD_PRESET)
                field("folder", "safeguardai")

                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
                out.writeBytes("Content-Type: audio/mp4\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                out.writeBytes("\r\n--$boundary--\r\n")
            }

            val code = connection.responseCode
            if (code in 200..299) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val link = JSONObject(body).optString("secure_url")
                if (link.isNotBlank()) {
                    Log.i(TAG, "Uploaded ${file.name} -> $link")
                    Outcome(link, "Uploaded")
                } else {
                    Outcome(null, "Upload finished but no link was returned")
                }
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = try {
                    JSONObject(error).optJSONObject("error")?.optString("message") ?: error
                } catch (_: Exception) {
                    error
                }
                Log.e(TAG, "Upload failed $code: $error")
                Outcome(null, "Upload failed ($code): ${message.take(120)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            Outcome(null, "Upload failed: ${e.message ?: "no internet?"}")
        } finally {
            connection?.disconnect()
        }
    }
}