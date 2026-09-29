# VeriVox Mobile App

VeriVox Mobile 是一個 Android prototype。手機負責選取和上傳音訊，電腦上的 Flask API 呼叫原有的 VeriVox 模型，再把分析結果回傳到手機。現階段模型仍在電腦執行；這個架構保留了日後把模型移到真正雲端，或改成手機本地推論的空間。

## 系統流程

```text
Android App
    │  HTTPS（Cloudflare Quick Tunnel）
    ▼
cloudflared（電腦）
    │  http://127.0.0.1:5000
    ▼
Flask real_server.py
    │  model.py + 本機模型權重
    ▼
VeriVox / Wav2Vec2 模型
    ▲
    └── JSON 分析結果、處理時間、請求編號
```

Cloudflare Quick Tunnel 不會執行模型，也不會在電腦關機後繼續工作。測試時，Python 伺服器、Cloudflare 終端機和電腦都必須保持開啟；每次重新建立 Quick Tunnel 都可能得到新的網址。

## 目前功能

- Android Jetpack Compose 介面，可用系統檔案選擇器選取音訊。
- 透過 `multipart/form-data` 的 `file` 欄位上傳音訊到 `POST /v1/analyze`。
- 支援 WAV、FLAC、MP3、WebM、M4A、OGG。
- MP3、WebM、M4A、OGG 會由後端用 FFmpeg 轉為 16 kHz、單聲道的暫存 WAV；分析完成後暫存檔會刪除。
- 顯示分類、真人／AI 機率、模型版本、請求編號，以及：
  - **送出音訊到收到結果**：手機端的完整 round-trip（上傳、網路、Cloudflare、伺服器和下載回應）。
  - **音訊分析處理時間**：後端收到請求至準備回應，包含轉檔、預處理和模型運算。
  - **純模型運算時間**：後端實際呼叫模型的時間。
- 單一上傳上限為 10 MB。第一次分析通常較慢，因為模型已在伺服器啟動時載入。

## 專案結構

```text
.
├── app/                         # Android Jetpack Compose app
├── backend/
│   ├── real_server.py           # 真實模型 API（使用這個做正式測試）
│   ├── mock_server.py           # 固定結果的連線測試 API
│   ├── local_config.example.py  # 本機模型路徑範本
│   └── REAL_MODEL.md            # 後端 API 和模型細節
└── README.md
```

模型權重、原本的 ASVspoof 專案和 Python virtual environment 不放在這個 Git repository。不要把權重、個人路徑、IP、Cloudflare 網址或帳密提交到 Git。

## 一次性準備

需要：

- Windows 電腦（或可執行同等命令的其他作業系統）。
- Android Studio、Android SDK（此專案以 compile SDK 34、最低 Android API 29 建置），以及 Android 手機或 Emulator。
- 原 VeriVox/ASVspoof 專案的 Python environment 和模型檔案。
- FFmpeg（要測試 MP3、M4A、WebM、OGG 時必須有）。
- `cloudflared`。Windows 安裝方式請參考 [Cloudflare 下載頁](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/downloads/)。

本 README 使用的是臨時 Quick Tunnel，不需要把模型部署到 Cloudflare 或設定自有網域；它會產生隨機、公開的 `trycloudflare.com` 網址。這和正式的命名 tunnel／雲端部署是不同流程。

### 1. 設定模型路徑

模型資料夾應至少包含：

```text
verivox-model-assets/
├── model.py
├── model.safetensors
├── wav2vec2-xls-r-300m-model/
│   ├── config.json
│   └── model.safetensors
└── wav2vec2-xls-r-300m-feature/
    └── preprocessor_config.json
```

在專案根目錄執行一次（如果 `backend/local_config.py` 已存在，直接編輯它，不要重複覆蓋）：

PowerShell：

```powershell
if (-not (Test-Path backend\local_config.py)) {
    Copy-Item backend\local_config.example.py backend\local_config.py
}
notepad backend\local_config.py
```

Command Prompt：

```bat
if not exist backend\local_config.py copy backend\local_config.example.py backend\local_config.py
notepad backend\local_config.py
```

把 `PROGRAM_DIR` 改成**包含 `model.py` 的資料夾**，例如：

```python
PROGRAM_DIR = r"C:\path\to\asvspoof\program"
```

`backend/local_config.py` 已被 `.gitignore` 排除，只保留在每台開發電腦上。也可以不用這個檔案，改用環境變數 `VERIVOX_PROGRAM_DIR`。

原專案的 virtual environment 應包含 Flask、PyTorch、Transformers、librosa、soundfile、safetensors 等依賴。這個 mobile repository 沒有另外複製一份 Python environment 或 `requirements.txt`；建議直接使用已能載入模型的原專案 venv。若要在新電腦安裝，請依原 ASVspoof/VeriVox 專案的 requirements 安裝。

