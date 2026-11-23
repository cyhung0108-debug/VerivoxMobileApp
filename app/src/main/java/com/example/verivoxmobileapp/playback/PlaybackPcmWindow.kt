package com.example.verivoxmobileapp.playback

import kotlin.math.abs

/** Keeps a single mono clip from the first audible playback sample. */
internal class PlaybackPcmWindow(frameCount: Int, private val channelCount: Int) {
    val samples = FloatArray(frameCount)
    private var written = 0
    val hasAudio: Boolean get() = written != 0
    val isComplete: Boolean get() = written == samples.size

    fun append(pcm: ShortArray, sampleCount: Int) {
        var offset = 0
        while (offset + channelCount <= sampleCount && !isComplete) {
            var sum = 0
            repeat(channelCount) { sum += pcm[offset++].toInt() }
            val sample = sum.toFloat() / channelCount
            if (hasAudio || abs(sample) >= AUDIBLE_PEAK) {
                samples[written++] = sample / 32768f
            }
        }
    }

    private companion object {
        const val AUDIBLE_PEAK = 32f
    }
}
