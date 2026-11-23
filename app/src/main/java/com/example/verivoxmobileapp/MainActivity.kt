package com.example.verivoxmobileapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.verivoxmobileapp.playback.PlaybackCaptureService
import com.example.verivoxmobileapp.ui.theme.VerivoxMobileAppTheme
import com.example.verivoxmobileapp.worker.BatchAudioWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class AnalysisMode {
    CLOUD,
    LOCAL
}

private data class ResultItem(
    val fileName: String,
    val result: DetectionResult?,
    val errorMsg: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VerivoxMobileAppTheme {
                VeriVoxApp()
            }
        }
    }
}

@Composable
private fun VeriVoxApp() {
    val context = LocalContext.current
    var selectedUris by rememberSaveable { mutableStateOf(arrayListOf<Uri>()) }
    var analysisModeName by rememberSaveable { mutableStateOf(AnalysisMode.CLOUD.name) }
    var apiUrlInput by rememberSaveable { mutableStateOf(ApiConfig.ANALYZE_URL) }
    var status by rememberSaveable { mutableStateOf("請先選擇音訊檔") }

    var isProcessing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val workManager = remember(context) { WorkManager.getInstance(context) }
    var batchId by rememberSaveable { mutableStateOf<String?>(null) }
    val batchFlow = remember(batchId, workManager) {
        batchId?.let { workManager.getWorkInfoByIdFlow(UUID.fromString(it)) } ?: flowOf(null)
    }
    val batchInfo by batchFlow.collectAsState(initial = null)
    val batchRunning = batchInfo?.state?.isFinished == false
    var modelJob by remember { mutableStateOf<Job?>(null) }
    var modelProgress by remember { mutableStateOf(-1) }
    var localReadiness by remember(context) { mutableStateOf(LocalInference.readiness(context)) }

    val analysisMode = AnalysisMode.valueOf(analysisModeName)
    val playback by PlaybackCaptureService.state.collectAsState()
    var requestingPlayback by rememberSaveable { mutableStateOf(false) }
    val isBusy = isProcessing || modelJob != null || batchRunning || playback.active || requestingPlayback
    val canAnalyze = selectedUris.isNotEmpty() && (analysisMode == AnalysisMode.CLOUD ||
        (localReadiness.modelInstalled && localReadiness.memorySufficient))

    // 处理进度与结果
    var currentProgress by remember { mutableStateOf(0) }
    var totalProgress by remember { mutableStateOf(0) }
    val resultList = remember { mutableStateListOf<ResultItem>() }

    // 多选选择器（选1个就是单文件，选多个就是批量）
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        selectedUris = ArrayList(uris)
        status = "已選擇 ${uris.size} 個音訊，準備分析"
        resultList.clear()
        currentProgress = 0
        totalProgress = 0
    }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        requestingPlayback = false
        val grant = result.data
        if (result.resultCode != Activity.RESULT_OK || grant == null) {
            PlaybackCaptureService.publishMessage(context.getString(R.string.playback_not_authorized))
        } else {
            PlaybackCaptureService.start(context, grant)
        }
    }
    fun startPlaybackAnalysis() {
        if (!PlaybackCaptureService.notificationsAvailable(context)) {
            requestingPlayback = false
            PlaybackCaptureService.publishMessage(context.getString(R.string.playback_notifications_required))
            return
        }
        if (PlaybackCaptureService.state.value.sessionOpen) {
            requestingPlayback = false
            PlaybackCaptureService.analyzeAgain(context)
            return
        }
        try {
            val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } catch (_: RuntimeException) {
            requestingPlayback = false
            PlaybackCaptureService.publishMessage(context.getString(R.string.playback_start_failed))
        }
    }
    val permissionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { !it } || grants.isEmpty()) {
            requestingPlayback = false
            val message = if (grants[Manifest.permission.RECORD_AUDIO] == false) {
                R.string.playback_audio_permission_required
            } else R.string.playback_notifications_required
            PlaybackCaptureService.publishMessage(context.getString(message))
        } else {
            startPlaybackAnalysis()
        }
    }

    VeriVoxScreen(
        selectedUris = selectedUris,
        analysisMode = analysisMode,
        localReadiness = localReadiness,
        onAnalysisModeChange = {
            if (!isBusy) {
                analysisModeName = it.name
                resultList.clear()
                status = if (selectedUris.isEmpty()) "請先選擇音訊檔" else "音訊已選擇，可以進行分析"
            }
        },
        apiUrlInput = apiUrlInput,
        onApiUrlInputChange = { apiUrlInput = it },
        status = status,
        isBusy = isBusy,
        isProcessing = isProcessing,
        canAnalyze = canAnalyze,
        currentProgress = currentProgress,
        totalProgress = totalProgress,
        resultList = resultList,
        playbackControls = {
            if (playback.active) {
                TextButton(onClick = { PlaybackCaptureService.stop(context) }) { Text(stringResource(R.string.playback_stop)) }
            } else {
                OutlinedButton(
                    enabled = !isBusy && localReadiness.modelInstalled && localReadiness.memorySufficient,
                    onClick = {
                        requestingPlayback = true
                        val required = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        val missing = required.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                        if (missing.isEmpty()) startPlaybackAnalysis() else permissionsLauncher.launch(missing.toTypedArray())
                    }
                ) { Text(stringResource(R.string.playback_start)) }
            }
            if (playback.sessionOpen) {
                if (!playback.active) {
                    Text(stringResource(R.string.playback_session_ready), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { PlaybackCaptureService.end(context) }) { Text(stringResource(R.string.playback_end_session)) }
            }
            playback.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        },
        modelProgress = modelProgress.takeIf { modelJob != null },
        onUpdateModel = {
            if (!isBusy) {
                modelProgress = -1
                status = "正在檢查模型版本…"
                modelJob = scope.launch {
                    try {
                        status = LocalInference.updateModel(context) { modelProgress = it }
                    } catch (error: CancellationException) {
                        status = "模型更新已取消。"
                        throw error
                    } catch (error: Exception) {
                        status = "模型更新失敗：${error.message ?: "未知錯誤"}"
                    } finally {
                        localReadiness = LocalInference.readiness(context)
                        modelJob = null
                    }
                }
            }
        },
        onCancelModelUpdate = { modelJob?.cancel() },
        batchInfo = batchInfo,
        onCancelBatch = { batchId?.let { workManager.cancelWorkById(UUID.fromString(it)) } },
        onAnalyzeInBackground = {
            if (canAnalyze && !isBusy) {
                try {
                    val endpoint = if (analysisMode == AnalysisMode.CLOUD) ApiConfig.normalizeAnalyzeUrl(apiUrlInput) else ""
                    val request = OneTimeWorkRequestBuilder<BatchAudioWorker>()
                        .setInputData(workDataOf(
                            BatchAudioWorker.KEY_URIS to selectedUris.map(Uri::toString).toTypedArray(),
                            BatchAudioWorker.KEY_MODE to analysisMode.name,
                            BatchAudioWorker.KEY_API_URL to endpoint
                        ))
                        .build()
                    for (uri in selectedUris) {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    workManager.enqueue(request)
                    batchId = request.id.toString()
                    status = "已排程背景分析。"
                } catch (error: Exception) {
                    status = "無法開始背景分析：${error.message ?: "未知錯誤"}"
                }
            }
        },
        onSelectAudio = {
            if (!isBusy) audioPicker.launch(arrayOf("audio/*"))
        },
        onAnalyze = {
            if (canAnalyze && !isBusy) {
                scope.launch {
                    isProcessing = true
                    resultList.clear()
                    totalProgress = selectedUris.size
                    currentProgress = 0
                    status = if (analysisMode == AnalysisMode.LOCAL) "正在手機本地分析..." else "正在上傳音訊分析..."

                    try {
                        val endpoint = if (analysisMode == AnalysisMode.CLOUD) ApiConfig.normalizeAnalyzeUrl(apiUrlInput) else ""
                        for ((index, uri) in selectedUris.withIndex()) {
                            val fileName = withContext(Dispatchers.IO) { displayName(context, uri) }
                            try {
                                val result = if (analysisMode == AnalysisMode.LOCAL) {
                                    LocalInference.analyze(context, uri)
                                } else CloudApi.analyze(context, uri, endpoint)
                                resultList.add(ResultItem(fileName, result))
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                val message = when (error) {
                                    is ApiFailure -> error.displayMessage()
                                    is LocalInferenceFailure -> error.displayMessage()
                                    else -> error.message ?: "未知錯誤"
                                }
                                resultList.add(ResultItem(fileName, null, message))
                            }
                            currentProgress = index + 1
                        }
                        status = "全部處理完成"
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        status = "無法完成分析：${error.message ?: "未知錯誤"}"
                    } finally {
                        isProcessing = false
                    }
                }
            }
        }
    )
}

@Composable
private fun VeriVoxScreen(
    selectedUris: List<Uri>,
    analysisMode: AnalysisMode,
    localReadiness: LocalReadiness,
    onAnalysisModeChange: (AnalysisMode) -> Unit,
    apiUrlInput: String,
    onApiUrlInputChange: (String) -> Unit,
    status: String,
    isBusy: Boolean,
    isProcessing: Boolean,
    canAnalyze: Boolean,
    currentProgress: Int,
    totalProgress: Int,
    resultList: List<ResultItem>,
    playbackControls: @Composable () -> Unit,
    modelProgress: Int?,
    onUpdateModel: () -> Unit,
    onCancelModelUpdate: () -> Unit,
    batchInfo: WorkInfo?,
    onCancelBatch: () -> Unit,
    onAnalyzeInBackground: () -> Unit,
    onSelectAudio: () -> Unit,
    onAnalyze: () -> Unit
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 原版头部
            Text(text = "VeriVox", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(text = "Audio Deepfake Detector", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(text = "選擇一或多段音訊，使用雲端或手機本地模型分析它是真人語音還是 AI 合成語音。", style = MaterialTheme.typography.bodyLarge)

            // 模式切换
            Text(text = "分析方式", style = MaterialTheme.typography.labelLarge)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !isBusy,
                    onClick = { onAnalysisModeChange(AnalysisMode.CLOUD) },
                    colors = if (analysisMode == AnalysisMode.CLOUD) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
                ) { Text("雲端／電腦") }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !isBusy,
                    onClick = { onAnalysisModeChange(AnalysisMode.LOCAL) },
                    colors = if (analysisMode == AnalysisMode.LOCAL) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
                ) { Text("手機本地") }
            }

            // 网址/本地信息
            if (analysisMode == AnalysisMode.CLOUD) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = apiUrlInput,
                    onValueChange = onApiUrlInputChange,
                    enabled = !isBusy,
                    singleLine = true,
                    label = { Text("API 網址") },
                    placeholder = { Text("https://xxxx.trycloudflare.com") },
                    supportingText = { Text("可貼上 Cloudflare 根網址，App 會自動加入 /v1/analyze") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "手機本地模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = localReadiness.message)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "動態模型接受至少 1 秒的 WAV。1 至 8 秒整段分析，超過 8 秒只分析開頭 4 秒。", style = MaterialTheme.typography.bodySmall)
                        if (modelProgress == null) {
                            OutlinedButton(enabled = !isBusy && localReadiness.memorySufficient, onClick = onUpdateModel) {
                                Text(if (localReadiness.modelInstalled) "檢查模型更新" else "下載本地模型")
                            }
                        } else {
                            Text(when (modelProgress) {
                                -1 -> "正在檢查版本…"
                                100 -> "正在驗證並載入模型…"
                                else -> "正在下載模型 · $modelProgress%"
                            })
                            if (modelProgress in 0..99) {
                                LinearProgressIndicator(progress = { modelProgress / 100f }, modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            TextButton(onClick = onCancelModelUpdate) { Text("取消更新") }
                        }
                        playbackControls()
                    }
                }
            }

            // 状态卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(text = "目前狀態", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = status, style = MaterialTheme.typography.bodyLarge)

                    // 进度条
                    if (totalProgress > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "進度：$currentProgress / $totalProgress", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { currentProgress.toFloat() / totalProgress },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }
            }

            // 文件列表展示（简化版）
            if (selectedUris.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "已選擇 ${selectedUris.size} 個檔案", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "點擊下方按鈕開始處理", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // 操作按钮
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy,
                onClick = onSelectAudio
            ) {
                Text(text = "選擇音訊檔 (可多選)")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = canAnalyze && !isBusy,
                onClick = onAnalyze
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(text = "開始分析")
                }
            }

            if (selectedUris.size > 1) {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = canAnalyze && !isBusy, onClick = onAnalyzeInBackground) {
                    Text("在背景分析並顯示完成統計")
                }
            }
            if (batchInfo != null) {
                BatchStatus(batchInfo, onCancelBatch)
            }

            // 统一的结果展示列表
            if (resultList.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "分析結果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                resultList.forEach { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = item.fileName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))

                            if (item.result != null) {
                                val res = item.result
                                Text(text = "分類：${res.label}", style = MaterialTheme.typography.bodyLarge)
                                res.probReal?.let { Text(text = "真人機率：${"%.2f".format(it)}") }
                                res.probFake?.let { Text(text = "AI 機率：${"%.2f".format(it)}") }
                                res.modelVersion?.let { Text(text = "模型版本：$it") }
                                res.message?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (res.modelVersion?.endsWith("-local") == true) "本地耗時：${res.roundTripTimeMs} ms" else "雲端耗時：${res.roundTripTimeMs} ms",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                Text(text = "❌ 失敗：${item.errorMsg}", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchStatus(info: WorkInfo, onCancel: () -> Unit) {
    when (info.state) {
        WorkInfo.State.SUCCEEDED -> {
            val success = info.outputData.getInt(BatchAudioWorker.KEY_SUCCESS, 0)
            val failed = info.outputData.getInt(BatchAudioWorker.KEY_FAIL, 0)
            Text("背景分析完成 · 成功 $success 個，失敗 $failed 個")
        }
        WorkInfo.State.CANCELLED -> Text("已取消背景分析")
        WorkInfo.State.FAILED -> Text("背景分析無法完成")
        else -> {
            val completed = info.progress.getInt(BatchAudioWorker.KEY_COMPLETED, 0)
            val total = info.progress.getInt(BatchAudioWorker.KEY_TOTAL, 0)
            if (total == 0) {
                Text("等待背景分析開始…")
            } else {
                Text("背景分析 · $completed / $total")
                LinearProgressIndicator(progress = { completed.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = onCancel) { Text("取消背景分析") }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)
        }
    }
    return uri.lastPathSegment ?: "未命名音訊"
}
