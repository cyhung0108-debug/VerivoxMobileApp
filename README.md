# VeriVox Mobile App

VeriVox 是研究用的 Android 語音真偽辨識原型。App 支援手機 ONNX Runtime 推論和 Flask API 分析。模型架構、匯出工具和正式的 Python 推論服務由 [VerivoxModel](https://github.com/cyhung0108-debug/VerivoxModel) 管理。

## 建置 Android App

用 Android Studio 開啟包含 `settings.gradle.kts` 的專案根目錄。建議 Gradle JDK 17 和 Android SDK 34。最低裝置版本為 Android 10（API 29）。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK 位於 `app/build/outputs/apk/debug/app-debug.apk`。舊 APK 如果用不同的 debug 金鑰簽署，Android 會拒絕覆蓋安裝。移除先前的 App 會清除其設定和模型檔。

## 手機本地分析

1. 開啟 App，選「手機本地 → 下載本地模型」。目前的 FP32 模型下載約 1.26 GB。請預留儲存空間，並保持畫面開啟直至啟用完成。
2. 選擇一個或多個 PCM/Float WAV，按「開始分析」。選擇多檔時，也可在背景分析並查看完成統計。
3. 模型啟用後可離線分析。模型組發佈相容的新版本時，在手機上點「檢查模型更新」即可更新，APK 不需要重裝。

App 從 [VerivoxModel 最新正式 Release](https://github.com/cyhung0108-debug/VerivoxModel/releases/latest) 讀取版本資訊，按指定 tag 下載 ONNX，並校驗大小、SHA-256、輸入輸出形狀與基本推論。下載或驗證失敗時，已安裝的模型仍可使用。更新時需要同時保留新舊模型的空間。

動態模型會將 WAV 轉為 16 kHz 單聲道。1 至 8 秒使用整段音訊，超過 8 秒只分析開頭 4 秒。延續 2026-10-30 版本，最低程式門檻是 Android 回報至少 2 GiB RAM，並排除系統標記為低 RAM 的裝置。建議在 ARM64、8 GB 或以上的真機測試。模型結果供研究使用。

## 雲端／電腦分析

在 App 的雲端模式輸入 Flask 根網址或 `/v1/analyze`。App 會送出 multipart 的 `file` 欄位。實際模型 API 的啟動方式在 [VerivoxModel](https://github.com/cyhung0108-debug/VerivoxModel#桌面和雲端分析)，本 repo 保留 [mock API](backend/README.md) 供手機組測試。

也可用自己的 `local.properties` 設定預設值：實體裝置用 `verivox.physicalDeviceAnalyzeUrl`，所有裝置可用 `verivox.apiUrl` 覆蓋。模擬器未指定覆蓋時使用 `http://10.0.2.2:5000/v1/analyze`。正式部署使用 HTTPS。Cloudflare Quick Tunnel 僅在電腦和伺服器都運行時有效。

## 歷史版本

| 來源 | 內容 |
| --- | --- |
| `master` | Android Studio 初始專案，原伺服器與後兩個手機 ZIP 的 tag 指向這個分支的提交。 |
| `verivox-mobile` | 早期 Compose、Flask／Cloudflare 測試與雲端推論源碼。 |
| [`v0.1.0-debug`](https://github.com/cyhung0108-debug/VerivoxMobileApp/releases/tag/v0.1.0-debug) | 雲端模式測試 APK。 |
| [`v0.1.0-server`](https://github.com/cyhung0108-debug/VerivoxMobileApp/releases/tag/v0.1.0-server) | Flask、舊桌面介面、PyTorch 權重及 backbone，分成 Core 與 Backbone ZIP。 |
| [`v0.2.0-debug`](https://github.com/cyhung0108-debug/VerivoxMobileApp/releases/tag/v0.2.0-debug) | APK 和 Android 原始碼 ZIP 新增本地 ONNX 推論。 |
| [`0.3.0-batch`](https://github.com/cyhung0108-debug/VerivoxMobileApp/releases/tag/0.3.0-batch) | ZIP 新增 WorkManager 背景批次分析和 Android 14 前景服務設定。 |
| [`0.4.0____0.3.0update`](https://github.com/cyhung0108-debug/VerivoxMobileApp/releases/tag/0.4.0____0.3.0update) | 2026-10-30 ZIP 新增前景逐檔結果並縮短長音訊分析流程。 |

`main` 收錄三份手機 ZIP 的源碼演進，整合前景逐檔結果和 WorkManager 背景批次。原分支、tags 與 Release 仍可查閱。
