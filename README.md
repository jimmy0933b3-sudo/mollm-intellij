
## MCP (Model Context Protocol) 支援

MoLLM 現在支援透過 [MCP (Model Context Protocol)](https://modelcontextprotocol.io) 載入外部工具，讓模型能夠存取更多元的本機資源與遠端服務。

### 支援的傳輸協定
1. **Stdio (命令列傳輸)**: 可執行本機的 CLI 指令並透過標準輸入/輸出與 MCP 伺服器溝通。
   - **範例**: 使用 `npx` 啟動 `@modelcontextprotocol/server-everything`：
     - **執行檔**: `npx`
     - **參數**: `-y @modelcontextprotocol/server-everything`
2. **SSE (HTTP 串流傳輸)**: 透過 HTTP 連線至遠端或本機的 MCP 伺服器。
   - **範例**: `http://localhost:3000/sse`

### 安全認證
當您使用 SSE 傳輸並需要認證時，可以在設定頁面中填寫 `Auth Token`，並勾選「安全儲存」。
這些資訊會被加密並存放於 IntelliJ 內建的 Password Safe 當中，絕不會以明碼方式寫入設定檔或日誌中，且不會傳送給語言模型。如果需要自訂標頭，也可以透過 Header 欄位（以 `Key:Value;` 格式）指定。

### 設定匯入
在 MCP 管理介面中，支援點擊「從 JSON 匯入」按鈕，您可以選擇現有的 `mcp.json` 設定檔（例如 Claude Desktop 的配置）。匯入程式將自動解析：
- Stdio 執行檔、參數與環境變數
- SSE 的伺服器網址
如果有環境變數（包含 `$VAR` 或 `%VAR%`）參考，系統會跳出警告讓您在隨後的預覽對話框中手動設定。

### 權限控制
所有 MCP 工具執行前都會彈出單次的許可對話框，要求您手動批准（您可以決定本次是否執行或拒絕）。
這確保了所有透過 MCP 發生的動作，皆在您的明確授權之下完成。


## GitHub Actions 建置、簽章與 Release

* 推送到 **master** 或手動執行 Actions 的 **Build and Release Plugin**：建置、執行 `check`、打包 ZIP、以 GitHub Actions Secrets 簽章，成功後建立最新 Release。
* Pull Request：執行相同的編譯與測試並提供**未簽章**的 Actions Artifact，不使用簽章 Secrets，也不建立 Release。
* 每次發布使用獨立的 `ci-<run>-<attempt>-<sha>` 標籤。確認 ZIP、SHA-256 與新 Release 上傳成功後，刪除其餘舊 Releases；只刪除由此 CI 產生的舊標籤，不刪除手動維護的版本標籤。
* 最新可安裝檔：<https://github.com/jimmy0933b3-sudo/mollm-intellij/releases/latest>。下載 `mollm-intellij-<version>.zip`，在 IntelliJ IDEA 中選擇 **Settings → Plugins → ⚙ → Install Plugin from Disk**。直接選擇外掛 ZIP，不用自行解壓。
* 只有完整編譯、測試、簽章及發版成功才會清除舊 Release；失敗時保留上一個可下載版本。

### 必填 Repository Actions Secrets

到 **Settings → Secrets and variables → Actions → New repository secret** 設定：

| Secret 名稱 | 內容 |
| --- | --- |
| `KEYSTORE_BASE64` | 已有 Android Release KeyStore（JKS/PKCS12）檔案的 Base64 內容；可用 `ANDROID_KEYSTORE_BASE64` |
| `KEYSTORE_PASSWORD` | KeyStore 的密碼；可用 `ANDROID_KEYSTORE_PASSWORD` |
| `KEY_ALIAS` | KeyStore 私鑰的 alias；可用 `ANDROID_KEY_ALIAS` |
| `KEY_PASSWORD` | 私鑰密碼，可與 KeyStore 密碼不同；可用 `ANDROID_KEY_PASSWORD` |

**現在預設直接支援其他專案同一組四個 KeyStore Secrets。** 建置時從 KeyStore 取出簽章私鑰及 X.509 憑證，在 Runner 暫存目錄轉成 PKCS12 後簽署 ZIP，並驗證簽章；不會把密鑰放進 Release。若已使用 PEM，也可改用 `PRIVATE_KEY`、`CERTIFICATE_CHAIN`，加密 PEM 才需要 `PRIVATE_KEY_PASSWORD`。若兩組都有設定，優先使用 KeyStore。

CI 使用 GitHub Actions 內建的 `GITHUB_TOKEN` 建立 Release，不需要另外建立 GitHub PAT。請確認 Actions 允許執行工作流程，且所使用的簽章 Secrets 都已設定。master 建置若缺任一 Secret **直接失敗**，不會偷偷發布未簽章版本。

簽章遵循 [JetBrains Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)，使用固定版本、SHA-256 驗證過的 Marketplace ZIP Signer。機密資料只在 CI 中以暫存檔使用，不會寫入原始碼或發行附件。使用自簽憑證的外掛仍可能需要在 IntelliJ **Manage Plugin Certificates** 中信任該憑證；GitHub Releases 不等同於 JetBrains Marketplace 自動更新。
