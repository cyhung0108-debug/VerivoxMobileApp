package com.example.verivoxmobileapp

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class DetectionResult(
    val label: String,
    val probReal: Double?,
    val probFake: Double?,
    val modelVersion: String?,
    val message: String?,
    val roundTripTimeMs: Long,
    val processingTimeMs: Double?,
    val inferenceTimeMs: Double?,
    val requestId: String?
)

/** Small HTTP client for the prototype cloud endpoint. */
object CloudApi {
    private const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    suspend fun analyze(
        context: Context,
        uri: Uri,
        endpoint: String = ApiConfig.ANALYZE_URL
    ): DetectionResult =
        withContext(Dispatchers.IO) {
            val endpointUrl = URL(ApiConfig.normalizeAnalyzeUrl(endpoint))
            val fileName: String
            val mimeType: String
            val audioBytes: ByteArray
            try {
                fileName = displayName(context, uri)
                mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                audioBytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readUpTo(MAX_UPLOAD_BYTES + 1)
                } ?: throw IOException("No input stream")
            } catch (error: Exception) {
                throw ApiFailure("file_read_failed", "無法讀取音訊。檔案可能已移動或讀取權限已失效，請重新選擇。",
                    "讀取手機檔案", cause = error)
            }

            if (audioBytes.size > MAX_UPLOAD_BYTES) {
                throw ApiFailure("file_too_large", "音訊檔太大，請使用不超過 10 MB 的檔案。", "讀取手機檔案")
            }
            if (audioBytes.isEmpty()) {
                throw ApiFailure("empty_file", "所選音訊是空檔案，請重新選擇。", "讀取手機檔案")
            }

            val boundary = "----VeriVoxBoundary${System.currentTimeMillis()}"
            val requestBody = buildMultipartBody(
                boundary = boundary,
                fileName = fileName,
                mimeType = mimeType,
                audioBytes = audioBytes
            )

            val connection = (endpointUrl.openConnection() as HttpURLConnection)
            var phase = "連線、上傳或等待伺服器回應"
            // The round-trip clock starts just before the request body is sent.
            val started = SystemClock.elapsedRealtime()
            try {
                connection.requestMethod = "POST"
                connection.doInput = true
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=$boundary"
                )
                connection.setFixedLengthStreamingMode(requestBody.size)

                coroutineContext.ensureActive()
                // Start immediately before network I/O, excluding local file reading/body preparation.
                // Includes DNS/TLS/connect, upload, server work and the full response download.
                connection.outputStream.use { output ->
                    output.write(requestBody)
                }

                val responseCode = connection.responseCode
                phase = if (responseCode in 200..299) "接收分析結果" else "接收錯誤回應"
                val responseStream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val responseBytes = responseStream?.use { it.readUpTo(MAX_RESPONSE_BYTES + 1) }
                    ?: byteArrayOf()
                val elapsedMs = SystemClock.elapsedRealtime() - started
                val responseText = responseBytes.toString(Charsets.UTF_8)

                if (responseCode !in 200..299) {
                    throw httpFailure(responseCode, responseText, elapsedMs)
                }
                if (responseBytes.size > MAX_RESPONSE_BYTES) {
                    throw ApiFailure("invalid_response", "回應過大，並非預期分析結果，請檢查 API 網址。",
                        phase, elapsedMs, responseCode)
                }
                parseResult(responseText, elapsedMs, responseCode)
            } catch (error: ApiFailure) {
                throw error
            } catch (error: IOException) {
                throw networkFailure(error, phase, SystemClock.elapsedRealtime() - started)
            } finally {
                connection.disconnect()
            }
        }

    private fun buildMultipartBody(
        boundary: String,
        fileName: String,
        mimeType: String,
        audioBytes: ByteArray
    ): ByteArray {
        val safeFileName = fileName
            .replace("\\", "_")
            .replace("\"", "_")
            .replace("\r", "_")
            .replace("\n", "_")

        val output = ByteArrayOutputStream()
        fun writeText(value: String) {
            output.write(value.toByteArray(Charsets.UTF_8))
        }

        writeText("--$boundary\r\n")
        writeText(
            "Content-Disposition: form-data; name=\"file\"; " +
                "filename=\"$safeFileName\"\r\n"
        )
        writeText("Content-Type: $mimeType\r\n\r\n")
        output.write(audioBytes)
        writeText("\r\n--$boundary--\r\n")
        return output.toByteArray()
    }

    private fun parseResult(responseText: String, elapsedMs: Long, status: Int): DetectionResult {
        val json = try {
            JSONObject(responseText)
        } catch (_: JSONException) {
            throw ApiFailure("invalid_response", "伺服器未回傳有效 JSON 分析結果。可能是錯誤頁面，請檢查 API 網址。",
                "讀取分析結果", elapsedMs, status)
        }
        if (json.has("error") || json.optString("label").isBlank()) {
            throw httpFailure(status, responseText, elapsedMs)
        }
        fun optionalNumber(name: String): Double? {
            if (!json.has(name) || json.isNull(name)) return null
            val value = json.optDouble(name, Double.NaN)
            return value.takeIf { it.isFinite() && it >= 0 }
        }

        return DetectionResult(
            label = json.optString("label", "unknown"),
            probReal = optionalNumber("prob_real"),
            probFake = optionalNumber("prob_fake"),
            modelVersion = optionalString(json, "model_version"),
            message = optionalString(json, "message"),
            roundTripTimeMs = elapsedMs,
            processingTimeMs = optionalNumber("processing_time_ms"),
            inferenceTimeMs = optionalNumber("inference_time_ms"),
            requestId = optionalString(json, "request_id")
        )
    }

    private fun httpFailure(status: Int, body: String, elapsedMs: Long): ApiFailure {
        val json = try { JSONObject(body) } catch (_: JSONException) { null }
        val code = json?.let { optionalString(it, "error") }?.take(80) ?: "invalid_response"
        // Prefer the API's concise explanation, never dump a Cloudflare HTML page or raw traceback.
        val message = json?.let { optionalString(it, "message") }?.take(400)
            ?: json?.let { optionalString(it, "detail") }?.take(400)
            ?: if (status in 200..299) "回應缺少分析結果欄位，請確認 API 和 App 版本一致。"
            else httpFailureHint(status)
        return ApiFailure(code, message, "伺服器回應", elapsedMs, status,
            json?.let { optionalString(it, "request_id") }?.take(80))
    }

    private fun InputStream.readUpTo(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count == -1) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun optionalString(json: JSONObject, name: String): String? {
        if (!json.has(name) || json.isNull(name)) return null
        return json.optString(name).takeUnless { it.isBlank() }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "audio"
    }
}
