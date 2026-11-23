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

internal data class PlaybackCaptureState(
    val sessionOpen: Boolean = false,
    val active: Boolean = false,
    val message: String? = null
)

class PlaybackCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var projectionSession: ProjectionSession? = null
    private var analysisJob: Job? = null

    private data class ProjectionSession(val projection: MediaProjection, val callback: MediaProjection.Callback)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (projectionSession == null) openProjection(intent) else analyzePlayback()
            ACTION_ANALYZE -> analyzePlayback()
            ACTION_STOP -> if (projectionSession == null) stopSelf() else analysisJob?.cancel()
            ACTION_END_SESSION -> endSession(R.string.playback_session_ended)
            else -> if (projectionSession == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun openProjection(intent: Intent) {
        val grant = IntentCompat.getParcelableExtra(intent, EXTRA_PROJECTION, Intent::class.java)
        if (grant == null) {
            endSession(R.string.playback_not_authorized)
            return
        }
        createChannels(this)
        try {
            startForeground(
                CAPTURE_ID,
                statusNotification(getString(R.string.playback_preparing), canCancelAnalysis = false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            if (!notificationsAvailable(this)) {
                endSession(R.string.playback_notifications_required)
                return
            }
            val manager = getSystemService(MediaProjectionManager::class.java)
            // The consent Intent is consumed once. Only the live projection is retained.
            val projection = manager.getMediaProjection(Activity.RESULT_OK, grant)
            if (projection == null) {
                endSession(R.string.playback_not_authorized)
                return
            }
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projectionSession?.projection === projection) {
                        endSession(R.string.playback_projection_stopped, stoppedBySystem = true)
                    }
                }
            }
            projectionSession = ProjectionSession(projection, callback)
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not start media projection", error)
            endSession(R.string.playback_start_failed)
            return
        }
        analyzePlayback()
    }

    private fun analyzePlayback() {
        if (analysisJob?.isCompleted == false) return
        val current = projectionSession
        if (current == null) {
            endSession(R.string.playback_projection_stopped)
            return
        }
        if (!notificationsAvailable(this)) {
            endSession(R.string.playback_notifications_required)
            return
        }

        val waiting = getString(R.string.playback_waiting, PLAYBACK_WAIT_SECONDS)
        notifications.cancel(RESULT_ID)
        setStatus(waiting)

        analysisJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var outcomeMessage = getString(R.string.playback_stopped)
            try {
                val result = captureAndAnalyze(current.projection)
                ensureActive()
                val title = getString(if (result.label == "genuine") R.string.playback_result_real else R.string.playback_result_fake)
                val percent = NumberFormat.getPercentInstance()
                val scores = getString(
                    R.string.playback_result_scores,
                    result.probReal?.let { percent.format(it) } ?: "—",
                    result.probFake?.let { percent.format(it) } ?: "—"
                )
                outcomeMessage = showResult(title, scores)
            } catch (error: CancellationException) {
                throw error
            } catch (error: LocalInferenceFailure) {
                outcomeMessage = showResult(getString(R.string.playback_failed), error.displayMessage())
            } catch (error: PlaybackCaptureFailure) {
                outcomeMessage = showResult(getString(R.string.playback_failed), error.message.orEmpty())
            } catch (error: Exception) {
                Log.w(TAG, "Playback capture or inference failed", error)
                outcomeMessage = showResult(getString(R.string.playback_failed), getString(R.string.playback_capture_unavailable))
            } finally {
                if (projectionSession === current) {
                    mutableState.value = PlaybackCaptureState(sessionOpen = true, message = outcomeMessage)
                    notifications.notify(CAPTURE_ID, statusNotification(getString(R.string.playback_session_ready), false))
                }
            }
        }
    }

    private suspend fun captureAndAnalyze(projection: MediaProjection): DetectionResult {
        val audio = PlaybackAudioCapture(this).capture(projection) {
            withContext(Dispatchers.Main.immediate) {
                setStatus(getString(R.string.playback_recording))
            }
        }
        setStatus(getString(R.string.playback_analyzing))
        return LocalInference.analyzeSamples(applicationContext, audio.samples, audio.sampleRate)
    }

    private fun setStatus(message: String) {
        mutableState.value = PlaybackCaptureState(sessionOpen = true, active = true, message = message)
        notifications.notify(CAPTURE_ID, statusNotification(message, canCancelAnalysis = true))
    }

    private fun statusNotification(message: String, canCancelAnalysis: Boolean): Notification {
        val notification = notification(CAPTURE_CHANNEL, getString(R.string.playback_channel_capture), message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (canCancelAnalysis) {
            notification.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.playback_stop), action(ACTION_STOP))
        }
        return notification.addAction(
            android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.playback_end_session), action(ACTION_END_SESSION)
        ).build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this, CAPTURE_ID, Intent(this, PlaybackCaptureService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun showResult(title: String, message: String): String {
        if (notificationsAvailable(this)) {
            notifications.notify(RESULT_ID, notification(RESULT_CHANNEL, title, message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build())
        }
        return "$title\n$message"
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

    private fun endSession(messageId: Int, stoppedBySystem: Boolean = false) {
        closeProjection(stoppedBySystem)
        mutableState.value = PlaybackCaptureState(message = getString(messageId))
        stopSelf()
    }

    private fun closeProjection(stoppedBySystem: Boolean = false) {
        val current = projectionSession
        projectionSession = null
        analysisJob?.cancel()
        analysisJob = null
        if (current != null) {
            current.projection.unregisterCallback(current.callback)
            if (!stoppedBySystem) current.projection.stop()
        }
    }

    override fun onDestroy() {
        closeProjection()
        scope.cancel()
        if (state.value.sessionOpen || state.value.active) {
            mutableState.value = PlaybackCaptureState(message = getString(R.string.playback_projection_stopped))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VeriVoxPlayback"
        private const val ACTION_START = "com.example.verivoxmobileapp.START_PLAYBACK_CAPTURE"
        private const val ACTION_ANALYZE = "com.example.verivoxmobileapp.ANALYZE_PLAYBACK_AGAIN"
        private const val ACTION_STOP = "com.example.verivoxmobileapp.STOP_PLAYBACK_CAPTURE"
        private const val ACTION_END_SESSION = "com.example.verivoxmobileapp.END_PLAYBACK_SESSION"
        private const val EXTRA_PROJECTION = "projection_grant"
        private const val CAPTURE_CHANNEL = "playback_capture"
        private const val RESULT_CHANNEL = "playback_result"
        private const val CAPTURE_ID = 1002
        private const val RESULT_ID = 1003

        private val mutableState = MutableStateFlow(PlaybackCaptureState())
        internal val state = mutableState.asStateFlow()

        internal fun publishMessage(message: String) {
            mutableState.value = mutableState.value.copy(message = message)
        }

        internal fun start(context: Context, grant: Intent) {
            dispatchAnalysis(context) {
                ContextCompat.startForegroundService(context, serviceIntent(context, ACTION_START).putExtra(EXTRA_PROJECTION, grant))
            }
        }

        internal fun analyzeAgain(context: Context) {
            dispatchAnalysis(context) { context.startService(serviceIntent(context, ACTION_ANALYZE)) }
        }

        private fun dispatchAnalysis(context: Context, start: () -> Unit) {
            val previous = mutableState.value
            if (previous.active) return
            mutableState.value = previous.copy(active = true, message = context.getString(R.string.playback_preparing))
            try {
                start()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not request playback analysis", error)
                mutableState.value = previous.copy(message = context.getString(R.string.playback_start_failed))
            }
        }

        internal fun stop(context: Context) = sendAction(context, ACTION_STOP)

        internal fun end(context: Context) = sendAction(context, ACTION_END_SESSION)

        private fun sendAction(context: Context, name: String) {
            try {
                context.startService(serviceIntent(context, name))
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not send playback control action", error)
                publishMessage(context.getString(R.string.playback_stop_failed))
            }
        }

        private fun serviceIntent(context: Context, name: String) =
            Intent(context, PlaybackCaptureService::class.java).setAction(name)

        internal fun notificationsAvailable(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                manager.getNotificationChannel(CAPTURE_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE &&
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
