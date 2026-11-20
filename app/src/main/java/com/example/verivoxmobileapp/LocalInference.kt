package com.example.verivoxmobileapp

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
    // match the cloud one-shot path. The October 30 release uses only the
    // first 4 seconds of recordings beyond this limit.
    const val MAX_DYNAMIC_WHOLE_SAMPLES = SAMPLE_RATE * 8
    // The dynamic export intentionally has a bounded domain. Keeping the
    // lower bound explicit here avoids silently turning a very short clip
    // into a different 4-second input through the fallback path.
    const val MIN_DYNAMIC_SAMPLES = SAMPLE_RATE
    private const val REQUIRED_RAM_BYTES = 2L * 1024L * 1024L * 1024L

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
        val model = ModelUpdates.installed(context)
        val memorySufficient = totalRam >= REQUIRED_RAM_BYTES &&
            activityManager?.isLowRamDevice != true
        val message = when {
            !memorySufficient ->
                "本機模式要求系統回報至少 2 GiB RAM，且裝置不能處於低 RAM 分類。建議用 8 GB 或以上真機測試。"
            model == null -> "尚未安裝本地模型。首次下載約 1.26 GB，安裝後可離線分析。"
            else ->
                "本地模型已就緒 · ${model.version}"
        }
        return LocalReadiness(model != null, memorySufficient, totalRam, message)
    }

    suspend fun updateModel(context: Context, onProgress: (Int) -> Unit): String =
        analysisMutex.withLock {
            val readiness = readiness(context)
            if (!readiness.memorySufficient) throw LocalInferenceFailure("device_memory", readiness.message)
            ModelUpdates.update(context, onProgress) { candidate ->
                val candidateSession = getSession(candidate)
                try {
                    val specification = inspectInput(candidateSession)
                    val outputNames = candidateSession.outputNames
                    val output = outputNames.singleOrNull()?.let { candidateSession.outputInfo[it]?.info } as? TensorInfo
                    val outputShape = output?.shape
                    if (output?.type != OnnxJavaType.FLOAT || outputShape?.size != 2 ||
                        outputShape[0] !in setOf(-1L, 1L) || outputShape[1] != 2L ||
                        specification.fixedSampleCount !in setOf(null, CHUNK_SAMPLES)
                    ) {
                        throw LocalInferenceFailure("model_contract", "模型不符合目前的音訊輸入與輸出合約。")
                    }
                    val inputLength = specification.fixedSampleCount ?: MIN_DYNAMIC_SAMPLES
                    runChunk(candidateSession, FloatArray(inputLength))
                } catch (error: Throwable) {
                    candidateSession.close()
                    session = null
                    loadedModelPath = null
                    throw error
                }
            }
        }

    suspend fun analyze(context: Context, uri: Uri): DetectionResult =
        analysisMutex.withLock { withContext(Dispatchers.Default) {
            val started = android.os.SystemClock.elapsedRealtime()
            val readiness = readiness(context)
            if (!readiness.memorySufficient) {
                throw LocalInferenceFailure("device_memory", readiness.message)
            }

            val model = ModelUpdates.installed(context)
                ?: throw LocalInferenceFailure("model_missing", "請先安裝本地模型。")

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

            val onnxSession = getSession(model.file)
            val inputSpec = inspectInput(onnxSession)
            val resampledCount = resampledSampleCount(audio.samples.size, audio.sampleRate)
            if (inputSpec.fixedSampleCount == null && resampledCount < MIN_DYNAMIC_SAMPLES) {
                throw LocalInferenceFailure(
                    "audio_too_short",
                    "動態本地模型需要至少 1 秒（16 kHz）的音訊；目前只有 " +
                        "${"%.2f".format(resampledCount / SAMPLE_RATE.toDouble())} 秒。"
                )
            }
            val resampled = if (audio.sampleRate == SAMPLE_RATE) audio.samples else
                resample(audio.samples, audio.sampleRate, SAMPLE_RATE)
            val windowSize = inputSpec.fixedSampleCount
                ?: if (resampled.size > MAX_DYNAMIC_WHOLE_SAMPLES) CHUNK_SAMPLES else resampled.size
            val usedSamples = min(windowSize, resampled.size)
            val window = normalizeCopy(repeatToLength(resampled.copyOf(usedSamples), windowSize))
            var usedFallback = false
            val inferenceStarted = android.os.SystemClock.elapsedRealtime()
            val logits = try {
                runChunk(onnxSession, window)
            } catch (error: LocalInferenceFailure) {
                if (inputSpec.fixedSampleCount != null || window.size >= CHUNK_SAMPLES) throw error
                usedFallback = true
                runChunk(onnxSession, normalizeCopy(repeatToLength(window, CHUNK_SAMPLES)))
            }
            val inferenceMs = android.os.SystemClock.elapsedRealtime() - inferenceStarted
            val fakeLogit = logits[0].toDouble()
            val realLogit = logits[1].toDouble()
            val maxLogit = max(fakeLogit, realLogit)
            val denominator = exp(fakeLogit - maxLogit) + exp(realLogit - maxLogit)
            val probFake = exp(fakeLogit - maxLogit) / denominator
            val probReal = exp(realLogit - maxLogit) / denominator
            val totalMs = android.os.SystemClock.elapsedRealtime() - started

            DetectionResult(
                label = if (probReal >= 0.50) "genuine" else "fake",
                probReal = probReal,
                probFake = probFake,
                modelVersion = "${model.version}-local",
                message = when {
                    usedSamples < resampled.size -> "只分析開頭 ${usedSamples / SAMPLE_RATE.toDouble()} 秒。"
                    usedFallback -> "動態輸入失敗，改用 4 秒循環補齊分析。"
                    windowSize > usedSamples -> "已循環補齊至模型固定輸入長度。"
                    else -> "已分析整段音訊。"
                },
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
        val inputName = session.inputNames.singleOrNull()
            ?: throw LocalInferenceFailure("model_input_invalid", "ONNX 模型必須只有一個輸入節點。")
        val tensorInfo = session.inputInfo[inputName]?.info as? TensorInfo
            ?: throw LocalInferenceFailure("model_input_invalid", "ONNX 模型輸入不是浮點張量。")
        val shape = tensorInfo.shape
        if (tensorInfo.type != OnnxJavaType.FLOAT || shape.size != 2 || shape[0] !in setOf(-1L, 1L) || shape[1] == 0L) {
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
            if (logits == null || logits.size != 2 || logits.any { !it.isFinite() }) {
                throw LocalInferenceFailure("model_output_invalid", "ONNX 模型輸出不是兩個分類 logits。")
            }
            return logits
        } catch (error: LocalInferenceFailure) {
            throw error
        } catch (error: Exception) {
            throw LocalInferenceFailure("inference_failed", "本地模型運算失敗。", error)
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
