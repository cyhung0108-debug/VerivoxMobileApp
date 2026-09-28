package com.example.verivoxmobileapp

import android.os.Build
import java.net.URI
import java.util.Locale

/**
 * Address of the temporary inference server used during development.
 *
 * The phone and the computer must be on the same Wi-Fi network. Replace the
 * address if your computer receives a different IPv4 address. When the API is
 * deployed to a real cloud host, this becomes an HTTPS URL.
 */
object ApiConfig {
    private const val EMULATOR_ANALYZE_URL = "http://10.0.2.2:5000/v1/analyze"
    private val apiUrlOverride = BuildConfig.VERIVOX_API_URL_OVERRIDE.trim()
    private val physicalDeviceAnalyzeUrl = BuildConfig.VERIVOX_PHYSICAL_DEVICE_ANALYZE_URL

    /**
     * The Android emulator maps 10.0.2.2 to the host computer's loopback.
     * A physical phone instead reaches the computer through its Wi-Fi IP.
     */
    val ANALYZE_URL: String
        get() = if (apiUrlOverride.isNotEmpty()) {
            apiUrlOverride
        } else if (isEmulator()) {
            EMULATOR_ANALYZE_URL
        } else {
            physicalDeviceAnalyzeUrl
        }

    /**
     * Accept either a complete /v1/analyze URL or the server's base URL.
     * This is used by the prototype's in-app endpoint field so that a new
     * Quick Tunnel URL can be entered without rebuilding the application.
     */
    fun normalizeAnalyzeUrl(value: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "請輸入 API 網址" }

        val parsed = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException("API 網址格式不正確，請貼上完整網址，不要包含空格")
        }
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") {
            "網址必須以 http:// 或 https:// 開頭"
        }
        require(!parsed.host.isNullOrBlank()) { "API 網址格式不正確" }
        require(parsed.port == -1 || parsed.port in 1..65535) { "網址的連接埠不正確" }
        require(parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null) {
            "請只貼上伺服器網址，不要包含帳密、? 參數或 # 片段"
        }
        require(parsed.path.trimEnd('/') in setOf("", "/health", "/v1/analyze")) {
            "請貼上根網址（例如 https://xxxx.trycloudflare.com）或 /v1/analyze 網址"
        }
        return URI(scheme, parsed.rawAuthority, "/v1/analyze", null, null).toASCIIString()
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.contains("sdk_gphone", ignoreCase = true) ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            Build.MODEL.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk_gphone", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.DEVICE.contains("emu", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true)
    }
}
