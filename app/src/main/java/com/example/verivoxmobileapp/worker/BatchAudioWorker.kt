package com.example.verivoxmobileapp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.verivoxmobileapp.ApiConfig
import com.example.verivoxmobileapp.CloudApi
import com.example.verivoxmobileapp.LocalInference
import kotlinx.coroutines.delay

class BatchAudioWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uris = inputData.getStringArray(KEY_URIS)?.map(Uri::parse) ?: return Result.failure()
        val mode = inputData.getString(KEY_MODE) ?: "CLOUD"
        val apiUrl = inputData.getString(KEY_API_URL) ?: ""
        val total = uris.size
        if (total == 0) return Result.failure()

        try {
            setForeground(createForegroundInfo(0, total))
        } catch (e: Exception) {
            // 忽略通知权限不足导致的崩溃
            e.printStackTrace()
        }

        var completed = 0
        var successCount = 0
        var failCount = 0

        for (uri in uris) {
            try {
                if (mode == "LOCAL") {
                    LocalInference.analyze(applicationContext, uri)
                } else {
                    val endpoint = ApiConfig.normalizeAnalyzeUrl(apiUrl)
                    CloudApi.analyze(applicationContext, uri, endpoint)
                }
                successCount++
            } catch (e: Throwable) { // 捕获所有错误，包括 OOM 内存溢出
                e.printStackTrace()
                failCount++
            }
            completed++

            setProgress(workDataOf(KEY_COMPLETED to completed, KEY_TOTAL to total))
            try {
                setForeground(createForegroundInfo(completed, total))
            } catch (e: Exception) {
                e.printStackTrace()
            }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "批量音频处理",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("正在批量处理音频")
            .setContentText("已完成 $completed / $total")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setProgress(total, completed, false)
            .addAction(android.R.drawable.ic_delete, "取消", cancelIntent)
            .build()

        // 关键修复：针对 Android 10 (API 29) 及以上，必须指定前台服务类型
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
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