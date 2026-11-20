package com.example.verivoxmobileapp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class VerifiedModelCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sameFingerprintReusesCompleteCacheWithoutOpeningSource() {
        val bytes = "model-a".toByteArray()
        val manifest = manifestFor(bytes, "a")
        var sourceOpens = 0

        val first = VerifiedModelCache.install(temporaryFolder.root, manifest) {
            sourceOpens++
            ByteArrayInputStream(bytes)
        }
        val second = VerifiedModelCache.install(temporaryFolder.root, manifest) {
            sourceOpens++
            throw AssertionError("cached model should be reused")
        }

        assertEquals(first, second)
        assertEquals(1, sourceOpens)
        assertArrayEquals(bytes, first.readBytes())
    }

    @Test
    fun sameSizeDifferentFingerprintGetsASeparateCacheFile() {
        val firstBytes = "model-a".toByteArray()
        val secondBytes = "model-b".toByteArray()
        assertEquals(firstBytes.size, secondBytes.size)

        val first = VerifiedModelCache.install(
            temporaryFolder.root,
            manifestFor(firstBytes, "a")
        ) { ByteArrayInputStream(firstBytes) }
        val second = VerifiedModelCache.install(
            temporaryFolder.root,
            manifestFor(secondBytes, "b")
        ) { ByteArrayInputStream(secondBytes) }

        assertNotEquals(first, second)
        assertArrayEquals(firstBytes, first.readBytes())
        assertArrayEquals(secondBytes, second.readBytes())
    }

    @Test
    fun interruptedSourceDoesNotPublishPartialModel() {
        val bytes = "model-that-will-be-interrupted".toByteArray()
        val manifest = manifestFor(bytes, "interrupted")

        assertThrows(IOException::class.java) {
            VerifiedModelCache.install(temporaryFolder.root, manifest) {
                object : InputStream() {
                    private val source = ByteArrayInputStream(bytes)
                    private var copied = 0

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (copied >= 8) throw IOException("simulated source interruption")
                        val count = source.read(buffer, offset, minOf(length, 8 - copied))
                        if (count > 0) copied += count
                        return count
                    }

                    override fun read(): Int = throw IOException("single-byte read is unsupported")
                }
            }
        }

        assertFalse(FileAssertions.hasModelFile(temporaryFolder.root, manifest))
        assertTrue(temporaryFolder.root.listFiles()?.none { it.name.endsWith(".part") } == true)
    }

    @Test
    fun hashOrSizeMismatchDoesNotPublishModel() {
        val bytes = "actual-model".toByteArray()
        val wrongHash = "0".repeat(64)
        val manifest = BundledModelManifest(
            byteCount = bytes.size.toLong(),
            sha256 = wrongHash,
            version = "wrong-hash"
        )

        assertThrows(IOException::class.java) {
            VerifiedModelCache.install(temporaryFolder.root, manifest) {
                ByteArrayInputStream(bytes)
            }
        }

        assertFalse(FileAssertions.hasModelFile(temporaryFolder.root, manifest))
        assertTrue(temporaryFolder.root.listFiles()?.none { it.name.endsWith(".part") } == true)
    }

    @Test
    fun sizeMismatchDoesNotPublishModel() {
        val bytes = "actual-model".toByteArray()
        val manifest = BundledModelManifest(
            byteCount = bytes.size.toLong() + 1,
            sha256 = sha256(bytes),
            version = "wrong-size"
        )

        assertThrows(IOException::class.java) {
            VerifiedModelCache.install(temporaryFolder.root, manifest) {
                ByteArrayInputStream(bytes)
            }
        }

        assertFalse(FileAssertions.hasModelFile(temporaryFolder.root, manifest))
        assertTrue(temporaryFolder.root.listFiles()?.none { it.name.endsWith(".part") } == true)
    }

    @Test
    fun truncatedCacheIsReinstalledFromSource() {
        val bytes = "complete-model".toByteArray()
        val manifest = manifestFor(bytes, "complete")
        val first = VerifiedModelCache.install(temporaryFolder.root, manifest) {
            ByteArrayInputStream(bytes)
        }
        first.writeBytes(bytes.copyOf(bytes.size - 2))

        val restored = VerifiedModelCache.install(temporaryFolder.root, manifest) {
            ByteArrayInputStream(bytes)
        }

        assertEquals(first, restored)
        assertArrayEquals(bytes, restored.readBytes())
    }

    @Test
    fun failedNewInstallLeavesPreviousFingerprintUntouched() {
        val oldBytes = "old-model".toByteArray()
        val newBytes = "new-model".toByteArray()
        val oldManifest = manifestFor(oldBytes, "old")
        val newManifest = manifestFor(newBytes, "new")
        val oldFile = VerifiedModelCache.install(temporaryFolder.root, oldManifest) {
            ByteArrayInputStream(oldBytes)
        }

        assertThrows(IOException::class.java) {
            VerifiedModelCache.install(temporaryFolder.root, newManifest) {
                object : InputStream() {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        throw IOException("simulated failure")

                    override fun read(): Int = throw IOException("simulated failure")
                }
            }
        }

        assertArrayEquals(oldBytes, oldFile.readBytes())
        assertTrue(oldFile.exists())
    }

    private fun manifestFor(bytes: ByteArray, version: String): BundledModelManifest =
        BundledModelManifest(
            byteCount = bytes.size.toLong(),
            sha256 = sha256(bytes),
            version = version
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun <T : Throwable> assertThrows(type: Class<T>, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (type.isInstance(error)) return
            throw AssertionError("Expected ${type.name}, got ${error.javaClass.name}", error)
        }
        throw AssertionError("Expected ${type.name}")
    }

    private object FileAssertions {
        fun hasModelFile(directory: java.io.File, manifest: BundledModelManifest): Boolean =
            java.io.File(directory, "verivox_${manifest.sha256}.onnx").isFile
    }
}
