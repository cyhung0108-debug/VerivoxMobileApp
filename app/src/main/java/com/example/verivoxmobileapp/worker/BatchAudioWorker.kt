package com.example.verivoxmobileapp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.verivoxmobileapp.ApiConfig
import com.example.verivoxmobileapp.CloudApi
import com.example.verivoxmobileapp.LocalInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class BatchAudioWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uris = inputData.getStringArray(KEY_URIS)?.map(Uri::parse) ?: return Result.failure()
        val mode = inputData.getString(KEY_MODE) ?: "CLOUD"
        val total = uris.size
        if (total == 0) return Result.failure()

        val endpoint = if (mode == "CLOUD") {
            try {
                ApiConfig.normalizeAnalyzeUrl(inputData.getString(KEY_API_URL).orEmpty())
            } catch (_: IllegalArgumentException) {
                return Result.failure()
            }
        } else {
            if (mode != "LOCAL") return Result.failure()
            ""
        }
        setForeground(createForegroundInfo(0, total))
        setProgress(workDataOf(KEY_COMPLETED to 0, KEY_TOTAL to total))

        var completed = 0
        var successCount = 0
        var failCount = 0

        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            try {
                if (mode == "LOCAL") {
                    LocalInference.analyze(applicationContext, uri)
                } else {
                    CloudApi.analyze(applicationContext, uri, endpoint)
                }
                successCount++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("VeriVoxBatch", "Could not analyze a batch item: ${error.javaClass.simpleName}")
                failCount++
            }
            completed++

            setProgress(workDataOf(KEY_COMPLETED to completed, KEY_TOTAL to total))
            setForeground(createForegroundInfo(completed, total))
        }

        val resultData = workDataOf(
            KEY_SUCCESS to successCount,
            KEY_FAIL to failCount
        )
        return Result.success(resultData)
    }

    private fun createForegroundInfo(completed: Int, total: Int): ForegroundInfo {
        val channelId = "batch_audio_processing"
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(channelId, "批次音訊分析", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)

        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("正在批次分析音訊")
            .setContentText("已完成 $completed / $total")
            .setOnlyAlertOnce(true)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setProgress(total, completed, false)
            .addAction(android.R.drawable.ic_delete, "取消", cancelIntent)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_URIS = "uris"
        const val KEY_MODE = "mode"
        const val KEY_API_URL = "api_url"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_SUCCESS = "success"
        const val KEY_FAIL = "fail"
        const val NOTIFICATION_ID = 1001
    }
}
