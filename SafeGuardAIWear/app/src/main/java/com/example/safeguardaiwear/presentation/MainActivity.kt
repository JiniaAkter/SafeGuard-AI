package com.example.safeguardaiwear.presentation

import android.Manifest
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.example.safeguardaiwear.data.SafeGuardPrefs
import com.example.safeguardaiwear.presentation.theme.SafeGuardAIWearTheme
import com.example.safeguardaiwear.service.KeywordListenerService
import com.example.safeguardaiwear.service.KeywordListenerService.State
import com.example.safeguardaiwear.speech.VoskModelManager
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { CONSENT, PHRASE, PHRASE_CONFIRM, HOME, RECORDINGS, SETTINGS }

private val RecordingRed = Color(0xFFFF5252)

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SafeGuardPrefs

    private var screen by mutableStateOf(Screen.CONSENT)
    private var message by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var pendingPhrase by mutableStateOf("")
    private var showPhrase by mutableStateOf(false)
    private var recordingsVersion by mutableIntStateOf(0)
    private var playingPath by mutableStateOf<String?>(null)
    private var confirmDeletePath by mutableStateOf<String?>(null)

    private var player: MediaPlayer? = null

    // ---------------------------------------------------------------- permissions

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.RECORD_AUDIO] == true) {
                // Consent is only recorded once the microphone permission is actually granted.
                prefs.consentGiven = true
                prefs.consentTimestamp = System.currentTimeMillis()
                navigate(if (prefs.secretPhrase.isNullOrBlank()) Screen.PHRASE else Screen.HOME)
            } else {
                message = "Microphone access is needed. Allow it in Settings › Apps › SafeGuardAI."
            }
        }

    // ---------------------------------------------------------------- phrase input

    /** Opens the standard Wear OS text-input screen (keyboard / handwriting). */
    private val phraseInputLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data?.let { data ->
                RemoteInput.getResultsFromIntent(data)?.getCharSequence(PHRASE_KEY)?.toString()
            }
            if (text.isNullOrBlank()) {
                message = "No phrase entered. Tap “Enter phrase” to try again."
                return@registerForActivityResult
            }
            val phrase = SafeGuardPrefs.normalise(text)
            val words = if (phrase.isEmpty()) 0 else phrase.split(" ").size
            if (words < SafeGuardPrefs.MIN_WORDS || words > SafeGuardPrefs.MAX_WORDS) {
                message = "“$phrase” has $words word(s). Use ${SafeGuardPrefs.MIN_WORDS}–${SafeGuardPrefs.MAX_WORDS} words."
                return@registerForActivityResult
            }
            pendingPhrase = phrase
            navigate(Screen.PHRASE_CONFIRM)
        }

    private fun launchPhraseInput() {
        message = null
        val remoteInput = RemoteInput.Builder(PHRASE_KEY)
            .setLabel("Type your secret phrase")
            .build()
        val intent = Intent(ACTION_REMOTE_INPUT).apply {
            putExtra(EXTRA_REMOTE_INPUTS, arrayOf<Parcelable>(remoteInput))
            putExtra(EXTRA_TITLE, "Secret phrase")
        }
        try {
            phraseInputLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "No text input screen", e)
            message = "This watch has no text input screen."
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SafeGuardPrefs(this)
        screen = when {
            !prefs.consentGiven || !hasMicPermission() -> Screen.CONSENT
            prefs.secretPhrase.isNullOrBlank() -> Screen.PHRASE
            else -> Screen.HOME
        }
        setContent { App() }
    }

    override fun onResume() {
        super.onResume()
        recordingsVersion++
        // If the microphone permission was turned off in Settings, go back to consent.
        if (prefs.consentGiven && !hasMicPermission()) {
            KeywordListenerService.stop(this)
            prefs.withdrawConsent()
            navigate(Screen.CONSENT)
        }
    }

    override fun onStop() {
        stopPlayback()
        super.onStop()
    }

    // ---------------------------------------------------------------- actions

    private fun navigate(to: Screen) {
        message = null
        confirmDeletePath = null
        showPhrase = false
        screen = to
    }

    private fun goBack() {
        when (screen) {
            Screen.PHRASE_CONFIRM -> navigate(Screen.PHRASE)
            Screen.PHRASE -> navigate(Screen.SETTINGS)
            Screen.RECORDINGS, Screen.SETTINGS -> navigate(Screen.HOME)
            else -> finish()
        }
    }

    private fun requestConsent() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun savePendingPhrase() {
        val phrase = pendingPhrase
        busy = true
        message = "Saving… (first time can take ~20 s)"
        // Loading the speech model is slow – keep it off the UI thread.
        Thread {
            val error: String? = try {
                VoskModelManager.unknownWords(this, phrase)
                null
            } catch (e: Throwable) {
                Log.e(TAG, "Model load failed", e)
                "Speech model problem: ${e.message}"
            }
            runOnUiThread {
                busy = false
                if (error != null) {
                    message = error
                    return@runOnUiThread
                }
                val wasRunning = KeywordListenerService.state.value != State.STOPPED
                prefs.secretPhrase = phrase
                pendingPhrase = ""
                navigate(Screen.HOME)
                if (wasRunning) {
                    // Restart so the listener uses the new phrase.
                    KeywordListenerService.stop(this)
                    message = KeywordListenerService.start(this) ?: "✓ Phrase saved and active"
                } else {
                    message = "✓ Phrase saved. Tap “Turn protection on” to start."
                }
            }
        }.start()
    }

    private fun turnProtectionOn() {
        if (!prefs.consentGiven || !hasMicPermission()) {
            navigate(Screen.CONSENT)
            return
        }
        if (prefs.secretPhrase.isNullOrBlank()) {
            navigate(Screen.PHRASE)
            return
        }
        message = KeywordListenerService.start(this)
    }

    private fun turnProtectionOff() {
        message = null
        KeywordListenerService.stop(this)
    }

    private fun withdrawConsent() {
        KeywordListenerService.stop(this)
        prefs.withdrawConsent()
        navigate(Screen.CONSENT)
        message = "Consent withdrawn. Listening is off. Saved recordings are kept."
    }

    private fun togglePlay(file: File) {
        if (playingPath == file.path) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            playingPath = file.path
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed", e)
            stopPlayback()
            message = "Can't play this recording"
        }
    }

    private fun stopPlayback() {
        try { player?.release() } catch (_: Exception) { }
        player = null
        playingPath = null
    }

    private fun deleteTapped(file: File) {
        if (confirmDeletePath != file.path) {
            confirmDeletePath = file.path      // first tap: ask to confirm
            return
        }
        if (playingPath == file.path) stopPlayback()
        file.delete()
        File(file.parentFile, file.name + ".sha256").delete()
        confirmDeletePath = null
        recordingsVersion++
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- UI

    @Composable
    private fun App() {
        val serviceState by KeywordListenerService.state.collectAsState()
        val serviceError by KeywordListenerService.error.collectAsState()
        val startedAt by KeywordListenerService.recordingStartedAt.collectAsState()
        val heard by KeywordListenerService.heard.collectAsState()

        val recordings = remember(serviceState, recordingsVersion) {
            KeywordListenerService.listRecordings(this@MainActivity)
        }

        // Ticks every half second while recording, for the timer.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(startedAt) {
            while (startedAt > 0) {
                now = System.currentTimeMillis()
                delay(500)
            }
        }

        val savedPhrase = prefs.secretPhrase.orEmpty()
        val hasPhrase = savedPhrase.isNotBlank()

        // Swipe-right (back) moves up one level instead of closing the app.
        BackHandler(enabled = screen !in setOf(Screen.HOME, Screen.CONSENT) &&
                !(screen == Screen.PHRASE && !hasPhrase)) {
            goBack()
        }

        val shownMessage = message ?: serviceError

        SafeGuardAIWearTheme {
            AppScaffold {
                key(screen) { // fresh scroll position for every screen
                    val listState = rememberTransformingLazyColumnState()
                    ScreenScaffold(scrollState = listState) { contentPadding ->
                        TransformingLazyColumn(
                            contentPadding = contentPadding,
                            state = listState
                        ) {
                            when (screen) {

                                // ================= CONSENT =================
                                Screen.CONSENT -> {
                                    item { Header("SafeGuardAI") }
                                    item { Body("SafeGuardAI records audio when you say a secret phrase.") }
                                    item { Body("• Listening happens only on this watch.") }
                                    item { Body("• Nothing is saved until you say your phrase.") }
                                    item { Body("• The 5 seconds before the phrase are included.") }
                                    item { Body("• You can turn it off or withdraw consent any time.") }
                                    item { Body("Recording laws differ by place. Check what applies to you.", small = true) }
                                    shownMessage?.let { m -> item { Note(m) } }
                                    item { Action("I agree – allow mic", onClick = ::requestConsent) }
                                }

                                // ================= PHRASE (step 1) =================
                                Screen.PHRASE -> {
                                    item { Header("Secret phrase") }
                                    item { Body("Choose 2–4 common English words you wouldn't say together by accident.") }
                                    item { Body("Example: purple pineapple tea", small = true) }
                                    shownMessage?.let { m -> item { Note(m) } }
                                    item {
                                        Action("Enter phrase", secondary = "Opens the keyboard",
                                            onClick = ::launchPhraseInput)
                                    }
                                    if (hasPhrase) {
                                        item { Action("Cancel", onClick = { navigate(Screen.SETTINGS) }) }
                                    }
                                }

                                // ================= PHRASE (step 2: confirm) =================
                                Screen.PHRASE_CONFIRM -> {
                                    item { Header("Confirm phrase") }
                                    item { Body("Your phrase is:", small = true) }
                                    item { Big("“$pendingPhrase”") }
                                    item { Body("Say it once to start recording and again to stop.", small = true) }
                                    shownMessage?.let { m -> item { Note(m) } }
                                    item {
                                        Action(if (busy) "Saving…" else "Save phrase",
                                            enabled = !busy, onClick = ::savePendingPhrase)
                                    }
                                    item {
                                        Action("Re-enter", enabled = !busy, onClick = ::launchPhraseInput)
                                    }
                                }

                                // ================= HOME =================
                                Screen.HOME -> {
                                    val isOn = serviceState != State.STOPPED
                                    item { Header("SafeGuardAI") }
                                    item {
                                        when (serviceState) {
                                            State.STOPPED -> Status("Protection is off")
                                            State.LOADING -> Status("Starting…")
                                            State.LISTENING -> Status("Listening for your phrase")
                                            State.RECORDING -> {
                                                val secs = ((now - startedAt).coerceAtLeast(0) / 1000).toInt()
                                                Status("● REC  %d:%02d".format(secs / 60, secs % 60), RecordingRed)
                                            }
                                        }
                                    }
                                    if (isOn && heard.isNotBlank()) {
                                        item { Body("Heard: “$heard”", small = true) }
                                    }
                                    shownMessage?.let { m -> item { Note(m) } }
                                    if (serviceState == State.RECORDING) {
                                        item {
                                            Action("Stop recording", danger = true,
                                                onClick = { KeywordListenerService.stopRecording(this@MainActivity) })
                                        }
                                    }
                                    item {
                                        if (isOn) {
                                            Action("Turn protection off", onClick = ::turnProtectionOff)
                                        } else {
                                            Action("Turn protection on",
                                                secondary = "Then say your phrase",
                                                onClick = ::turnProtectionOn)
                                        }
                                    }
                                    item {
                                        Action("Recordings", secondary = "${recordings.size} saved",
                                            onClick = { navigate(Screen.RECORDINGS) })
                                    }
                                    item { Action("Settings", onClick = { navigate(Screen.SETTINGS) }) }
                                }

                                // ================= RECORDINGS =================
                                Screen.RECORDINGS -> {
                                    item { Header("Recordings") }
                                    shownMessage?.let { m -> item { Note(m) } }
                                    if (recordings.isEmpty()) {
                                        item { Body("No recordings yet. Turn protection on and say your phrase.") }
                                    }
                                    recordings.forEach { file ->
                                        item {
                                            val playing = playingPath == file.path
                                            Action(
                                                formatDate(file.lastModified()),
                                                secondary = if (playing) "■ Playing – tap to stop"
                                                else "▶ Play · ${file.length() / 1024} KB",
                                                onClick = { togglePlay(file) }
                                            )
                                        }
                                        item {
                                            val confirming = confirmDeletePath == file.path
                                            Action(
                                                if (confirming) "Tap again to delete" else "Delete",
                                                danger = confirming,
                                                onClick = { deleteTapped(file) }
                                            )
                                        }
                                    }
                                    item { Action("Back", onClick = { navigate(Screen.HOME) }) }
                                }

                                // ================= SETTINGS =================
                                Screen.SETTINGS -> {
                                    item { Header("Settings") }
                                    item { Body("Secret phrase", small = true) }
                                    item {
                                        if (showPhrase) Big("“$savedPhrase”")
                                        else Body("•••••• (${savedPhrase.split(" ").size} words)")
                                    }
                                    shownMessage?.let { m -> item { Note(m) } }
                                    item {
                                        Action(if (showPhrase) "Hide phrase" else "Show phrase",
                                            onClick = { showPhrase = !showPhrase })
                                    }
                                    item { Action("Change phrase", onClick = { navigate(Screen.PHRASE) }) }
                                    item { Action("Withdraw consent", danger = true, onClick = ::withdrawConsent) }
                                    item { Action("Back", onClick = { navigate(Screen.HOME) }) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("d MMM, h:mm:ss a", Locale.getDefault()).format(Date(millis))

    companion object {
        private const val TAG = "SafeGuard"
        private const val PHRASE_KEY = "secret_phrase"

        // Standard Wear OS text-input screen (same values as androidx.wear.input).
        private const val ACTION_REMOTE_INPUT = "android.support.wearable.input.action.REMOTE_INPUT"
        private const val EXTRA_REMOTE_INPUTS = "android.support.wearable.input.extra.REMOTE_INPUTS"
        private const val EXTRA_TITLE = "android.support.wearable.input.extra.TITLE"
    }
}

// ==================================================================== UI pieces

@Composable
private fun Header(text: String) {
    ListHeader(modifier = Modifier.fillMaxWidth()) { Text(text) }
}

@Composable
private fun Body(text: String, small: Boolean = false) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun Big(text: String) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun Status(text: String, color: Color = Color.Unspecified) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun Action(
    text: String,
    secondary: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        colors = if (danger) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        } else {
            ButtonDefaults.buttonColors()
        }
    ) {
        Column {
            Text(text)
            if (secondary != null) {
                Text(secondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}