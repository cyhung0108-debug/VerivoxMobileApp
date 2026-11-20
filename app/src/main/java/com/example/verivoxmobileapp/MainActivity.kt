package com.example.verivoxmobileapp

import android.content.Context
import android.net.Uri
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.verivoxmobileapp.ui.theme.VerivoxMobileAppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AnalysisMode {
    CLOUD,
    LOCAL
}

data class ResultItem(
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
    // 统一管理选中的文件列表
    var selectedUris by rememberSaveable { mutableStateOf<List<Uri>>(emptyList()) }
    var analysisModeName by rememberSaveable { mutableStateOf(AnalysisMode.CLOUD.name) }
    var apiUrlInput by rememberSaveable { mutableStateOf(ApiConfig.ANALYZE_URL) }
    var status by rememberSaveable { mutableStateOf("請先選擇音訊檔") }

    var isProcessing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val analysisMode = AnalysisMode.valueOf(analysisModeName)
    val localReadiness = remember(context) { LocalInference.readiness(context) }

    // 处理进度与结果
    var currentProgress by remember { mutableStateOf(0) }
    var totalProgress by remember { mutableStateOf(0) }
    val resultList = remember { mutableStateListOf<ResultItem>() }

    // 多选选择器（选1个就是单文件，选多个就是批量）
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        selectedUris = uris
        status = "已選擇 ${uris.size} 個音訊，準備分析"
        resultList.clear()
        currentProgress = 0
        totalProgress = 0
    }

    VeriVoxScreen(
        selectedUris = selectedUris,
        analysisMode = analysisMode,
        localReadiness = localReadiness,
        onAnalysisModeChange = {
            if (!isProcessing) {
                analysisModeName = it.name
                resultList.clear()
                status = if (it == AnalysisMode.LOCAL) localReadiness.message else "音訊已選擇，可以進行分析"
            }
        },
        apiUrlInput = apiUrlInput,
        onApiUrlInputChange = { apiUrlInput = it },
        status = status,
        isProcessing = isProcessing,
        currentProgress = currentProgress,
        totalProgress = totalProgress,
        resultList = resultList,
        onSelectAudio = {
            if (!isProcessing) audioPicker.launch(arrayOf("audio/*"))
        },
        onAnalyze = {
            if (selectedUris.isNotEmpty() && !isProcessing) {
                scope.launch {
                    isProcessing = true
                    resultList.clear()
                    totalProgress = selectedUris.size
                    currentProgress = 0
                    status = if (analysisMode == AnalysisMode.LOCAL) "正在手機本地分析..." else "正在上傳音訊分析..."

                    withContext(Dispatchers.IO) {
                        for ((index, uri) in selectedUris.withIndex()) {
                            val fileName = displayName(context, uri)
                            try {
                                val res = if (analysisMode == AnalysisMode.LOCAL) {
                                    LocalInference.analyze(context, uri)
                                } else {
                                    val endpoint = ApiConfig.normalizeAnalyzeUrl(apiUrlInput)
                                    CloudApi.analyze(context, uri, endpoint)
                                }
                                withContext(Dispatchers.Main) {
                                    resultList.add(ResultItem(fileName, res, null))
                                }
                            } catch (e: Throwable) {
                                e.printStackTrace()
                                withContext(Dispatchers.Main) {
                                    resultList.add(ResultItem(fileName, null, e.message ?: "未知錯誤"))
                                }
                            }
                            withContext(Dispatchers.Main) {
                                currentProgress = index + 1
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        isProcessing = false
                        status = "全部處理完成"
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
    isProcessing: Boolean,
    currentProgress: Int,
    totalProgress: Int,
    resultList: List<ResultItem>,
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
                    enabled = !isProcessing,
                    onClick = { onAnalysisModeChange(AnalysisMode.CLOUD) },
                    colors = if (analysisMode == AnalysisMode.CLOUD) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
                ) { Text("雲端／電腦") }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing,
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
                    enabled = !isProcessing,
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
                        Text(text = "目前接受 WAV；音訊會轉成 16 kHz 單聲道。短於 4 秒按原長度分析，較長音訊僅取其中 4 秒進行分析。", style = MaterialTheme.typography.bodySmall)
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
                enabled = !isProcessing,
                onClick = onSelectAudio
            ) {
                Text(text = "選擇音訊檔 (可多選)")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedUris.isNotEmpty() && !isProcessing,
                onClick = onAnalyze
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(text = "開始分析")
                }
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