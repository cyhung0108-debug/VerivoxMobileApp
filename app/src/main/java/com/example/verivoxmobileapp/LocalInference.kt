package com.example.verivoxmobileapp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * First on-device inference path.
 *
 * The ONNX file is deliberately not checked into this repository. For a local
 * test, package the converted model as app/src/main/assets/models/verivox_model.onnx
 * together with its model_manifest.json after desktop parity has been verified.
 * An externally installed model at filesDir/verivox_model.onnx remains a fallback
 * for builds that do not bundle an asset.
 */
data class LocalReadiness(
    val modelInstalled: Boolean,
    val memorySufficient: Boolean,
    val totalRamBytes: Long,
    val message: String
)

class LocalInferenceFailure(
    val code: String,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause) {
    fun displayMessage(): String = "本地分析失敗：${message ?: "未知錯誤"}\n錯誤：$code"
}

private const val MAX_LOCAL_AUDIO_BYTES = 100 * 1024 * 1024

object LocalInference {
    const val SAMPLE_RATE = 16_000
    const val CHUNK_SECONDS = 4
    const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_SECONDS
    // Keep short recordings in one dynamic-graph call so their probabilities
    // match the cloud one-shot path. Longer recordings are bounded to 4-second
    // windows to limit peak memory on an 8 GB device.
    const val MAX_DYNAMIC_WHOLE_SAMPLES = SAMPLE_RATE * 8
    // The dynamic export intentionally has a bounded domain. Keeping the
    // lower bound explicit here avoids silently turning a very short clip
    // into a different 4-second input through the fallback path.
    const val MIN_DYNAMIC_SAMPLES = SAMPLE_RATE
    const val MODEL_FILE_NAME = "verivox_model.onnx"

    private const val ASSET_MODEL_PATH = "models/$MODEL_FILE_NAME"
    private const val ASSET_MANIFEST_PATH = "models/model_manifest.json"
    private const val REQUIRED_RAM_BYTES = 2L * 1024L * 1024L * 1024L
    private const val STORAGE_HEADROOM_BYTES = 128L * 1024L * 1024L

    private val sessionLock = Any()
    private val analysisMutex = Mutex()
    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var loadedModelPath: String? = null

    fun readiness(context: Context): LocalReadiness {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val totalRam = memoryInfo.totalMem
        val modelInstalled = modelFile(context).exists() || assetModelExists(context)
        val memorySufficient = totalRam >= REQUIRED_RAM_BYTES &&
            activityManager?.isLowRamDevice != true
        val message = when {
            !modelInstalled ->
                "尚未安裝本地 ONNX 模型。請先完成桌面轉換，再放入 app 專用資料夾。"
            !memorySufficient ->
                "本機模式需要 8 GB RAM 或以上；目前裝置不符合建議門檻。"
            else ->
                "本地模型已就緒（16 kHz WAV；動態模型 1 至 8 秒整段分析，較長音訊按 4 秒切片）。"
        }
        return LocalReadiness(modelInstalled, memorySufficient, totalRam, message)
    }

