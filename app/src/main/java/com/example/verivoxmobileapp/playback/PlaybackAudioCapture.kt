package com.example.verivoxmobileapp.playback

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.SystemClock
import com.example.verivoxmobileapp.LocalInference
import com.example.verivoxmobileapp.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

internal const val PLAYBACK_WAIT_SECONDS = 60

internal data class CapturedPlayback(val samples: FloatArray, val sampleRate: Int)

internal class PlaybackCaptureFailure(message: String) : IOException(message)

internal class PlaybackAudioCapture(private val context: Context) {
    suspend fun capture(projection: MediaProjection, onAudioDetected: suspend () -> Unit): CapturedPlayback =
        withContext(Dispatchers.IO) {
            val configuration = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val recorder = createRecorder(configuration)
            try {
                val sampleRate = recorder.sampleRate
                val channels = recorder.channelCount
                val window = PlaybackPcmWindow(sampleRate * LocalInference.CHUNK_SECONDS, channels)
                val buffer = ShortArray(sampleRate / 50 * channels)
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw PlaybackCaptureFailure(context.getString(R.string.playback_capture_unavailable))
                }

                var deadline = SystemClock.elapsedRealtime() + PLAYBACK_WAIT_SECONDS * 1_000L
                while (!window.isComplete) {
                    currentCoroutineContext().ensureActive()
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        val message = if (window.hasAudio) {
                            context.getString(R.string.playback_capture_interrupted)
                        } else {
                            context.getString(R.string.playback_no_audio, PLAYBACK_WAIT_SECONDS)
                        }
                        throw PlaybackCaptureFailure(message)
                    }
                    val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    if (count < 0) throw PlaybackCaptureFailure(context.getString(R.string.playback_capture_interrupted))
                    if (count == 0) {
                        delay(15)
                        continue
                    }
                    val hadAudio = window.hasAudio
                    window.append(buffer, count)
                    if (!hadAudio && window.hasAudio) {
                        deadline = SystemClock.elapsedRealtime() + RECORDING_TIMEOUT_MS
                        onAudioDetected()
                    }
                }
                CapturedPlayback(window.samples, sampleRate)
            } finally {
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                } catch (_: IllegalStateException) {
                    // The recorder may already have stopped when projection was revoked.
                } finally {
                    recorder.release()
                }
            }
        }

    // The activity requests RECORD_AUDIO before the non-exported service starts.
    @SuppressLint("MissingPermission")
    private fun createRecorder(configuration: AudioPlaybackCaptureConfiguration): AudioRecord {
        for (rate in intArrayOf(48_000, 44_100)) {
            for (mask in intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)) {
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(rate, mask, encoding)
                if (bufferSize <= 0) continue
                val format = AudioFormat.Builder().setEncoding(encoding).setSampleRate(rate).setChannelMask(mask).build()
                val recorder = try {
                    AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferSize * 2)
                        .setAudioPlaybackCaptureConfig(configuration)
                        .build()
                } catch (_: IllegalArgumentException) {
                    continue
                } catch (_: UnsupportedOperationException) {
                    continue
                }
                if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
                recorder.release()
            }
        }
        throw PlaybackCaptureFailure(context.getString(R.string.playback_capture_unavailable))
    }

    private companion object {
        const val RECORDING_TIMEOUT_MS = 10_000L
    }
}
