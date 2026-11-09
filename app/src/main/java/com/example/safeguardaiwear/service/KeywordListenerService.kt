package com.example.safeguardaiwear.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.safeguardaiwear.R
import com.example.safeguardaiwear.ai.RiskDetector
import com.example.safeguardaiwear.alert.AlertSender
import com.example.safeguardaiwear.alert.EvidenceUploader
import com.example.safeguardaiwear.data.SafeGuardPrefs
import com.example.safeguardaiwear.speech.VoskModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Listens on the watch (offline) for the user's secret phrase and for signs of danger.
 *
 *  - While listening, nothing is saved. Only the last [PRE_ROLL_SECONDS] seconds
 *    are kept in memory and continuously thrown away.
 *  - Saying the phrase starts a recording and the watch vibrates once. If the
 *    "include 5 seconds before" setting is on, the pre-roll is added to the start.
 *  - While audio plays on the watch, detection is paused.
 *  - Saying the phrase again, tapping "Stop recording", or reaching
 *    [MAX_RECORDING_MS] stops it and the watch vibrates twice.
 *  - AI danger: records 60 s, texts the contact, then texts a link to the audio.
 *    How long the risk must last before alerting comes from the trained model.
 *  - Recordings are saved as .m4a (AAC) with a .sha256 fingerprint.
 */
class KeywordListenerService : Service() {

    enum class State { STOPPED, LOADING, LISTENING, RECORDING }