    suspend fun analyze(context: Context, uri: Uri): DetectionResult =
        analysisMutex.withLock { withContext(Dispatchers.Default) {
            val started = android.os.SystemClock.elapsedRealtime()
            val readiness = readiness(context)
            if (!readiness.memorySufficient) {
                throw LocalInferenceFailure("device_memory", readiness.message)
            }

            val model = try {
                ensureModelFile(context)
            } catch (error: LocalInferenceFailure) {
                throw error
            } catch (error: Exception) {
                throw LocalInferenceFailure(
                    "model_unavailable",
                    "找不到本地 ONNX 模型。請先放入 $MODEL_FILE_NAME。",
                    error
                )
            }

            val audio = try {
                WavAudioReader.read(context, uri)
            } catch (error: LocalInferenceFailure) {
                throw error
            } catch (error: Exception) {
                throw LocalInferenceFailure(
                    "audio_decode_failed",
                    "本地第一版只接受可讀取的 WAV/PCM 音訊；請先轉成 16 kHz WAV。",
                    error
                )
            }
            if (audio.samples.isEmpty()) {
                throw LocalInferenceFailure("empty_audio", "音訊沒有可分析的內容。")
            }

            val onnxSession = getSession(model)
            val inputSpec = inspectInput(onnxSession)
            val resampledCount = resampledSampleCount(audio.samples.size, audio.sampleRate)
            if (inputSpec.fixedSampleCount == null && resampledCount < MIN_DYNAMIC_SAMPLES) {
                throw LocalInferenceFailure(
                    "audio_too_short",
                    "動態本地模型需要至少 1 秒（16 kHz）的音訊；目前只有 " +
                        "${"%.2f".format(resampledCount / SAMPLE_RATE.toDouble())} 秒。"
                )
            }
            var sumFake = 0.0
            var sumReal = 0.0
            var inferenceMs = 0L
            var chunkCount = 0
            var dynamicChunkCount = 0
            var minimumTailPadCount = 0
            var fixedFallbackCount = 0
            for (preparedChunk in chunkAndNormalize(
                samples = audio.samples,
                sampleRate = audio.sampleRate,
                fixedSampleCount = inputSpec.fixedSampleCount
            )) {
                val chunk = preparedChunk.samples
                if (preparedChunk.minimumTailPadded) {
                    minimumTailPadCount++
                }
                val inferenceStarted = android.os.SystemClock.elapsedRealtime()
                val logits = try {
                    runChunk(onnxSession, chunk).also {
                        if (inputSpec.fixedSampleCount == null && chunk.size != CHUNK_SAMPLES) {
                            dynamicChunkCount++
                        }
                    }
                } catch (error: LocalInferenceFailure) {
                    // A graph may advertise a dynamic dimension but still
                    // contain an operator/provider that only accepts the
                    // original 64,000-sample window. Retry this particular
                    // window padded to the training length so the app remains
                    // usable with the fixed model while a dynamic graph is
                    // being prepared. The result message makes this fallback
                    // visible instead of hiding the changed preprocessing.
                    if (inputSpec.fixedSampleCount != null || chunk.size >= CHUNK_SAMPLES) {
                        throw error
                    }
                    fixedFallbackCount++
                    runChunk(
                        onnxSession,
                        normalizeCopy(repeatToLength(chunk, CHUNK_SAMPLES))
                    )
                }
                inferenceMs += android.os.SystemClock.elapsedRealtime() - inferenceStarted
                sumFake += logits[0].toDouble()
                sumReal += logits[1].toDouble()
                chunkCount++
            }

            val averageFake = sumFake / chunkCount
            val averageReal = sumReal / chunkCount
            val maxLogit = max(averageFake, averageReal)
            val denominator = exp(averageFake - maxLogit) + exp(averageReal - maxLogit)
            val probFake = exp(averageFake - maxLogit) / denominator
            val probReal = exp(averageReal - maxLogit) / denominator
            val totalMs = android.os.SystemClock.elapsedRealtime() - started

            DetectionResult(
                label = if (probReal >= 0.50) "genuine" else "fake",
                probReal = probReal,
                probFake = probFake,
                modelVersion = "${bundledManifest(context)?.version ?: "wav2vec2-xls-r-300m-onnx-local-external"}-local",
                message = buildInferenceMessage(
                    chunkCount = chunkCount,
                    inputSpec = inputSpec,
                    dynamicChunkCount = dynamicChunkCount,
                    minimumTailPadCount = minimumTailPadCount,
                    fixedFallbackCount = fixedFallbackCount
                ),
                roundTripTimeMs = totalMs,
                processingTimeMs = totalMs.toDouble(),
                inferenceTimeMs = inferenceMs.toDouble(),
                requestId = null
            )
        } }

    private data class ModelInputSpec(
        /** Null means the audio dimension is dynamic in the ONNX metadata. */
        val fixedSampleCount: Int?
    )

    private fun inspectInput(session: OrtSession): ModelInputSpec {
        val inputName = session.inputNames.firstOrNull()
            ?: throw LocalInferenceFailure("model_input_missing", "ONNX 模型沒有輸入節點。")
        val tensorInfo = session.inputInfo[inputName]?.info as? TensorInfo
            ?: throw LocalInferenceFailure("model_input_invalid", "ONNX 模型輸入不是浮點張量。")
        val shape = tensorInfo.shape
        if (shape.size != 2 || shape[0] > 1L || shape[1] == 0L) {
            throw LocalInferenceFailure(
                "model_input_shape",
                "ONNX 模型輸入形狀不受支援（${shape.contentToString()}）。需要 [1, 音訊樣本數]。"
            )
        }
        val fixedSampleCount = shape[1].takeIf { it > 0L }?.let {
            if (it > Int.MAX_VALUE) {
                throw LocalInferenceFailure("model_input_shape", "ONNX 模型輸入長度過大。")
            }
            it.toInt()
        }
        return ModelInputSpec(fixedSampleCount)
    }

    private data class PreparedChunk(
        val samples: FloatArray,
        val minimumTailPadded: Boolean
    )

