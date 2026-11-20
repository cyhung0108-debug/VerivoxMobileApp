package com.example.verivoxmobileapp

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class BundledModelManifest(
    val byteCount: Long,
    val sha256: String,
    val version: String
)

/** A new asset fingerprint must never silently reuse a previous APK's model. */
internal object VerifiedModelCache {
    fun install(
        directory: File,
        manifest: BundledModelManifest,
        onBytesCopied: (Long) -> Unit = {},
        openSource: () -> InputStream
    ): File {
        require(manifest.byteCount > 0 && manifest.sha256.matches(Regex("[0-9a-f]{64}")))
        val destination = File(directory, "verivox_${manifest.sha256}.onnx")
        if (destination.isFile && destination.length() == manifest.byteCount) return destination
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create model cache directory")
        }

        // Only expose the final filename after a complete, verified copy.
        // A failed installation leaves previous versions untouched.
        val temporary = File.createTempFile("verivox-model-", ".part", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            openSource().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count
                        if (copied > manifest.byteCount) throw IOException("Model asset exceeds expected size")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onBytesCopied(copied)
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (copied != manifest.byteCount || actualHash != manifest.sha256) {
                throw IOException("Model asset SHA-256 or size verification failed")
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            return destination
        } finally {
            // This is exclusively the temporary file created by this call.
            if (temporary.exists()) temporary.delete()
        }
    }
}
