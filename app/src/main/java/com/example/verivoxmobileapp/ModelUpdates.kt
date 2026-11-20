package com.example.verivoxmobileapp

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class InstalledModel(val file: File, val version: String)

internal object ModelUpdates {
    private const val RELEASES = "https://github.com/cyhung0108-debug/VerivoxModel/releases"
    private const val MANIFEST = "model_manifest.json"
    private const val WEIGHTS = "verivox_model.onnx"
    private const val CONTRACT = "verivox-waveform-logits-v1"
    private const val STORAGE_HEADROOM = 128L * 1024 * 1024
    private val releaseTagPattern = Regex("model-v[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}")
    private val versionPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val allowedHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    private val legacyManifest = BundledModelManifest(
        byteCount = 1_263_443_484,
        sha256 = "bbecf5c09abb290997dcff5dfd03993f6dfa46859b86184b81e9c1b35e289d9b",
        version = "xls-r-dynamic-fp32-pool-v2"
    )

    @Synchronized
    fun installed(context: Context): InstalledModel? {
        val folder = directory(context)
        val active = try {
            parseManifest(activeManifest(folder).readFully().decodeToString()).manifest
        } catch (_: Exception) {
            null
        }
        if (active != null) findCached(folder, active)?.let { return it }
        findCached(context.filesDir.resolve("models"), legacyManifest)?.let { return it }
        val external = context.filesDir.resolve(WEIGHTS)
        return if (external.isFile && external.length() > 0) {
            InstalledModel(external, "wav2vec2-xls-r-300m-onnx-local-external")
        } else null
    }

    suspend fun update(
        context: Context,
        onProgress: (Int) -> Unit,
        validate: suspend (File) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val rawManifest = download("$RELEASES/latest/download/$MANIFEST") { input ->
            val bytes = input.buffered().use { stream ->
                val limit = 8192
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > limit) throw IOException("模型版本資訊太大。")
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
            bytes.decodeToString()
        }
        val release = parseManifest(rawManifest)
        val manifest = release.manifest
        val folder = directory(context)
        val active = installed(context)
        val current = findCached(folder, manifest)
            ?: findCached(context.filesDir.resolve("models"), manifest)
        if (active != null && current == active) return@withContext "目前已使用模型 ${manifest.version}。"
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("無法建立模型儲存資料夾。")
        folder.listFiles()?.filter { it.name.startsWith("verivox-model-") && it.name.endsWith(".part") }
            ?.forEach { it.delete() }
        if (folder.usableSpace < manifest.byteCount + STORAGE_HEADROOM && current?.file?.parentFile != folder) {
            throw IOException("空間不足。請預留新模型與目前模型所需的儲存空間。")
        }

        var lastPercent = -1
        val candidate = current?.file?.takeIf { it.parentFile == folder }
            ?: VerifiedModelCache.install(folder, manifest, { bytes ->
                coroutine.ensureActive()
                val percent = (bytes * 100 / manifest.byteCount).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }) {
                if (current != null) current.file.inputStream() else
                    openDownload("$RELEASES/download/${release.tag}/$WEIGHTS")
            }
        try {
            onProgress(100)
            validate(candidate)
            coroutine.ensureActive()
            activate(folder, rawManifest)
        } catch (error: Throwable) {
            if (active?.file != candidate) candidate.delete()
            throw error
        }
        folder.listFiles()?.filter {
            it != candidate && it.name.startsWith("verivox_") && it.name.endsWith(".onnx")
        }?.forEach { it.delete() }
        "已啟用本地模型 ${manifest.version}。"
    }

    private fun directory(context: Context) = context.noBackupFilesDir.resolve("local-models")

    private fun activeManifest(folder: File) = AtomicFile(folder.resolve("active.json"))

    @Synchronized
    private fun activate(folder: File, manifest: String) {
        val pointer = activeManifest(folder)
        val stream = pointer.startWrite()
        try {
            stream.write(manifest.encodeToByteArray())
            pointer.finishWrite(stream)
        } catch (error: Throwable) {
            pointer.failWrite(stream)
            throw error
        }
    }

    private fun findCached(folder: File, manifest: BundledModelManifest): InstalledModel? {
        val file = folder.resolve("verivox_${manifest.sha256}.onnx")
        return if (file.isFile && file.length() == manifest.byteCount) InstalledModel(file, manifest.version) else null
    }

    private data class Release(val tag: String, val manifest: BundledModelManifest)

    private fun parseManifest(text: String): Release = try {
        val json = JSONObject(text)
        val tag = json.getString("release_tag")
        val version = json.getString("model_version")
        val size = json.getLong("bytes")
        val digest = json.getString("sha256")
        require(json.getString("contract") == CONTRACT)
        require(releaseTagPattern.matches(tag) && versionPattern.matches(version))
        require(size in 1 until 2_147_483_648L && digestPattern.matches(digest))
        Release(tag, BundledModelManifest(size, digest, version))
    } catch (error: Exception) {
        throw IOException("模型版本資訊無效，或與目前的 App 不相容。", error)
    }

    private fun <T> download(url: String, read: (InputStream) -> T): T = openDownload(url).use(read)

    private fun openDownload(source: String): InputStream {
        var url = URL(source)
        repeat(5) {
            if (url.protocol != "https" || url.host !in allowedHosts || url.userInfo != null || url.port != -1) {
                throw IOException("模型下載連結指向未核准的地址。")
            }
            val connection = url.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            try {
                when (val status = connection.responseCode) {
                    200 -> {
                        val body = connection.inputStream
                        return object : java.io.FilterInputStream(body) {
                            override fun close() {
                                try { super.close() } finally { connection.disconnect() }
                            }
                        }
                    }
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("找不到模型下載位置。")
                        url = URL(url, location)
                    }
                    else -> throw IOException("模型下載失敗（HTTP $status）。")
                }
            } catch (error: Throwable) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw IOException("模型下載連結重新導向次數過多。")
    }
}
