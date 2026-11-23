package com.example.verivoxmobileapp.playback

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.verivoxmobileapp.DetectionResult
import com.example.verivoxmobileapp.LocalInference
import com.example.verivoxmobileapp.LocalInferenceFailure
import com.example.verivoxmobileapp.MainActivity
import com.example.verivoxmobileapp.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

internal data class PlaybackCaptureState(val active: Boolean = false, val message: String? = null)

class PlaybackCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var session: Job? = null
    private var cancellationMessage = R.string.playback_stopped

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val current = session
            if (current == null || current.isCompleted) stopSelf() else current.cancel()
            return START_NOT_STICKY
        }
        if (session?.isCompleted == false) return START_NOT_STICKY

        val grant = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_PROJECTION, Intent::class.java) }
        if (grant == null || intent.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannels(this)
        val waiting = getString(R.string.playback_waiting, PLAYBACK_WAIT_SECONDS)
        try {
            startForeground(CAPTURE_ID, statusNotification(waiting), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not start playback foreground service", error)
            publishMessage(getString(R.string.playback_start_failed))
            stopSelf()
            return START_NOT_STICKY
        }
        if (!notificationsAvailable(this)) {
            publishMessage(getString(R.string.playback_notifications_required))
            stopSelf()
            return START_NOT_STICKY
        }
        notifications.cancel(RESULT_ID)
        mutableState.value = PlaybackCaptureState(active = true, message = waiting)

        cancellationMessage = R.string.playback_stopped
        session = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = captureAndAnalyze(grant)
                ensureActive()
                val title = getString(if (result.label == "genuine") R.string.playback_result_real else R.string.playback_result_fake)
                val percent = NumberFormat.getPercentInstance()
                val scores = getString(
                    R.string.playback_result_scores,
                    result.probReal?.let { percent.format(it) } ?: "—",
                    result.probFake?.let { percent.format(it) } ?: "—"
                )
                showResult(title, scores)
            } catch (error: CancellationException) {
                publishMessage(getString(cancellationMessage))
                throw error
            } catch (error: LocalInferenceFailure) {
                showResult(getString(R.string.playback_failed), error.displayMessage())
            } catch (error: PlaybackCaptureFailure) {
                showResult(getString(R.string.playback_failed), error.message.orEmpty())
            } catch (error: Exception) {
                Log.w(TAG, "Playback capture or inference failed", error)
                showResult(getString(R.string.playback_failed), getString(R.string.playback_capture_unavailable))
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun captureAndAnalyze(grant: Intent): DetectionResult {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(Activity.RESULT_OK, grant)
            ?: throw PlaybackCaptureFailure(getString(R.string.playback_not_authorized))
        var ending = false
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (ending) return
                cancellationMessage = R.string.playback_projection_stopped
                session?.cancel()
            }
        }
        try {
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
            val audio = PlaybackAudioCapture(this).capture(projection) {
                withContext(Dispatchers.Main.immediate) { setStatus(R.string.playback_recording) }
            }
            setStatus(R.string.playback_analyzing)
            return LocalInference.analyzeSamples(applicationContext, audio.samples, audio.sampleRate)
        } finally {
            ending = true
            projection.unregisterCallback(callback)
            projection.stop()
        }
    }

    private fun setStatus(messageId: Int) {
        val message = getString(messageId)
        mutableState.value = PlaybackCaptureState(active = true, message = message)
        notifications.notify(CAPTURE_ID, statusNotification(message))
    }

    private fun statusNotification(message: String): Notification {
        val stop = PendingIntent.getService(
            this, CAPTURE_ID, Intent(this, PlaybackCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return notification(CAPTURE_CHANNEL, getString(R.string.playback_channel_capture), message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.playback_stop), stop)
            .build()
    }

    private fun showResult(title: String, message: String) {
        publishMessage("$title\n$message")
        if (!notificationsAvailable(this)) return
        notifications.notify(RESULT_ID, notification(RESULT_CHANNEL, title, message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build())
    }

    private fun notification(channel: String, title: String, message: String): NotificationCompat.Builder {
        val openApp = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
    }

    override fun onDestroy() {
        scope.cancel()
        if (state.value.active) publishMessage(getString(cancellationMessage))
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VeriVoxPlayback"
        private const val ACTION_START = "com.example.verivoxmobileapp.START_PLAYBACK_CAPTURE"
        private const val ACTION_STOP = "com.example.verivoxmobileapp.STOP_PLAYBACK_CAPTURE"
        private const val EXTRA_PROJECTION = "projection_grant"
        private const val CAPTURE_CHANNEL = "playback_capture"
        private const val RESULT_CHANNEL = "playback_result"
        private const val CAPTURE_ID = 1002
        private const val RESULT_ID = 1003

        private val mutableState = MutableStateFlow(PlaybackCaptureState())
        internal val state = mutableState.asStateFlow()

        internal fun publishMessage(message: String) {
            mutableState.value = PlaybackCaptureState(message = message)
        }

        internal fun start(context: Context, grant: Intent) {
            ContextCompat.startForegroundService(
                context, Intent(context, PlaybackCaptureService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PROJECTION, grant)
            )
        }

        internal fun stop(context: Context) {
            context.startService(Intent(context, PlaybackCaptureService::class.java).setAction(ACTION_STOP))
        }

        internal fun notificationsAvailable(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                manager.getNotificationChannel(RESULT_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        private fun createChannels(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
                NotificationChannel(CAPTURE_CHANNEL, context.getString(R.string.playback_channel_capture), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(RESULT_CHANNEL, context.getString(R.string.playback_channel_results), NotificationManager.IMPORTANCE_HIGH)
            ))
        }
    }
}
