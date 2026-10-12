package com.example.safeguardaiwear.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.example.safeguardaiwear.presentation.theme.SafeGuardAIWearTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private var recorder: MediaRecorder? = null
    private var isRecording by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Ready to record")
    private var pendingRecording = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && pendingRecording) {
                pendingRecording = false
                startRecording()
            } else {
                pendingRecording = false
                statusMessage = "Microphone permission denied"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WearApp(
                isRecording = isRecording,
                statusMessage = statusMessage,
                onStartRecording = { checkPermissionAndRecord() },
                onStopRecording = { stopRecording() }
            )
        }
    }

    private fun checkPermissionAndRecord() {
        val permissionGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        if (permissionGranted) {
            startRecording()
        } else {
            pendingRecording = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        if (isRecording) return

        val recordingsFolder = File(filesDir, "recordings")
        if (!recordingsFolder.exists()) {
            recordingsFolder.mkdirs()
        }

        val audioFile = File(
            recordingsFolder,
            "recording_${System.currentTimeMillis()}.m4a"
        )

        try {
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            statusMessage = "Recording..."
        } catch (exception: Exception) {
            recorder?.release()
            recorder = null
            isRecording = false
            statusMessage = "Could not start recording"
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        try {
            recorder?.stop()
            statusMessage = "Recording saved"
        } catch (exception: Exception) {
            statusMessage = "Recording failed"
        } finally {
            recorder?.release()
            recorder = null
            isRecording = false
        }
    }

    override fun onDestroy() {
        if (isRecording) {
            stopRecording()
        }
        super.onDestroy()
    }
}

@Composable
fun WearApp(
    isRecording: Boolean,
    statusMessage: String,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    SafeGuardAIWearTheme {
        AppScaffold {
            val listState = rememberTransformingLazyColumnState()
            val transformationSpec = rememberTransformationSpec()

            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(
                    contentPadding = contentPadding,
                    state = listState
                ) {
                    item {
                        ListHeader(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) {
                            Text("SafeGuardAI Audio")
                        }
                    }

                    item {
                        Text(statusMessage)
                    }

                    item {
                        Button(
                            onClick = onStartRecording,
                            enabled = !isRecording,
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) {
                            Text("Start Recording")
                        }
                    }

                    item {
                        Button(
                            onClick = onStopRecording,
                            enabled = isRecording,
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) {
                            Text("Stop Recording")
                        }
                    }
                }
            }
        }
    }
}