原始第一版專案可參考 [FYP VeriVox Audio Deepfake Detector](https://github.com/s350fgroup25/FYP_VeriVox_Audio_Deepfake_Detector)。請只從可信的團隊副本取得模型程式和權重，並把它們放在 repository 之外。

### 2. 確認依賴工具

```powershell
cloudflared --version
ffmpeg -version
```

兩個命令都應該印出版本。若只測試 WAV 或 FLAC，後端不會走 FFmpeg 轉檔，但建議仍安裝它，以免手機選到其他格式時失敗。

### 3. 開啟 Android 專案

如果尚未取得程式碼，可以 clone 或下載 ZIP；在 Android Studio 開啟**包含 `settings.gradle.kts` 的 repository 根資料夾**，不要只開 `app/` 子資料夾。然後：

1. 按 **File → Sync Project with Gradle Files**，等待右下角 Gradle 同步完成。
2. 選擇實體手機或 Emulator。
3. 按 **Run ▶** 建置並安裝 App。

App 內有 **API 網址** 欄位，因此 Quick Tunnel 改網址時通常不需要修改程式碼或重新 build。這個欄位是執行期間的設定，App 程序被系統清除或重新開啟後可能要再貼一次網址。

## 每次測試的完整啟動順序

以下流程要開兩個終端機視窗。請不要關閉它們或按 `Ctrl+C`，直到測試完成。

### A. 啟動真正的 VeriVox Flask 伺服器

先切換到本 Android 專案根目錄。把命令中的兩個 `C:\path\...` 換成你電腦的實際位置。

Command Prompt：

```bat
cd /d "C:\path\to\VerivoxMobileApp"
"C:\path\to\asvspoof\venv\Scripts\python.exe" backend\real_server.py
```

PowerShell（執行含完整路徑的 Python 時要有 `&`）：

```powershell
Set-Location "C:\path\to\VerivoxMobileApp"
& "C:\path\to\asvspoof\venv\Scripts\python.exe" backend\real_server.py
```

不要只用 `py backend\real_server.py`，因為 `py` 可能選到沒有模型依賴的另一個 Python。若已經啟用正確 venv，也可以直接使用 `python backend\real_server.py`。

看到以下類似訊息才表示模型已載入：

```text
Loading VeriVox model on cpu...
VeriVox model loaded successfully.
Running on http://127.0.0.1:5000
Running on http://<電腦區域網路IP>:5000
```

載入約 2.5 GB 權重可能需要一段時間。若看不到 `VeriVox model loaded successfully`，先不要啟動 App；查看這個終端機的錯誤及 `PROGRAM_DIR`。

另開瀏覽器在**電腦**測試：

```text
http://127.0.0.1:5000/health
```

正常時 JSON 會包含 `"status": "ok"` 和 `"model_loaded": true`。瀏覽器額外請求 `/favicon.ico` 而得到 404 是正常的，不代表 API 壞掉。

### B. 啟動 Cloudflare Quick Tunnel

在第二個終端機執行：

```powershell
cloudflared tunnel --url http://127.0.0.1:5000
```

等待終端機出現類似：

```text
https://some-random-name.trycloudflare.com
```

複製這個 `https://...trycloudflare.com` 根網址。在瀏覽器測試：

```text
https://some-random-name.trycloudflare.com/health
```

應看到 `status: "ok"`、`model_loaded: true`。如果 tunnel 終端機沒有印出網址，等幾秒並查看是否有網路或 `cloudflared` 錯誤；成功建立後網址會在同一個終端機中顯示，不是在 Flask 視窗中顯示。

### C. 在手機 App 進行分析

1. 啟動已安裝的 VeriVox App。
2. 在 **API 網址** 欄位貼上 Quick Tunnel 的**根網址**，例如 `https://some-random-name.trycloudflare.com`。也可以貼 `/health` 或 `/v1/analyze`，App 會自動整理成 `/v1/analyze`。
3. 按 **選擇音訊檔**，選擇不超過 10 MB 的音訊。
4. 按 **開始分析**。
5. 等待狀態變成 **分析完成**，查看分類、機率和三種時間。

成功時，Python/Flask 終端機會出現：

```text
POST /v1/analyze HTTP/1.1" 200 -
```

這行表示後端收到分析請求並成功回應；手機畫面才是使用者看到的最終結果。

### D. 測試完成後

先在 Cloudflare 終端機按 `Ctrl+C`，再在 Flask 終端機按 `Ctrl+C`。下次重新開機或重新建立 tunnel 時，重做 A、B，並把新的網址貼回 App。電腦睡眠、關機、Flask 停止或 tunnel 停止時，手機不能取得分析結果。

## 不使用 Cloudflare 的替代測試方式

這些方式只適合同一台電腦或同一個區域網路：

- Android Emulator：使用 `http://10.0.2.2:5000`（這是 Emulator 指向主機的特殊位址）。
- 實體手機同一個 Wi-Fi：使用電腦目前的區域網路 IP，例如 `http://192.168.x.x:5000`，並確保 Windows 防火牆允許 Python/5000 埠。
- 實體手機不能使用 `127.0.0.1`：那會指向手機自己，不是電腦。

Cloudflare 的好處是實體手機不必直接連入電腦區域網路，而且使用 HTTPS；代價是網址短暫、公開且每次可能改變。

## 只測試 App 連線（可選）

如果模型環境尚未準備好，可以先停止 `real_server.py`，再於專案根目錄執行：

```powershell
py backend\mock_server.py
```

它會在同一個 5000 埠回傳固定的 `0.50 / 0.50` 和 `mock-v0`，只用來確認檔案選擇、上傳、Cloudflare 和畫面流程。Mock 和 real server 不能同時使用同一個埠；完成連線測試後按 `Ctrl+C`，再按上面的 A 步驟啟動真實模型。

## API 摘要

| 方法 | 路徑 | 用途 |
| --- | --- | --- |
| `GET` | `/health` | 查看服務和模型是否已載入 |
| `POST` | `/v1/analyze` | 以 multipart 欄位 `file` 上傳音訊並取得結果 |

成功回應會包含 `label`、`prob_real`、`prob_fake`、`model_version`、`processing_time_ms`、`inference_time_ms` 和 `request_id`。完整後端說明見 [`backend/REAL_MODEL.md`](backend/REAL_MODEL.md)。

目前 mobile API 的 `REAL_THRESHOLD` 是 `0.50`；舊版網頁的部分頁面曾使用 `0.10`。因此同一段音訊在兩個版本出現不同的 `genuine/fake` 標籤並不一定代表模型換了，還可能是判斷門檻不同。若要重現舊版標籤策略，請在確認需求後修改 `backend/real_server.py` 的門檻。

## 常見問題

| 現象 | 原因和處理方式 |
| --- | --- |
| 手機顯示 `127.0.0.1 refused` | 手機把 127.0.0.1 當成自己。實體手機貼 Cloudflare 根網址；Emulator 用 `10.0.2.2:5000`。 |
| `failed to connect`、timeout | 確認 Flask 和 cloudflared 兩個視窗仍在執行；Quick Tunnel 可能已過期，重啟後把新網址貼入 App。 |
| Tunnel URL 開啟 502/530 | Cloudflare 找不到本機 5000 服務；先確認 Flask 的 `/health` 在電腦可開，再重啟 tunnel。 |
| `/health` 回 `degraded` 或 `model_loaded: false` | 模型路徑、權重或 Python 依賴有問題。檢查 `backend/local_config.py`，並使用包含模型依賴的 venv Python 啟動。 |
| Python 一啟動就顯示 `ModuleNotFoundError: model` 或找不到模型模組 | `PROGRAM_DIR` 必須指向直接包含 `model.py` 的資料夾，不是它的上一層或某個權重子資料夾；同時確認該 venv 已安裝原模型依賴。修正後重新啟動 Flask。 |
| `POST /v1/analyze ... 200` 沒出現 | 請求未抵達這個 Flask 視窗，通常是 App 網址錯誤、tunnel 已變更或服務未啟動。 |
| `favicon.ico 404` | 瀏覽器自動尋找網站圖示，與 API 無關，可以忽略。 |
| `file_too_large` / HTTP 413 | 檔案接近或超過 10 MB；選擇較短或較小的音訊。multipart 額外標頭也會佔少量大小，實際請留一些餘量。 |
| `audio_processing_failed` / HTTP 422 | 檔案損壞、沒有音軌或需要 FFmpeg。先用可播放的 WAV/FLAC，或確認 `ffmpeg -version` 可用。 |
| HTTP 415 | 副檔名不在 WAV、FLAC、MP3、WebM、M4A、OGG 之內。 |
| 分析等候很久後 timeout | prototype 的手機端等待上限是 120 秒；先用較短音訊，並確認 Flask 終端機仍在運算。日後可再調整 `CloudApi` 的 `readTimeout`。 |
| App 仍顯示舊畫面或沒有時間欄位 | 在 Android Studio 重新 **Sync Project with Gradle Files**，再按 Run；必要時先停止舊 App 並重新安裝。 |
| PowerShell 顯示 `UnexpectedToken` | PowerShell 執行完整 `.exe` 路徑要在前面加 `&`；或改用 Command Prompt 的 `cd /d` 命令。 |

## 開發和安全注意事項

- Quick Tunnel 是 prototype 轉發工具，不是正式雲端部署：拿到網址的人都可能嘗試呼叫 API。現階段沒有登入驗證、速率限制或正式的使用者隔離，不要用來處理敏感錄音。
- Cloudflare 只把請求轉發到本機；模型權重仍留在電腦上。後端分析用的暫存音訊在請求結束時刪除，但仍應按照團隊的私隱政策測試。
- `usesCleartextTraffic` 和本機 HTTP 只為開發用途保留；對外測試應使用 HTTPS tunnel。正式部署還需要身份驗證、持久網址、日誌政策、限制上傳大小和正式 WSGI/容器環境。
- `local_config.py`、`local.properties`、模型權重、virtual environment 和個人 IP 都不應提交到 Git。
- 日後若要移到真正雲端，保留目前 `/health` 和 `/v1/analyze` API 合約，將 `real_server.py` 放入雲端服務即可，Android 端只需換 API 根網址。
- 日後若要做手機本地推論，可保留目前 `DetectionResult` 和 UI，把 `CloudApi.analyze` 替換成 TFLite/ONNX Runtime 推論層，讓畫面和結果格式不必大改。
