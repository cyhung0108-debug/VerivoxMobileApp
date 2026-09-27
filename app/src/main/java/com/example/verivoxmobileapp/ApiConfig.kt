package com.example.verivoxmobileapp

import android.os.Build

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
