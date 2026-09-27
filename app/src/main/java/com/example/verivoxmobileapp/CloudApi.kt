package com.example.verivoxmobileapp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class DetectionResult(
    val label: String,
    val probReal: Double?,
    val probFake: Double?,
    val modelVersion: String?,
    val message: String?
)

/** Small HTTP client for the prototype cloud endpoint. */
object CloudApi {
    private const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024

    suspend fun analyze(context: Context, uri: Uri): DetectionResult =
        withContext(Dispatchers.IO) {
            val fileName = displayName(context, uri)
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val audioBytes = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: throw IOException("無法讀取所選音訊檔")

            if (audioBytes.size > MAX_UPLOAD_BYTES) {
                throw IOException("音訊檔太大，暫時限制為 10 MB")
            }

            val boundary = "----VeriVoxBoundary${System.currentTimeMillis()}"
            val requestBody = buildMultipartBody(
                boundary = boundary,
                fileName = fileName,
                mimeType = mimeType,
                audioBytes = audioBytes
            )

            val connection = (URL(ApiConfig.ANALYZE_URL).openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.doInput = true
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 120_000
                connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=$boundary"
                )
                connection.setFixedLengthStreamingMode(requestBody.size)

                connection.outputStream.use { output ->
                    output.write(requestBody)
                }

                val responseCode = connection.responseCode
                val responseStream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val responseText = responseStream?.bufferedReader()?.use { it.readText() }
                    ?: ""

                if (responseCode !in 200..299) {
                    throw IOException("伺服器回傳 HTTP $responseCode：$responseText")
                }

                parseResult(responseText)
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

    private fun parseResult(responseText: String): DetectionResult {
        val json = JSONObject(responseText)
        fun optionalNumber(name: String): Double? {
            if (!json.has(name) || json.isNull(name)) return null
            val value = json.optDouble(name, Double.NaN)
            return value.takeUnless { it.isNaN() }
        }

        return DetectionResult(
            label = json.optString("label", "unknown"),
            probReal = optionalNumber("prob_real"),
            probFake = optionalNumber("prob_fake"),
            modelVersion = optionalString(json, "model_version"),
            message = optionalString(json, "message")
        )
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