    companion object {
        private const val TAG = "SafeGuard"
        const val SAMPLE_RATE = 16_000
        const val PRE_ROLL_SECONDS = 5
        const val MAX_RECORDING_MS = 5 * 60 * 1000L
        const val ALERT_COOLDOWN_MS = 5 * 60 * 1000L     // max one automatic SMS per 5 minutes
        const val AI_CLIP_MS = 60 * 1000L                // AI recordings stop after 60 s and are sent

        private const val ACTION_STOP_RECORDING = "com.example.safeguardaiwear.STOP_RECORDING"
        private const val CHANNEL_ID = "safeguard_service"
        private const val NOTIFICATION_ID = 1001

        private val _state = MutableStateFlow(State.STOPPED)
        val state: StateFlow<State> = _state

        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error

        /** Time the current recording started (epoch ms), or 0 when not recording. */
        private val _recordingStartedAt = MutableStateFlow(0L)
        val recordingStartedAt: StateFlow<Long> = _recordingStartedAt

        /** True while listening is paused because audio is playing on the watch. */
        private val _paused = MutableStateFlow(false)
        val paused: StateFlow<Boolean> = _paused

        // ---------------------------------------------------------- AI risk + alerts

        /** Latest AI risk (0..1), or -1 when the AI is off / not loaded. */
        private val _risk = MutableStateFlow(-1f)
        val risk: StateFlow<Float> = _risk

        /** The AI's alert threshold (from violence_head.json). */
        private val _riskThreshold = MutableStateFlow(0.5f)
        val riskThreshold: StateFlow<Float> = _riskThreshold

        /** Loudest sound YAMNet hears right now, e.g. "Speech" or "Screaming". */
        private val _topSound = MutableStateFlow("")
        val topSound: StateFlow<String> = _topSound

        /** Time of the last confirmed risk event (epoch ms), 0 if none. */
        private val _lastRiskAt = MutableStateFlow(0L)
        val lastRiskAt: StateFlow<Long> = _lastRiskAt

        /** When the last automatic alert was sent. */
        @Volatile
        private var lastAlertAt = 0L

        /** Result of the last alert, e.g. "✓ Alert sent to Mum (7:42 pm)". */
        private val _alertStatus = MutableStateFlow<String?>(null)
        val alertStatus: StateFlow<String?> = _alertStatus

        fun alertMessage(confidencePercent: Int?): String {
            val time = SimpleDateFormat("h:mm a 'on' d MMM", Locale.getDefault()).format(Date())
            return if (confidencePercent == null) {
                "SafeGuardAI TEST: this is a test alert sent at $time. No action needed."
            } else {
                "SafeGuardAI ALERT: possible danger detected at $time " +
                        "(AI confidence $confidencePercent%). Audio is being recorded – " +
                        "a link to listen will follow in about a minute. " +
                        "Please check on them now. If you believe they are in immediate danger, call 000."
            }
        }

        /**
         * Sends an SMS to the saved emergency contact on a background thread and
         * records the result. [confidencePercent] = null means a test alert.
         */
        fun sendAlertAsync(context: Context, confidencePercent: Int?) {
            val prefs = SafeGuardPrefs(context)
            val kind = if (confidencePercent == null) "Test alert" else "Alert"
            if (!prefs.hasContact) {
                _alertStatus.value = "$kind not sent – no emergency contact set"
                return
            }
            val name = prefs.contactName.ifBlank { prefs.contactPhone }
            _alertStatus.value = "Sending $kind to $name…"
            Thread {
                val outcome = AlertSender.sendSms(prefs.contactPhone, alertMessage(confidencePercent))
                val time = SimpleDateFormat("h:mm a, d MMM", Locale.getDefault()).format(Date())
                val status = when {
                    outcome.sent -> "✓ $kind sent to $name ($time)"
                    outcome.simulated -> "$kind to $name SIMULATED – SMS service not set up ($time)"
                    else -> "✗ $kind to $name failed: ${outcome.detail}"
                }
                _alertStatus.value = status
                prefs.addAlertLog(status)
            }.start()
        }

        /**
         * Uploads an AI recording and texts the emergency contact a link to it.
         * Runs on a background thread.
         */
        fun sendEvidenceAsync(context: Context, file: File, seconds: Int) {
            val prefs = SafeGuardPrefs(context)
            if (!prefs.hasContact) return
            val name = prefs.contactName.ifBlank { prefs.contactPhone }
            _alertStatus.value = "Uploading audio for $name…"
            Thread {
                val upload = EvidenceUploader.upload(file)
                val time = SimpleDateFormat("h:mm a, d MMM", Locale.getDefault()).format(Date())
                val status = if (upload.url == null) {
                    "✗ Audio not sent: ${upload.detail}"
                } else {
                    // First 12 characters of the SHA-256 fingerprint: proves the file wasn't changed.
                    val check = File(file.parentFile, file.name + ".sha256")
                        .takeIf { it.exists() }?.readText()?.take(12).orEmpty()
                    val body = "SafeGuardAI: audio from the alert (${seconds}s). Listen: ${upload.url}" +
                            if (check.isNotBlank()) " Check code: $check" else ""
                    val outcome = AlertSender.sendSms(prefs.contactPhone, body)
                    when {
                        outcome.sent -> "✓ Audio link sent to $name ($time)"
                        outcome.simulated -> "Audio uploaded; SMS to $name SIMULATED ($time)"
                        else -> "✗ Audio link to $name failed: ${outcome.detail}"
                    }
                }
                _alertStatus.value = status
                prefs.addAlertLog(status)
            }.start()
        }

        /** Last words the recogniser heard from the phrase – helps when testing. */
        private val _heard = MutableStateFlow("")
        val heard: StateFlow<String> = _heard

        /** True while the app is playing a recording out loud. */
        @Volatile
        private var playbackActive = false

        /** Ignore the phrase until this time (echo after playback ends). */
        @Volatile
        private var ignoreUntil = 0L

        /** Called by the app when it starts / stops playing a recording. */
        fun setPlaybackActive(active: Boolean) {
            playbackActive = active
            if (!active) ignoreUntil = System.currentTimeMillis() + 1_500
        }

        /** Starts listening. Returns an error message, or null if it started. */
        fun start(context: Context): String? {
            _error.value = null
            return try {
                val started = context.startForegroundService(
                    Intent(context, KeywordListenerService::class.java)
                )
                if (started == null) "Service not found – check AndroidManifest.xml" else null
            } catch (e: Exception) {
                Log.e(TAG, "Could not start service", e)
                "Could not start: ${e.message}"
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeywordListenerService::class.java))
        }

