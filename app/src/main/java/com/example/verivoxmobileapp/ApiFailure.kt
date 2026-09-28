package com.example.verivoxmobileapp

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/** Short, actionable diagnostics for this prototype; no raw HTML error pages. */
class ApiFailure(
    val code: String,
    message: String,
    val phase: String,
    val elapsedMs: Long? = null,
    val httpStatus: Int? = null,
    val requestId: String? = null,
    cause: Throwable? = null
) : IOException(message, cause) {
    fun displayMessage(): String = buildString {
        append("分析失敗：${message}\n階段：$phase\n錯誤：$code")
        httpStatus?.let { append("（HTTP $it）") }
        elapsedMs?.let { append("\n送出至失敗：${formatDuration(it.toDouble())}") }
        requestId?.let { append("\n請求編號：$it") }
    }
}

internal fun formatDuration(milliseconds: Double): String =
    String.format(Locale.ROOT, "%.2f 秒", milliseconds / 1000.0)

internal fun networkFailure(error: IOException, phase: String, elapsedMs: Long?): ApiFailure {
    val (code, message) = when (error) {
        is UnknownHostException -> "dns_failed" to
            "找不到伺服器網址。請檢查網絡、網址拼寫，以及是否貼上最新 Cloudflare 網址。"
        is ConnectException -> "connection_failed" to
            "無法連接伺服器。請確認 real_server.py 和 Cloudflare 視窗仍在執行。"
        is SocketTimeoutException -> "timeout" to
            "連線或等待回應逾時（連線上限 15 秒；讀取等待上限 120 秒）。伺服器可能仍在分析，請查看 Python 視窗。"
        is SSLException -> "tls_failed" to
            "HTTPS 安全連線失敗。請檢查手機日期時間及 https:// 網址。"
        else -> "network_io" to
            "傳送或接收資料時連線中斷。請檢查網絡及兩個伺服器視窗。（${error.javaClass.simpleName}）"
    }
    return ApiFailure(code, message, phase, elapsedMs, cause = error)
}

internal fun httpFailureHint(status: Int): String = when (status) {
    400 -> "上傳請求有問題，重新選擇音訊。"
    401, 403 -> "伺服器拒絕存取，檢查 API 網址/存取設定。"
    404, 405 -> "找不到分析接口。確認網址是目前的 Cloudflare 網址，路徑為 /v1/analyze。"
    413 -> "音訊檔太大，請用< 10 MB 的檔案。"
    415, 422 -> "音訊格式不支援、沒有音軌或檔案已損壞，嘗試 WAV 檔。"
    429 -> "伺服器目前忙碌或請求過多，請稍後重試。"
    502, 503 -> "伺服器暫時不可用。請確認 real_server.py 已載入模型、Cloudflare 仍在執行。"
    504, 524 -> "閘道等待分析結果逾時。請查看 Python 視窗，或用較短的音訊重試。"
    530 -> "Cloudflare 無法提供此連線。請確認 Tunnel 仍在執行，並貼上最新網址。"
    in 500..599 -> "伺服器處理失敗，請查看執行 real_server.py 的視窗。"
    else -> "伺服器回傳非預期狀態，請檢查 API 網址及 Python 視窗。"
}