    private fun buildInferenceMessage(
        chunkCount: Int,
        inputSpec: ModelInputSpec,
        dynamicChunkCount: Int,
        minimumTailPadCount: Int,
        fixedFallbackCount: Int
    ): String {
        val windowDescription = inputSpec.fixedSampleCount?.let {
            "模型固定輸入 ${it / SAMPLE_RATE.toDouble()} 秒"
        } ?: "模型支援可變長度（${MAX_DYNAMIC_WHOLE_SAMPLES / SAMPLE_RATE} 秒內整段分析）"
        val shortClipDescription = if (inputSpec.fixedSampleCount == null) {
            "短片段不補齊；超過整段上限才按 4 秒切片"
        } else {
            "不足固定輸入長度的最後一段會循環補齊"
        }
        val fallbackDescription = if (fixedFallbackCount > 0) {
            "；其中 ${fixedFallbackCount} 段改用 4 秒循環補齊（模型動態輸入執行失敗後的保底）"
        } else {
            ""
        }
        val minimumTailDescription = if (minimumTailPadCount > 0) {
            "；長音訊不足 1 秒的尾段循環補至 1 秒（${minimumTailPadCount} 段）"
        } else {
            ""
        }
        return "本地分析：${chunkCount} 段；$windowDescription；$shortClipDescription" +
            if (dynamicChunkCount > 0) {
                "（可變長度執行 ${dynamicChunkCount} 段）$minimumTailDescription$fallbackDescription"
            } else {
                "$minimumTailDescription$fallbackDescription"
            }
    }

    private fun getSession(modelFile: java.io.File): OrtSession = synchronized(sessionLock) {
        if (session != null && loadedModelPath == modelFile.absolutePath) {
            return session!!
        }
        // Do not keep two large FP32 models resident during an asset update.
        session?.close()
        session = null
        loadedModelPath = null
        val env = environment ?: OrtEnvironment.getEnvironment().also { environment = it }
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(1)
        val newSession = try {
            env.createSession(modelFile.absolutePath, options)
        } catch (error: Exception) {
            throw LocalInferenceFailure(
                "model_load_failed",
                "本地 ONNX 模型載入失敗。請確認模型是給 ONNX Runtime 使用的檔案。",
                error
            )
        } finally {
            options.close()
        }
        session = newSession
        loadedModelPath = modelFile.absolutePath
        newSession
    }

