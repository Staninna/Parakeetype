package org.schabi.parakeetype.notes

import android.Manifest
import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.schabi.parakeetype.R
import org.schabi.parakeetype.audio.PermissionHelper
import org.schabi.parakeetype.recognition.InferenceConnection
import java.io.File
import java.io.IOException

private const val TAG = "VoiceNoteTest"

private sealed interface TestState {
    data object Idle : TestState
    data object Recording : TestState
    data object Transcribing : TestState
    data class Done(val text: String) : TestState
    data class Failed(val message: Int) : TestState
}

/**
 * Records a message as an Ogg Opus voice note, the format WhatsApp sends, and transcribes it
 * with [transcribeVoiceNoteFile], the path received notes take ([VoiceNotesActivity] opens it).
 * Nothing leaves the app; the recording is deleted when the dialog closes.
 */
@Composable
internal fun VoiceNoteTestDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inference = remember { InferenceConnection(context) }
    val file = remember { File(context.cacheDir, "voice-note-test.ogg") }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var state by remember { mutableStateOf<TestState>(TestState.Idle) }
    var seconds by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            recorder?.release()
            inference.unbind()
            file.delete()
        }
    }
    LaunchedEffect(state) {
        seconds = 0
        while (state == TestState.Recording) {
            delay(1_000)
            seconds++
        }
    }

    fun record() {
        recorder = try {
            startRecording(context, file)
        } catch (e: Exception) { // IOException, IllegalStateException, RuntimeException from the mic
            Log.w(TAG, "Recording failed to start: ${e.message}")
            state = TestState.Failed(R.string.voice_note_test_failed)
            return
        }
        state = TestState.Recording
    }

    fun stopAndTranscribe() {
        val active = recorder ?: return
        recorder = null
        val recorded = try {
            active.stop()
            true
        } catch (e: RuntimeException) { // stop() right after start(): nothing was recorded
            false
        } finally {
            active.release()
        }
        if (!recorded) {
            state = TestState.Idle
            return
        }
        state = TestState.Transcribing
        scope.launch {
            state = when (val result = inference.awaitRepository()) {
                is InferenceConnection.Result.Unavailable -> {
                    Log.w(TAG, "Engine unavailable: ${result.reason}")
                    TestState.Failed(R.string.voice_note_test_failed)
                }
                is InferenceConnection.Result.Ready -> try {
                    TestState.Done(transcribeVoiceNoteFile(context, result.repository.speechEngine, file.path))
                } catch (e: IOException) {
                    Log.w(TAG, "Transcribing the test note failed: ${e.message}")
                    TestState.Failed(R.string.voice_note_test_failed)
                }
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) record() else state = TestState.Failed(R.string.voice_note_test_no_mic)
    }
    val start = {
        if (PermissionHelper.hasRecordPermission(context)) record()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_note_test_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val s = state) {
                    TestState.Idle -> Text(stringResource(R.string.voice_note_test_intro))
                    TestState.Recording -> Text(
                        stringResource(R.string.voice_note_test_recording, "%d:%02d".format(seconds / 60, seconds % 60)),
                    )
                    TestState.Transcribing -> Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(stringResource(R.string.voice_note_test_transcribing))
                    }
                    is TestState.Done -> SelectionContainer {
                        Text(
                            s.text.ifBlank { stringResource(R.string.voice_note_no_speech) },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    is TestState.Failed -> Text(stringResource(s.message))
                }
            }
        },
        confirmButton = {
            when (state) {
                TestState.Idle -> TextButton(onClick = start) { Text(stringResource(R.string.voice_note_test_record)) }
                TestState.Recording -> TextButton(onClick = ::stopAndTranscribe) {
                    Text(stringResource(R.string.voice_note_test_stop))
                }
                TestState.Transcribing -> Unit
                is TestState.Done, is TestState.Failed -> TextButton(onClick = start) {
                    Text(stringResource(R.string.voice_note_test_again))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_note_test_close)) }
        },
    )
}

/** Starts recording the mic to [file] as 16 kHz mono Ogg Opus, like a WhatsApp voice note. */
private fun startRecording(context: Context, file: File): MediaRecorder =
    MediaRecorder(context).apply {
        try {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setAudioSamplingRate(16_000)
            setAudioChannels(1)
            setAudioEncodingBitRate(24_000)
            setOutputFile(file.path)
            prepare()
            start()
        } catch (e: Exception) {
            release()
            throw e
        }
    }
