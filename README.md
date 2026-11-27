# nhi-crawler — 健保署法規公告自動擷取與記憶體 PDF 寄信系統

以 **Java 25 + Spring Boot** 開發的自動化公告追蹤服務，每日定時擷取[衛生福利部中央健康保險署－法規公告](https://www.nhi.gov.tw/ch/lp-3258-1.html)，將公告 PDF/ODT 附件直接載入記憶體（不寫入本機暫存檔），驗證檔案完整性後透過 Gmail SMTP 夾帶寄出。

---

## 系統架構與核心模組

```text
NhiCrawlerScheduler (@Scheduled 每日 08:00 Asia/Taipei)
  ├── 依今日民國日期呼叫 ScraperService
  │     ├── CDP (debuggerAddress 127.0.0.1:9222) 接管本機 Chrome
  │     ├── Jsoup 解析公告列表並依刊登日期篩選
  │     ├── 同源 fetch() 取得詳細頁 HTML 與附件位元組 (byte[])
  │     └── 驗證 %PDF 檔頭並記錄失敗附件 (failedPdfUrls)
  ├── 成功且附件完整 ──▶ EmailService.sendMailWithAnnouncements()
  │                        └── MimeMessageHelper + ByteArrayResource 夾帶寄出
  └── 驗證過期或例外 ────▶ EmailService.sendFailureAlert()
                           └── 寄發系統告警信通知人工重新驗證
```

| 類別 | 職責說明 |
| :--- | :--- |
| `NhiCrawlerScheduler` | 每日台灣時間 `08:00` 依 `MinguoDate`（民國日期）觸發爬取；若當日無新公告則略過，發生異常時自動寄出告警信。 |
| `ScraperService` | 透過 CDP 連接已開啟的 Chrome，以同源 `fetch()` 抓取詳細頁與附件至記憶體，並驗證 `.pdf` 檔頭為 `%PDF`。 |
| `AnnouncementListParser` | 只負責「看懂列表頁」：把 HTML 文字轉成公告列（`AnnouncementRow`）並依刊登日期篩選；不連 Chrome、不上網，可直接用存下來的 HTML 檔做單元測試。 |
| `DetailPageParser` | 只負責「看懂詳細頁」：找出附件連結（去除重複），並判斷拿到的是否為 Cloudflare 驗證頁。 |
| `EmailService` | 逐筆檢查每則公告的附件完整性（任一附件失敗即中止寄信），使用 `ByteArrayResource` 直接從記憶體附加檔案寄出。 |
| `Announcement` | 封裝公告中繼資料（標題、發文字號、刊登日期等）、`Map<String, byte[]>` 記憶體附件與 `failedPdfUrls`。 |

---

## 為什麼採用「CDP 接管本機 Chrome ＋ 同源 `fetch()`」？

本專案在開發過程中針對健保署網站的防護機制進行了實測與架構演進：

1. **純 HTTP 請求（`Jsoup` / `curl` / 官方 RSS）無法通過驗證**
   實測直接請求列表頁（`lp-3258-1.html`）與官方法規公告 RSS（`rss-3258-1.xml`），皆回傳 `HTTP 403` 與 `cf-mitigated: challenge`，確認整個站點（含 RSS）均受 Cloudflare JS Challenge 保護。
2. **標準 `ChromeDriver` 與跨工具 `HttpClient` 易遭攔截**
   標準 Selenium 啟動的瀏覽器會暴露自動化特徵，導致 Cloudflare Turnstile 驗證循環失敗；即使取得 Cookie 後改用 Java `HttpClient` 下載附件，仍可能因 TLS 指紋（TLS Fingerprint）差異遭 WAF 阻擋。
3. **最終定案架構與容錯設計**
   * **CDP 接管真實瀏覽器**：以 `--remote-debugging-port=9222` 啟動獨立設定檔的 Chrome，讓 Selenium 透過 `debuggerAddress` 接管已通過驗證的瀏覽器工作階段。
   * **同源 `fetch()` 記憶體串流**：在已驗證的列表頁內執行非同步 `fetch(url, { credentials: 'include' })` 取得詳細頁 HTML 與 PDF 位元組陣列（`byte[]`），避免反覆跳轉頁面觸發驗證，且全程不產生本機暫存檔。
   * **資料完整性與可觀測性**：任何附件下載失敗即記錄於 `failedPdfUrls` 並中止寄信，避免靜默寄出僅含連結的半成品；排程失敗則透過 `sendFailureAlert` 主動告警。

---

## 快速開始

### 1. 環境需求
* **JDK 25**（或自行於 `pom.xml` 調整為本機安裝之相容 JDK 版本）
* **Google Chrome**
* **Gmail 帳號與應用程式密碼（App Password）**

### 2. 設定環境變數

本專案已將機密設定抽離 `application.properties`，請於終端機或 IDE 設定以下環境變數：

```bash
export GMAIL_USERNAME="your-email@gmail.com"
export GMAIL_APP_PASSWORD="your-16-char-app-password"

# 以下為可選設定（未設定時採用預設值）
export NHI_MAIL_RECIPIENT="recipient@gmail.com"
export NHI_CHROME_DEBUGGER_ADDRESS="127.0.0.1:9222"
```

### 3. 啟動供爬蟲接管的 Chrome 視窗

請開啟一支獨立設定檔的 Chrome（不會影響平常使用的瀏覽器），並在首次開啟時訪問一次 `https://www.nhi.gov.tw/ch/lp-3258-1.html` 確認能正常顯示列表：

* **macOS**：
  ```bash
  /Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --remote-debugging-port=9222 --user-data-dir="$HOME/nhi-chrome-profile"
  ```
* **Windows (CMD)**：
  ```cmd
  "C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222 --user-data-dir="C:\nhi-chrome-profile"
  ```

### 4. 執行測試或啟動排程服務

* **執行網頁解析單元測試（不需 Chrome、不會寄信，約一秒）**：
  ```bash
  ./mvnw test -Dtest='AnnouncementListParserTest,DetailPageParserTest'
  ```
* **執行整合測試（抓取最新刊登日期公告並實際寄信）**：
  ```bash
  ./mvnw test -Dtest=NhiCrawlerApplicationTests#scrapeAndSendEmailIntegrationTest
  ```
* **啟動 Spring Boot 排程服務（每日 08:00 自動檢查）**：
  ```bash
  ./mvnw spring-boot:run
  ```
