package com.example.safeguardaiwear.presentation

import android.Manifest
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.ContactsContract
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
import com.example.safeguardaiwear.alert.AlertSender
import com.example.safeguardaiwear.alert.Secrets
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

enum class Screen { CONSENT, PHRASE, PHRASE_CONFIRM, HOME, RECORDINGS, SETTINGS, CONTACT, CONTACT_PICK }

/** One phone number from the user's contacts. */
data class PhoneContact(val name: String, val number: String)

/** Which field the Wear OS keyboard screen is filling in. */
enum class InputField { PHRASE, CONTACT_NAME, CONTACT_PHONE }

private val RecordingRed = Color(0xFFFF5252)

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SafeGuardPrefs

    private var screen by mutableStateOf(Screen.CONSENT)
    private var message by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var pendingPhrase by mutableStateOf("")
    private var showPhrase by mutableStateOf(false)
    private var settingsVersion by mutableIntStateOf(0)
    private var playedSinceRestart = false
    private var contactVersion by mutableIntStateOf(0)
    private var inputField = InputField.PHRASE
    private var phoneContacts by mutableStateOf<List<PhoneContact>>(emptyList())
    private var contactsLoading by mutableStateOf(false)
    private var recordingsVersion by mutableIntStateOf(0)
    private var playingPath by mutableStateOf<String?>(null)
    private var confirmDeletePath by mutableStateOf<String?>(null)

    private var player: MediaPlayer? = null
    private var wasInBackground = false

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

    // ---------------------------------------------------------------- contacts

    /** Asks for permission to read contacts (only when the user taps "Choose from contacts"). */
    private val contactsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                openContactPicker()
            } else {
                message = "Contacts access was not allowed. You can enter the number manually."
            }
        }

    private fun chooseFromContacts() {
        message = null
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) openContactPicker()
        else contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
    }

    /** Loads every contact that has a phone number and shows them as a list. */
    private fun openContactPicker() {
        contactsLoading = true
        phoneContacts = emptyList()
        navigate(Screen.CONTACT_PICK)
        Thread {
            val list = try {
                readPhoneContacts()
            } catch (e: Exception) {
                Log.e(TAG, "Could not read contacts", e)
                emptyList()
            }
            runOnUiThread {
                phoneContacts = list
                contactsLoading = false
            }
        }.start()
    }

    private fun readPhoneContacts(): List<PhoneContact> {
        val result = mutableListOf<PhoneContact>()
        val seen = mutableSetOf<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val number = if (numberCol >= 0) cursor.getString(numberCol).orEmpty() else ""
                if (number.isBlank()) continue
                val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                val key = number.filter { it.isDigit() }
                if (seen.add(key)) result.add(PhoneContact(name.ifBlank { number }, number))
            }
        }
        return result
    }

    private fun pickContact(contact: PhoneContact) {
        val phone = AlertSender.normalisePhone(contact.number)
        if (phone == null) {
            message = "${contact.number} can't receive SMS. Choose another contact."
            return
        }
        prefs.contactName = contact.name.take(30)
        prefs.contactPhone = phone
        contactVersion++
        navigate(Screen.CONTACT)
        message = "✓ ${contact.name} will get the alerts"
    }

    // ---------------------------------------------------------------- text input

    /** Opens the standard Wear OS text-input screen (keyboard / handwriting). */
    private val textInputLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data?.let { data ->
                RemoteInput.getResultsFromIntent(data)?.getCharSequence(INPUT_KEY)?.toString()
            }
            if (text.isNullOrBlank()) {
                message = "Nothing entered. Tap the button to try again."
                return@registerForActivityResult
            }
            when (inputField) {
                InputField.PHRASE -> onPhraseEntered(text)
                InputField.CONTACT_NAME -> {
                    prefs.contactName = text.trim().take(30)
                    contactVersion++
                    message = "✓ Name saved"
                }
                InputField.CONTACT_PHONE -> {
                    val phone = AlertSender.normalisePhone(text)
                    if (phone == null) {
                        message = "“$text” doesn't look like a phone number. Example: 0412 345 678"
                    } else {
                        prefs.contactPhone = phone
                        contactVersion++
                        message = "✓ Number saved: $phone"
                    }
                }
            }
        }

    private fun onPhraseEntered(text: String) {
        val phrase = SafeGuardPrefs.normalise(text)
        val words = if (phrase.isEmpty()) 0 else phrase.split(" ").size
        if (words < SafeGuardPrefs.MIN_WORDS || words > SafeGuardPrefs.MAX_WORDS) {
            message = "“$phrase” has $words word(s). Use ${SafeGuardPrefs.MIN_WORDS}–${SafeGuardPrefs.MAX_WORDS} words."
            return
        }
        pendingPhrase = phrase
        navigate(Screen.PHRASE_CONFIRM)
    }

    private fun launchTextInput(field: InputField, title: String, hint: String) {
        message = null
        inputField = field
        val remoteInput = RemoteInput.Builder(INPUT_KEY)
            .setLabel(hint)
            .build()
        val intent = Intent(ACTION_REMOTE_INPUT).apply {
            putExtra(EXTRA_REMOTE_INPUTS, arrayOf<Parcelable>(remoteInput))
            putExtra(EXTRA_TITLE, title)
        }
        try {
            textInputLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "No text input screen", e)
            message = "This watch has no text input screen."
        }
    }

    private fun launchPhraseInput() =
        launchTextInput(InputField.PHRASE, "Secret phrase", "Type your secret phrase")

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
            return
        }
        // Protection is meant to be on but Android stopped the listener -> restart it.
        if (prefs.protectionOn && prefs.consentGiven && !prefs.secretPhrase.isNullOrBlank() &&
            KeywordListenerService.state.value == State.STOPPED
        ) {
            KeywordListenerService.start(this)?.let { message = it }
        } else if (wasInBackground) {
            // Coming back from another screen (keyboard, contacts, permission, home):
            // the microphone can stay silent afterwards, so restart listening fully –
            // the same as turning protection off and on.
            restartListening()
        }
        wasInBackground = false
    }

    override fun onStop() {
        // Only tell the listener about playback if something was actually playing.
        if (player != null) stopPlayback()
        wasInBackground = true
        super.onStop()
    }

    // ---------------------------------------------------------------- actions

    private fun navigate(to: Screen) {
        // Leaving the Recordings screen stops playback, so listening resumes at once.
        if (screen == Screen.RECORDINGS && to != Screen.RECORDINGS) {
            stopPlayback()
            if (playedSinceRestart) {
                playedSinceRestart = false
                restartListening()
            }
        }
        message = null
        confirmDeletePath = null
        showPhrase = false
        screen = to
    }

    private fun goBack() {
        when (screen) {
            Screen.PHRASE_CONFIRM -> navigate(Screen.PHRASE)
            Screen.PHRASE -> navigate(Screen.SETTINGS)
            Screen.CONTACT -> navigate(Screen.SETTINGS)
            Screen.CONTACT_PICK -> navigate(Screen.CONTACT)
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
                if (!prefs.hasContact) {
                    // First-time setup: next step is the emergency contact.
                    navigate(Screen.CONTACT)
                    message = "✓ Phrase saved. Now add an emergency contact to receive alerts."
                    return@runOnUiThread
                }
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
        prefs.protectionOn = true
        message = KeywordListenerService.start(this)
    }

    private fun turnProtectionOff() {
        prefs.protectionOn = false
        message = null
        KeywordListenerService.stop(this)
    }

    private fun toggleRiskDetection() {
        prefs.riskDetectionEnabled = !prefs.riskDetectionEnabled
        contactVersion++   // refresh the settings screen
        if (KeywordListenerService.state.value != State.STOPPED) {
            // Restart listening so the change takes effect.
            KeywordListenerService.stop(this)
            startWhenStopped(attempt = 0)
        }
    }

    private fun sendTestAlert() {
        message = null
        KeywordListenerService.sendAlertAsync(this, confidencePercent = null)
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
            playedSinceRestart = true
            KeywordListenerService.setPlaybackActive(true)
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed", e)
            stopPlayback()
            message = "Can't play this recording"
        }
    }

    /**
     * Fully restarts the listener (same as turning protection off and on).
     * Done after audio was played or the app was in the background, because on
     * some devices / the emulator the microphone stops delivering sound.
     */
    private fun restartListening() {
        val state = KeywordListenerService.state.value
        // Don't interrupt a recording that is in progress.
        if (!prefs.protectionOn || state == State.STOPPED || state == State.RECORDING) return
        Log.i(TAG, "Restarting listener")
        KeywordListenerService.stop(this)
        startWhenStopped(attempt = 0)
    }

    /** Waits until the old listener has fully stopped, then starts a new one. */
    private fun startWhenStopped(attempt: Int) {
        window.decorView.postDelayed({
            if (!prefs.protectionOn) return@postDelayed
            if (KeywordListenerService.state.value == State.STOPPED) {
                KeywordListenerService.start(this)?.let { message = it }
            } else if (attempt < 20) {
                startWhenStopped(attempt + 1)
            }
        }, 300)
    }

    private fun togglePreRoll() {
        prefs.includePreRoll = !prefs.includePreRoll
        settingsVersion++
    }

    private fun stopPlayback() {
        KeywordListenerService.setPlaybackActive(false)
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
        val pausedForAudio by KeywordListenerService.paused.collectAsState()
        val risk by KeywordListenerService.risk.collectAsState()
        val riskThreshold by KeywordListenerService.riskThreshold.collectAsState()
        val topSound by KeywordListenerService.topSound.collectAsState()
        val lastRiskAt by KeywordListenerService.lastRiskAt.collectAsState()
        val alertStatus by KeywordListenerService.alertStatus.collectAsState()
        val contactName = remember(contactVersion) { prefs.contactName }
        val contactPhone = remember(contactVersion) { prefs.contactPhone }
        val riskOn = remember(contactVersion) { prefs.riskDetectionEnabled }
        val alertLog = remember(contactVersion, alertStatus) { prefs.alertLog }
        // Recomposes every ~0.5 s while the AI runs, so this stays current.
        val recentDanger = lastRiskAt > 0 && System.currentTimeMillis() - lastRiskAt < 60_000

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
                                    item { Body("• You can choose to include the 5 seconds before the phrase.") }
                                    item { Body("• An AI also listens for signs of danger (shouting, screaming, crying, hits). If it hears them, it records and texts your emergency contact.") }
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
                                    if (isOn && recentDanger) {
                                        item { Status("⚠ Possible danger detected", RecordingRed) }
                                    }
                                    item {
                                        when (serviceState) {
                                            State.STOPPED -> Status("Protection is off")
                                            State.LOADING -> Status("Starting…")
                                            State.LISTENING ->
                                                if (pausedForAudio) Status("Paused – audio playing")
                                                else Status("Listening for your phrase")
                                            State.RECORDING -> {
                                                val secs = ((now - startedAt).coerceAtLeast(0) / 1000).toInt()
                                                Status("● REC  %d:%02d".format(secs / 60, secs % 60), RecordingRed)
                                            }
                                        }
                                    }
                                    if (isOn && risk >= 0f && !pausedForAudio) {
                                        item {
                                            val pct = (risk * 100).toInt()
                                            val sound = if (topSound.isNotBlank()) " · $topSound" else ""
                                            Body(
                                                "AI risk: $pct%$sound",
                                                small = true,
                                                color = if (risk >= riskThreshold) RecordingRed else Color.Unspecified
                                            )
                                        }
                                    }
                                    if (isOn && heard.isNotBlank()) {
                                        item { Body("Heard: “$heard”", small = true) }
                                    }
                                    alertStatus?.let { st -> item { Note(st) } }
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
                                    if (playingPath != null) {
                                        item { Note("Phrase detection paused while playing") }
                                    }
                                    shownMessage?.let { m -> item { Note(m) } }
                                    if (recordings.isEmpty()) {
                                        item { Body("No recordings yet. Turn protection on and say your phrase.") }
                                    }
                                    recordings.forEach { file ->
                                        item {
                                            val playing = playingPath == file.path
                                            val byAi = file.name.startsWith("risk_")
                                            Action(
                                                (if (byAi) "⚠ AI · " else "") + formatDate(file.lastModified()),
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
                                    item {
                                        val includeBefore = remember(settingsVersion) { prefs.includePreRoll }
                                        Action("Include 5 s before phrase",
                                            secondary = if (includeBefore) "On – tap to turn off" else "Off – tap to turn on",
                                            onClick = ::togglePreRoll)
                                    }
                                    item {
                                        Action("Emergency contact",
                                            secondary = if (contactPhone.isBlank()) "Not set"
                                            else contactName.ifBlank { contactPhone },
                                            onClick = { navigate(Screen.CONTACT) })
                                    }
                                    item {
                                        Action("AI danger detection",
                                            secondary = if (riskOn) "On – tap to turn off" else "Off – tap to turn on",
                                            onClick = ::toggleRiskDetection)
                                    }
                                    item { Action("Withdraw consent", danger = true, onClick = ::withdrawConsent) }
                                    item { Action("Back", onClick = { navigate(Screen.HOME) }) }
                                }

                                // ================= EMERGENCY CONTACT =================
                                Screen.CONTACT -> {
                                    item { Header("Emergency contact") }
                                    item {
                                        Body("Gets an SMS if the AI detects possible danger.", small = true)
                                    }
                                    shownMessage?.let { m -> item { Note(m) } }
                                    item {
                                        if (contactPhone.isBlank()) {
                                            Body("No contact chosen yet")
                                        } else {
                                            Big(contactName.ifBlank { contactPhone })
                                        }
                                    }
                                    if (contactPhone.isNotBlank() && contactName.isNotBlank()) {
                                        item { Body(contactPhone, small = true) }
                                    }
                                    item {
                                        Action(if (contactPhone.isBlank()) "Choose from contacts" else "Change contact",
                                            secondary = "Pick someone from your contacts",
                                            onClick = ::chooseFromContacts)
                                    }
                                    item { Body("Or enter manually:", small = true) }
                                    item {
                                        Action("Mobile number", secondary = contactPhone.ifBlank { "Not set" },
                                            onClick = {
                                                launchTextInput(InputField.CONTACT_PHONE, "Mobile number", "e.g. 0412 345 678")
                                            })
                                    }
                                    item {
                                        Action("Name", secondary = contactName.ifBlank { "Not set" },
                                            onClick = {
                                                launchTextInput(InputField.CONTACT_NAME, "Contact name", "e.g. Mum")
                                            })
                                    }
                                    item {
                                        Action("Send test SMS", enabled = contactPhone.isNotBlank(),
                                            onClick = ::sendTestAlert)
                                    }
                                    alertStatus?.let { st -> item { Note(st) } }
                                    item {
                                        Body("If the AI detects danger, this contact gets an SMS straight away, " +
                                                "then a second SMS with a link to 60 s of audio.", small = true)
                                    }
                                    if (!Secrets.twilioConfigured()) {
                                        item {
                                            Body("SMS service not set up yet – alerts are simulated (see Secrets.kt).",
                                                small = true)
                                        }
                                    }
                                    if (!Secrets.cloudinaryConfigured()) {
                                        item {
                                            Body("Audio upload not set up yet – audio stays on the watch (see Secrets.kt).",
                                                small = true)
                                        }
                                    }
                                    if (alertLog.isNotEmpty()) {
                                        item { Body("Recent alerts", small = true) }
                                        alertLog.take(5).forEach { entry -> item { Body(entry, small = true) } }
                                    }
                                    item { Action("Done", onClick = { navigate(Screen.HOME) }) }
                                }

                                // ================= PICK FROM CONTACTS =================
                                Screen.CONTACT_PICK -> {
                                    item { Header("Choose contact") }
                                    shownMessage?.let { m -> item { Note(m) } }
                                    when {
                                        contactsLoading -> item { Body("Loading contacts…") }
                                        phoneContacts.isEmpty() -> {
                                            item {
                                                Body("No contacts with a phone number on this watch. " +
                                                        "Add one in the Contacts app (or on the paired phone), " +
                                                        "or enter the number manually.")
                                            }
                                        }
                                        else -> phoneContacts.forEach { c ->
                                            item {
                                                Action(c.name, secondary = c.number, onClick = { pickContact(c) })
                                            }
                                        }
                                    }
                                    item { Action("Back", onClick = { navigate(Screen.CONTACT) }) }
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
        private const val INPUT_KEY = "safeguard_input"

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
private fun Body(text: String, small: Boolean = false, color: Color = Color.Unspecified) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        color = color,
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