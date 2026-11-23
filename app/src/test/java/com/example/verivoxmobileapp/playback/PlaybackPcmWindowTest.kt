package com.example.verivoxmobileapp.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPcmWindowTest {
    @Test
    fun silentInputDoesNotStartTheWindow() {
        val window = PlaybackPcmWindow(frameCount = 2, channelCount = 1)
        window.append(shortArrayOf(0, -31, 31, 0), 4)
        assertFalse(window.hasAudio)
        assertFalse(window.isComplete)
    }

    @Test
    fun keepsAudioFromFirstAudibleFrameAcrossReads() {
        val window = PlaybackPcmWindow(frameCount = 4, channelCount = 1)
        window.append(shortArrayOf(0, -16384, 0), 3)
        window.append(shortArrayOf(8192, -8192, 16384), 3)
        assertTrue(window.isComplete)
        assertArrayEquals(floatArrayOf(-0.5f, 0f, 0.25f, -0.25f), window.samples, 0f)
    }

    @Test
    fun mixesStereoFramesBeforeScalingToFloat() {
        val window = PlaybackPcmWindow(frameCount = 2, channelCount = 2)
        window.append(shortArrayOf(16384, -16384, 32767, 32767, -32768, 0), 6)
        assertTrue(window.isComplete)
        assertArrayEquals(floatArrayOf(32767f / 32768f, -0.5f), window.samples, 0f)
    }

    @Test
    fun ignoresUnreadSamplesInReusableBuffer() {
        val window = PlaybackPcmWindow(frameCount = 2, channelCount = 1)
        window.append(shortArrayOf(16384, -16384), 1)
        assertFalse(window.isComplete)
        window.append(shortArrayOf(0), 1)
        assertArrayEquals(floatArrayOf(0.5f, 0f), window.samples, 0f)
    }
}