        /** Ends the current recording but keeps listening. */
        fun stopRecording(context: Context) {
            if (_state.value != State.RECORDING) return
            context.startService(
                Intent(context, KeywordListenerService::class.java).setAction(ACTION_STOP_RECORDING)
            )
        }

        /**
         * Folder for recordings:
         * /sdcard/Android/data/com.example.safeguardaiwear/files/Music/SafeGuardAI
         * (private to this app, but reachable from Android Studio's Device Explorer).
         */
        fun recordingsDir(context: Context): File {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "recordings")
            return File(base, "SafeGuardAI").apply { mkdirs() }
        }

        fun listRecordings(context: Context): List<File> =
            recordingsDir(context)
                .listFiles { f -> f.extension == "m4a" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
    }

    @Volatile
    private var running = false

    @Volatile
    private var stopRecordingRequested = false

    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RECORDING) {
            stopRecordingRequested = true
            return START_NOT_STICKY
        }

        // Must be called promptly after startForegroundService(). If Android restarts
        // the service in the background it may refuse; the app then restarts
        // listening the next time it is opened.
        try {
            startInForeground("Starting…")
        } catch (e: Exception) {
            Log.w(TAG, "Not allowed to start listening right now", e)
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = SafeGuardPrefs(this)
        val phrase = prefs.secretPhrase
        when {
            !prefs.consentGiven -> return fail("Consent has not been given")
            phrase.isNullOrBlank() -> return fail("Set a secret phrase first")
            !hasMicPermission() -> return fail("Microphone permission is off")
        }

        if (worker == null) {
            running = true
            acquireWakeLock()
            worker = Thread({ listenLoop(phrase!!) }, "safeguard-listener").also { it.start() }
        }
        // STICKY: if Android kills the service, it is started again automatically.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        _paused.value = false
        worker?.join(3_000)
        worker = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        _state.value = State.STOPPED
        _recordingStartedAt.value = 0L
        _heard.value = ""
        _risk.value = -1f
        _topSound.value = ""
        super.onDestroy()
    }

    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun listenLoop(phrase: String) {
        _state.value = State.LOADING

        val model = try {
            VoskModelManager.load(this)
        } catch (e: Throwable) {
            Log.e(TAG, "Model load failed", e)
            fail("Speech model missing – see setup notes")
            return
        }

        // AI risk detector (YAMNet + trained classifier). Optional: if the model
        // files are missing, the secret phrase still works.
        val detector: RiskDetector? = if (SafeGuardPrefs(this).riskDetectionEnabled) {
            try {
                RiskDetector.load(this).also { _riskThreshold.value = it.threshold }
            } catch (e: Throwable) {
                Log.e(TAG, "AI model not loaded", e)
                _error.value = "AI model missing (assets/ai) – phrase only"
                null
            }
        } else null
        _risk.value = if (detector != null) 0f else -1f
        var riskStreak = 0

        // Only listen for the phrase (anything else becomes "[unk]").
        // The vocabulary is the full phrase, its shorter beginnings and each single
        // word. Without the shorter options the recogniser "completes" the phrase
        // when only part of it was said (e.g. "purple pineapple" -> "... tea").
        val words = phrase.split(" ")
        val options = buildList {
            for (n in words.size downTo 1) add(words.take(n).joinToString(" "))
            addAll(words)
            add("[unk]")
        }.distinct()
        val grammar = JSONArray(options).toString()
        fun newRecognizer() = Recognizer(model, SAMPLE_RATE.toFloat(), grammar)
        var recognizer = newRecognizer()

        val chunkBytes = SAMPLE_RATE / 5 * 2 // 200 ms of 16-bit audio
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        fun newAudioRecord() = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, chunkBytes) * 2
        )
        var audioRecord = newAudioRecord()
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            recognizer.close()
            detector?.close()
            fail("Microphone unavailable")
            return
        }

        val buffer = ByteArray(chunkBytes)

        /**
         * Closes and reopens the microphone. After audio has played the old
         * microphone stream can keep delivering silence – reopening it fixes that.
         */
        fun restartMic() {
            try { audioRecord.stop() } catch (_: Exception) { }
            audioRecord.release()
            audioRecord = newAudioRecord()
            audioRecord.startRecording()
            Log.i(TAG, "Microphone restarted")
        }
        val preRoll = ArrayDeque<ByteArray>()
        val preRollLimit = SAMPLE_RATE * 2 * PRE_ROLL_SECONDS
        var preRollBytes = 0

        var writer: AacWriter? = null
        var wasPaused = false
        var lastAudioAt = 0L
        val prefs = SafeGuardPrefs(this)
        val audioManager = getSystemService(AudioManager::class.java)

        var currentFile: File? = null
        var currentByAi = false
        var sendEvidenceForCurrent = false

        /** [byAi] = started by the AI (file name starts with "risk_"). */
        fun beginRecording(byAi: Boolean = false) {
            val prefix = if (byAi) "risk" else "recording"
            val file = File(recordingsDir(this), "${prefix}_${System.currentTimeMillis()}.m4a")
            val newWriter = try {
                AacWriter(file, SAMPLE_RATE)
            } catch (e: Exception) {
                Log.e(TAG, "Could not create recording", e)
                _error.value = "Could not create recording file"
                return
            }
            // AI recordings always keep the seconds before – they contain what triggered it.
            if (byAi || prefs.includePreRoll) {
                preRoll.forEach { chunk -> newWriter.write(chunk, chunk.size) }
            }
            preRoll.clear()
            preRollBytes = 0
            writer = newWriter
            currentFile = file
            currentByAi = byAi
            sendEvidenceForCurrent = false
            _recordingStartedAt.value = System.currentTimeMillis()
            _state.value = State.RECORDING
            updateNotification("Recording")
            if (!byAi) buzz(longArrayOf(0, 400))   // one long buzz = started
            Log.i(TAG, "Recording started: ${file.name}")
        }

        /** Confirmed AI risk: preserve evidence, vibrate, alert the contact. */
        fun onRisk(probability: Float) {
            val now = System.currentTimeMillis()
            val newEvent = now - _lastRiskAt.value > 30_000
            _lastRiskAt.value = now
            if (writer == null) beginRecording(byAi = true)
            if (newEvent) buzz(longArrayOf(0, 200, 120, 200, 120, 200))  // three buzzes = risk
            if (now - lastAlertAt > ALERT_COOLDOWN_MS) {
                lastAlertAt = now
                sendAlertAsync(this, (probability * 100).toInt())
                // The audio of this recording goes to the contact when it ends.
                if (writer != null) sendEvidenceForCurrent = true
            }
            Log.i(TAG, "Risk detected: ${(probability * 100).toInt()}%")
        }

        fun endRecording() {
            val w = writer ?: return
            writer = null
            val file = currentFile
            val sendIt = sendEvidenceForCurrent
            val seconds = ((System.currentTimeMillis() - _recordingStartedAt.value) / 1000).toInt()
            currentFile = null
            currentByAi = false
            sendEvidenceForCurrent = false
            try {
                w.close()
            } catch (e: Exception) {
                Log.e(TAG, "Could not finish recording", e)
            }
            if (sendIt && file != null && file.exists()) {
                sendEvidenceAsync(this, file, seconds)
            }
            _recordingStartedAt.value = 0L
            _state.value = State.LISTENING
            updateNotification("Listening")
            buzz(longArrayOf(0, 150, 150, 150))    // two short buzzes = stopped
            Log.i(TAG, "Recording stopped")
        }

        try {
            audioRecord.startRecording()
            _state.value = State.LISTENING
            updateNotification("Listening")

            while (running) {
                if (stopRecordingRequested) {
                    stopRecordingRequested = false
                    endRecording()
                }

                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read < 0) {
                    fail("Microphone error ($read)")
                    break
                }
                if (read == 0) continue

                val w = writer
                if (w != null) {
                    w.write(buffer, read)
                } else {
                    // Rolling in-memory buffer only – never saved unless triggered.
                    preRoll.addLast(buffer.copyOf(read))
                    preRollBytes += read
                    while (preRollBytes > preRollLimit) {
                        preRollBytes -= preRoll.removeFirst().size
                    }
                }

                // Pause detection while ANY audio plays on the watch (our recordings,
                // music, videos …) and for 1.5 s after, so sound from the speaker
                // can't trigger a recording. Listening resumes by itself.
                val now = System.currentTimeMillis()
                if (playbackActive || audioManager.isMusicActive) lastAudioAt = now
                if (now - lastAudioAt < 1_500 || now < ignoreUntil) {
                    if (!wasPaused) _paused.value = true
                    wasPaused = true
                    continue
                }
                if (wasPaused) {
                    _paused.value = false
                    // Throw away the played-back sound and start detection completely
                    // fresh (new recogniser), so listening works straight away.
                    wasPaused = false
                    preRoll.clear()
                    preRollBytes = 0
                    _heard.value = ""
                    recognizer.close()
                    recognizer = newRecognizer()
                    restartMic()
                    continue
                }

                // Partial results only update the "Heard" text on screen. The phrase
                // only counts once the sentence is finished (short pause after it),
                // so all words, including the last one, must really have been said.
                val isFinal = recognizer.acceptWaveForm(buffer, read)
                val raw = if (isFinal) {
                    JSONObject(recognizer.result).optString("text")
                } else {
                    JSONObject(recognizer.partialResult).optString("partial")
                }
                val text = raw.replace("[unk]", " ").trim().replace(Regex("\\s+"), " ")
                if (text.isNotEmpty()) _heard.value = text

                if (isFinal) Log.i(TAG, "Heard (final): '$text'")
                if (isFinal && " $text ".contains(" $phrase ")) {
                    if (writer == null) beginRecording() else endRecording()
                }

                // AI risk check – a new result about every 0.5 s.
                if (detector != null) {
                    val result = detector.feed(buffer, read)
                    if (result != null) {
                        _risk.value = result.probability
                        _topSound.value = result.topSound
                        riskStreak = if (result.probability >= detector.threshold) riskStreak + 1 else 0
                        // How long the risk must last comes from the trained model file.
                        if (riskStreak >= detector.confirmResults) {
                            riskStreak = 0
                            onRisk(result.probability)
                        }
                    }
                }

                val recordingFor = System.currentTimeMillis() - _recordingStartedAt.value
                if (writer != null &&
                    (recordingFor > MAX_RECORDING_MS || (currentByAi && recordingFor > AI_CLIP_MS))
                ) {
                    // AI clips stop after 60 s so the audio can be sent quickly.
                    endRecording()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Listening stopped unexpectedly", e)
            fail("Listening stopped unexpectedly")
        } finally {
            endRecording()
            try { audioRecord.stop() } catch (_: Exception) { }
            audioRecord.release()
            recognizer.close()
            detector?.close()
        }
    }

    // ---------------------------------------------------------------------

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SafeGuardAI")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun startInForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SafeGuardAI status", NotificationManager.IMPORTANCE_LOW)
            )
        }
        startForeground(
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) { }
    }

    private fun buzz(pattern: LongArray) {
        try {
            vibrator().vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafeGuardAI:listener")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun fail(message: String): Int {
        _error.value = message
        running = false
        stopSelf()
        return START_NOT_STICKY
    }
}