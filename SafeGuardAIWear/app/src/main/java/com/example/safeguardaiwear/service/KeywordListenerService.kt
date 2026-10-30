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
import com.example.safeguardaiwear.data.SafeGuardPrefs
import com.example.safeguardaiwear.speech.VoskModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.File

/**
 * Listens on the watch (offline) for the user's secret phrase.
 *
 *  - While listening, nothing is saved. Only the last [PRE_ROLL_SECONDS] seconds
 *    are kept in memory and continuously thrown away.
 *  - Saying the phrase starts a recording and the watch vibrates once. If the
 *    "include 5 seconds before" setting is on, the pre-roll is added to the start.
 *  - While a recording is being played back, phrase detection is paused, so the
 *    phrase inside the recording can't start a new recording.
 *  - Saying the phrase again, tapping "Stop recording", or reaching
 *    [MAX_RECORDING_MS] stops it and the watch vibrates twice.
 *  - Recordings are saved as .m4a (AAC) with a .sha256 fingerprint.
 */
class KeywordListenerService : Service() {

    enum class State { STOPPED, LOADING, LISTENING, RECORDING }

    companion object {
        private const val TAG = "SafeGuard"
        const val SAMPLE_RATE = 16_000
        const val PRE_ROLL_SECONDS = 5
        const val MAX_RECORDING_MS = 5 * 60 * 1000L

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

        /** Last words the recogniser heard from the phrase – helps when testing. */
        /** True while listening is paused because audio is playing on the watch. */
        private val _paused = MutableStateFlow(false)
        val paused: StateFlow<Boolean> = _paused

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
            fail("Microphone unavailable")
            return
        }

        val buffer = ByteArray(chunkBytes)
        var silentSince = 0L

        /**
         * Closes and reopens the microphone. After audio has played (or if the
         * system silenced us) the old microphone stream can keep delivering
         * silence – reopening it is what "turn protection off and on" used to fix.
         */
        fun restartMic() {
            try { audioRecord.stop() } catch (_: Exception) { }
            audioRecord.release()
            audioRecord = newAudioRecord()
            audioRecord.startRecording()
            silentSince = 0L
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

        fun beginRecording() {
            val file = File(recordingsDir(this), "recording_${System.currentTimeMillis()}.m4a")
            val newWriter = try {
                AacWriter(file, SAMPLE_RATE)
            } catch (e: Exception) {
                Log.e(TAG, "Could not create recording", e)
                _error.value = "Could not create recording file"
                return
            }
            if (prefs.includePreRoll) {
                preRoll.forEach { chunk -> newWriter.write(chunk, chunk.size) }
            }
            preRoll.clear()
            preRollBytes = 0
            writer = newWriter
            _recordingStartedAt.value = System.currentTimeMillis()
            _state.value = State.RECORDING
            updateNotification("Recording")
            buzz(longArrayOf(0, 400))              // one long buzz = started
            Log.i(TAG, "Recording started: ${file.name}")
        }

        fun endRecording() {
            val w = writer ?: return
            writer = null
            try {
                w.close()
            } catch (e: Exception) {
                Log.e(TAG, "Could not finish recording", e)
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

                if (writer != null &&
                    System.currentTimeMillis() - _recordingStartedAt.value > MAX_RECORDING_MS
                ) {
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