    private fun runChunk(session: OrtSession, normalizedChunk: FloatArray): FloatArray {
        val env = environment ?: throw LocalInferenceFailure("runtime_unavailable", "ONNX Runtime 尚未初始化。")
        val inputName = session.inputNames.firstOrNull()
            ?: throw LocalInferenceFailure("model_input_missing", "ONNX 模型沒有輸入節點。")
        try {
            val logits = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(normalizedChunk),
                longArrayOf(1L, normalizedChunk.size.toLong())
            ).use { input ->
                session.run(mapOf(inputName to input)).use { outputs ->
                    val raw: Any? = outputs[0].value
                    when (raw) {
                        is Array<*> -> raw.firstOrNull() as? FloatArray
                        is FloatArray -> raw
                        else -> null
                    }
                }
            }
            if (logits == null || logits.size < 2) {
                throw LocalInferenceFailure("model_output_invalid", "ONNX 模型輸出不是兩個分類 logits。")
            }
            return logits
        } catch (error: LocalInferenceFailure) {
            throw error
        } catch (error: Exception) {
            throw LocalInferenceFailure("inference_failed", "本地模型運算失敗。", error)
        }
    }

    private fun chunkAndNormalize(
        samples: FloatArray,
        sampleRate: Int,
        fixedSampleCount: Int?
    ): Sequence<PreparedChunk> = sequence {
        val resampled = if (sampleRate == SAMPLE_RATE) samples else resample(samples, sampleRate, SAMPLE_RATE)
        val windowSize = when {
            fixedSampleCount != null -> fixedSampleCount
            resampled.size <= MAX_DYNAMIC_WHOLE_SAMPLES -> resampled.size
            else -> CHUNK_SAMPLES
        }
        // === 核心修改：强制 count 永远为 1 ===
        // 这意味着如果音频很长，我们不再循环切片，而是只跑第一段（前 4 秒或模型固定长度）
        val count = 1

        repeat(count) { chunkIndex ->
            val start = chunkIndex * windowSize
            // 对于长音频，这里只取前 windowSize 的长度，其他的舍弃
            val remaining = min(windowSize, resampled.size - start)
            // Dynamic ONNX receives the actual length for short clips and the
            // last tail. To keep the dynamic graph in its documented
            // 16,000--128,000 sample domain, only a tail of a longer recording
            // is repeated to 16,000 samples. A whole short recording is
            // rejected earlier instead of being silently padded.
            // Fixed-shape graphs retain the training-time repeat policy because
            // ORT will reject a shorter tensor.
            val minimumTailPadded = fixedSampleCount == null &&
                resampled.size > MAX_DYNAMIC_WHOLE_SAMPLES && remaining < MIN_DYNAMIC_SAMPLES
            val targetSize = fixedSampleCount
                ?: if (minimumTailPadded) MIN_DYNAMIC_SAMPLES else remaining
            val chunk = FloatArray(targetSize)
            if (remaining > 0) {
                System.arraycopy(resampled, start, chunk, 0, remaining)
                if (targetSize > remaining) {
                    for (index in remaining until targetSize) {
                        chunk[index] = resampled[start + ((index - remaining) % remaining)]
                    }
                }
            }
            normalizeInPlace(chunk)
            yield(PreparedChunk(chunk, minimumTailPadded))
        }
    }

    private fun resampledSampleCount(inputSize: Int, fromRate: Int): Int {
        if (inputSize <= 0) return 0
        if (fromRate == SAMPLE_RATE) return inputSize
        return (inputSize.toLong() * SAMPLE_RATE / fromRate)
            .toInt()
            .coerceAtLeast(1)
    }

    private fun repeatToLength(values: FloatArray, targetSize: Int): FloatArray {
        if (values.isEmpty()) return FloatArray(targetSize)
        if (values.size == targetSize) return values.copyOf()
        return FloatArray(targetSize) { index -> values[index % values.size] }
    }

    private fun normalizeCopy(values: FloatArray): FloatArray {
        normalizeInPlace(values)
        return values
    }

    private fun normalizeInPlace(values: FloatArray) {
        var mean = 0.0
        for (value in values) mean += value
        mean /= values.size
        var variance = 0.0
        for (value in values) {
            val delta = value - mean
            variance += delta * delta
        }
        variance /= values.size
        val scale = sqrt(variance + 1e-7)
        for (index in values.indices) {
            values[index] = ((values[index] - mean) / scale).toFloat()
        }
    }

    private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (input.isEmpty()) return input
        val outputSize = (input.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val output = FloatArray(outputSize)
        val ratio = fromRate.toDouble() / toRate.toDouble()
        for (index in output.indices) {
            val position = index * ratio
            val left = position.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = position - left
            output[index] = (input[left] * (1.0 - fraction) + input[right] * fraction).toFloat()
        }
        return output
    }

    private fun modelFile(context: Context): java.io.File =
        context.filesDir.resolve(MODEL_FILE_NAME)

    private fun assetModelExists(context: Context): Boolean = try {
        context.assets.open(ASSET_MODEL_PATH).use { true }
    } catch (_: IOException) {
        false
    }

    private fun bundledManifest(context: Context): BundledModelManifest? {
        if (!assetModelExists(context)) return null
        try {
            val json = context.assets.open(ASSET_MANIFEST_PATH).bufferedReader().use {
                JSONObject(it.readText())
            }
            val manifest = BundledModelManifest(
                byteCount = json.getLong("bytes"),
                sha256 = json.getString("sha256").lowercase(),
                version = json.getString("model_version")
            )
            require(manifest.byteCount > 0 && manifest.sha256.matches(Regex("[0-9a-f]{64}")))
            return manifest
        } catch (error: Exception) {
            throw LocalInferenceFailure(
                "model_manifest_invalid",
                "APK 模型版本資訊缺失或無效。請重新匯出模型並一併加入 model_manifest.json。",
                error
            )
        }
    }

    private fun ensureModelFile(context: Context): java.io.File {
        val manifest = bundledManifest(context)
        if (manifest == null) {
            val external = modelFile(context)
            if (external.isFile && external.length() > 0) return external
            throw LocalInferenceFailure(
                "model_missing",
                "本地 ONNX 模型尚未安裝。請把 $MODEL_FILE_NAME 放入 App 專用資料夾。"
            )
        }
        val cacheDirectory = context.filesDir.resolve("models")
        val expectedModel = cacheDirectory.resolve("verivox_${manifest.sha256}.onnx")
        if (expectedModel.isFile && expectedModel.length() == manifest.byteCount) return expectedModel
        val requiredBytes = manifest.byteCount + STORAGE_HEADROOM_BYTES
        if (StatFs(context.filesDir.absolutePath).availableBytes < requiredBytes) {
            throw LocalInferenceFailure(
                "storage_low",
                "模型首次部署需要至少 ${"%.2f".format(requiredBytes / (1024.0 * 1024 * 1024))} GiB 可用空間（不含 APK）。"
            )
        }
        return try {
            VerifiedModelCache.install(cacheDirectory, manifest) { context.assets.open(ASSET_MODEL_PATH) }
        } catch (error: Exception) {
            throw LocalInferenceFailure(
                "model_install_failed",
                "模型複製或完整性驗證失敗：${error.message}。舊模型沒有被覆蓋。",
                error
            )
        }
    }
}

