package com.example.verivoxmobileapp

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.verivoxmobileapp.ui.theme.VerivoxMobileAppTheme
import kotlinx.coroutines.launch

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
    var selectedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf("請先選擇一個音訊檔") }
    var isAnalyzing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DetectionResult?>(null) }
    val scope = rememberCoroutineScope()

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        selectedUri = uri
        selectedName = displayName(context, uri)
        status = "音訊已選擇，可以進行分析"
        result = null
    }

    VeriVoxScreen(
        selectedName = selectedName,
        status = status,
        isAnalyzing = isAnalyzing,
        result = result,
        onSelectAudio = {
            if (!isAnalyzing) {
                audioPicker.launch(arrayOf("audio/*"))
            }
        },
        onAnalyze = {
            val uri = selectedUri
            if (uri != null && !isAnalyzing) {
                scope.launch {
                    isAnalyzing = true
                    result = null
                    status = "正在上傳音訊並等待分析..."
                    try {
                        result = CloudApi.analyze(context, uri)
                        status = "分析完成"
                    } catch (error: Exception) {
                        status = "分析失敗：${error.message ?: "無法連接伺服器"}"
                    } finally {
                        isAnalyzing = false
                    }
                }
            }
        }
    )
}

@Composable
private fun VeriVoxScreen(
    selectedName: String?,
    status: String,
    isAnalyzing: Boolean,
    result: DetectionResult?,
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
            Text(
                text = "VeriVox",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Audio Deepfake Detector",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "選擇一段音訊，稍後由雲端模型分析它是真人語音還是 AI 合成語音。",
                style = MaterialTheme.typography.bodyLarge
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "目前狀態",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = status, style = MaterialTheme.typography.bodyLarge)
                }
            }

            if (selectedName != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "已選擇音訊",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = selectedName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = when {
                                isAnalyzing -> "分析中"
                                result != null -> "已完成"
                                else -> "待分析"
                            },
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isAnalyzing,
                onClick = onSelectAudio
            ) {
                Text(text = "選擇音訊檔")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedName != null && !isAnalyzing,
                onClick = onAnalyze
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(text = "開始分析")
                }
            }

            if (result != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "分析結果",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "分類：${result.label}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        result.probReal?.let {
                            Text(text = "真人機率：${"%.2f".format(it)}")
                        }
                        result.probFake?.let {
                            Text(text = "AI 機率：${"%.2f".format(it)}")
                        }
                        result.modelVersion?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "模型：$it",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        result.message?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = it, style = MaterialTheme.typography.bodySmall)
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
            if (index >= 0) {
                return cursor.getString(index)
            }
        }
    }
    return uri.lastPathSegment ?: "未命名音訊"
}

@Preview(showBackground = true)
@Composable
private fun VeriVoxScreenPreview() {
    VerivoxMobileAppTheme {
        Surface {
            VeriVoxScreen(
                selectedName = "sample.wav",
                status = "音訊已選擇，可以進行分析",
                isAnalyzing = false,
                result = null,
                onSelectAudio = {},
                onAnalyze = {}
            )
        }
    }
}