private data class WavAudio(
    val samples: FloatArray,
    val sampleRate: Int
)

/** Minimal RIFF/WAVE PCM reader for the first on-device vertical slice. */
private object WavAudioReader {
    fun read(context: Context, uri: Uri): WavAudio {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readUpTo(MAX_LOCAL_AUDIO_BYTES)
        } ?: throw LocalInferenceFailure("file_read_failed", "無法讀取選取的音訊檔。")
        if (bytes.size < 44 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") {
            throw LocalInferenceFailure("wav_required", "本地第一版只接受 WAV 音訊。")
        }

        var format: ByteArray? = null
        var dataOffset = -1
        var dataSize = 0
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = ascii(bytes, offset, 4)
            val chunkSize = readUInt32(bytes, offset + 4).toInt()
            val chunkStart = offset + 8
            if (chunkSize < 0 || chunkStart > bytes.size || chunkSize > bytes.size - chunkStart) break
            when (chunkId) {
                "fmt " -> format = bytes.copyOfRange(chunkStart, chunkStart + chunkSize)
                "data" -> {
                    dataOffset = chunkStart
                    dataSize = chunkSize
                }
            }
            offset = chunkStart + chunkSize + (chunkSize and 1)
        }
        val fmt = format ?: throw LocalInferenceFailure("wav_format_missing", "WAV 缺少 fmt 區塊。")
        if (dataOffset < 0 || dataSize == 0 || fmt.size < 16) {
            throw LocalInferenceFailure("wav_data_missing", "WAV 缺少有效音訊資料。")
        }
        val audioFormat = readUInt16(fmt, 0)
        val channels = readUInt16(fmt, 2)
        val sampleRate = readUInt32(fmt, 4).toInt()
        val bitsPerSample = readUInt16(fmt, 14)
        if (channels <= 0 || sampleRate <= 0 || audioFormat !in setOf(1, 3)) {
            throw LocalInferenceFailure("wav_unsupported", "目前只支援 PCM/Float WAV 音訊。")
        }
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample <= 0 || channels * bytesPerSample <= 0) {
            throw LocalInferenceFailure("wav_bit_depth", "WAV 位元深度不受支援。")
        }
        val frameSize = channels * bytesPerSample
        val frameCount = dataSize / frameSize
        val samples = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            var mixed = 0.0
            for (channel in 0 until channels) {
                val sampleOffset = dataOffset + frame * frameSize + channel * bytesPerSample
                mixed += decodeSample(bytes, sampleOffset, audioFormat, bitsPerSample)
            }
            samples[frame] = (mixed / channels).toFloat()
        }
        return WavAudio(samples, sampleRate)
    }

    private fun decodeSample(bytes: ByteArray, offset: Int, format: Int, bits: Int): Double {
        return when {
            format == 1 && bits == 8 -> ((bytes[offset].toInt() and 0xff) - 128) / 128.0
            format == 1 && bits == 16 -> readInt16(bytes, offset) / 32768.0
            format == 1 && bits == 24 -> {
                var value = (bytes[offset].toInt() and 0xff) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xff) shl 16)
                if ((value and 0x800000) != 0) value = value or -0x1000000
                value / 8388608.0
            }
            format == 1 && bits == 32 -> readInt32(bytes, offset) / 2147483648.0
            format == 3 && bits == 32 -> Float.fromBits(readInt32(bytes, offset)).toDouble()
            else -> throw LocalInferenceFailure("wav_sample_format", "WAV sample format 尚未支援（${bits} bit）。")
        }
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, StandardCharsets.US_ASCII)

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readInt16(bytes: ByteArray, offset: Int): Int =
        readUInt16(bytes, offset).let { if (it and 0x8000 != 0) it - 0x10000 else it }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        readUInt32(bytes, offset).toInt()

    private fun java.io.InputStream.readUpTo(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) {
                throw LocalInferenceFailure(
                    "audio_too_large",
                    "本地音訊檔太大（上限 ${limit / (1024 * 1024)} MB），請先剪短或轉檔。"
                )
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
