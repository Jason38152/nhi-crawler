package com.jason.nhi_crawler.service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.jason.nhi_crawler.model.Announcement;
import com.jason.nhi_crawler.model.AnnouncementRow;
import com.jason.nhi_crawler.model.AttachmentLink;
import com.jason.nhi_crawler.parser.AnnouncementListParser;
import com.jason.nhi_crawler.parser.DetailPageParser;

@Service
public class ScraperService {

    private static final String TARGET_URL = "https://www.nhi.gov.tw/ch/lp-3258-1.html";
    private static final Pattern FILENAME_STAR_PATTERN =
            Pattern.compile("filename\\*=UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_PATTERN =
            Pattern.compile("filename=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    private final String debuggerAddress;
    private final AnnouncementListParser listParser;
    private final DetailPageParser detailPageParser;

    // 建構子注入：需要的東西都從建構子傳進來，測試時可以直接 new ScraperService(...) 換成假物件
    public ScraperService(
            @Value("${nhi.chrome.debugger-address:127.0.0.1:9222}") String debuggerAddress,
            AnnouncementListParser listParser,
            DetailPageParser detailPageParser) {
        this.debuggerAddress = debuggerAddress;
        this.listParser = listParser;
        this.detailPageParser = detailPageParser;
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /**
     * 抓取列表頁上「最新刊登日期」的所有公告與其記憶體 PDF 附件。
     */
    public List<Announcement> fetchLatestAnnouncement() {
        return fetchAnnouncementsByDate(null);
    }

    /**
     * 依指定民國刊登日期（格式如 115-11-13）篩選公告並載入附件至記憶體。
     * 若 targetPublishDate 為 null，則自動以列表第一筆的刊登日期作為最新日期。
     */
    public List<Announcement> fetchAnnouncementsByDate(String targetPublishDate) {
        List<Announcement> announcementsList = new ArrayList<>();
        ChromeDriver driver = connectToExistingChrome();

        try {
            driver.get(TARGET_URL);
            waitForPageReady(driver, By.cssSelector("td.title a"), 30);

            System.out.println("列表頁網址: " + driver.getCurrentUrl());
            System.out.println("列表頁標題: " + driver.getTitle());

            // 「看懂網頁」交給 parser；這裡只負責「把網頁拿回來」
            List<AnnouncementRow> matchedRows =
                    listParser.parse(driver.getPageSource(), TARGET_URL, targetPublishDate);
            System.out.println("符合篩選日期的公告筆數: " + matchedRows.size());

            for (int i = 0; i < matchedRows.size(); i++) {
                AnnouncementRow meta = matchedRows.get(i);
                if (i > 0) {
                    // 適度間隔請求，避免短時間連發觸發 Cloudflare 頻率限制
                    Thread.sleep(1000);
                }

                List<String> pdfUrls = new ArrayList<>();
                Map<String, byte[]> pdfAttachments = new LinkedHashMap<>();
                List<String> failedPdfUrls = new ArrayList<>();

                String detailHtml = fetchDetailHtml(driver, meta.link());
                List<AttachmentLink> attachmentLinks =
                        detailPageParser.extractAttachmentLinks(detailHtml, meta.link());

                String cookieHeader = buildCookieHeader(driver.manage().getCookies());
                String userAgent = extractUserAgent(driver);

                for (AttachmentLink attachmentLink : attachmentLinks) {
                    String attachmentUrl = attachmentLink.url();
                    pdfUrls.add(attachmentUrl);
                    boolean downloaded = downloadViaBrowserFetch(
                            driver, attachmentUrl, attachmentLink.text(), pdfAttachments);
                    if (!downloaded) {
                        downloaded = downloadViaHttpClient(
                                attachmentUrl, attachmentLink.text(), meta.link(), cookieHeader, userAgent, pdfAttachments);
                    }
                    if (!downloaded) {
                        failedPdfUrls.add(attachmentUrl);
                    }
                }

                Announcement announcement = new Announcement(
                        meta.title(),
                        meta.link(),
                        meta.referenceNo(),
                        meta.issueDate(),
                        meta.publishDate(),
                        meta.listingDeadline(),
                        pdfUrls,
                        pdfAttachments,
                        failedPdfUrls);
                announcementsList.add(announcement);
                System.out.println("成功 [" + meta.publishDate() + "]：" + announcement.getTitle()
                        + "（成功載入附件：" + pdfAttachments.size()
                        + "，失敗：" + failedPdfUrls.size() + "）");
            }
        } catch (Exception e) {
            throw new RuntimeException("爬取健保署公告失敗: " + e.getMessage(), e);
        }

        return announcementsList;
    }

    /**
     * 優先在已通過驗證的列表頁透過同源 fetch() 取得詳細頁 HTML，
     * 避免每次跳轉頁面都觸發整頁重載與 Cloudflare 驗證；若 fetch 被擋才退回 driver.get()。
     */
    private String fetchDetailHtml(ChromeDriver driver, String detailUrl) {
        try {
            String script = """
                const callback = arguments[arguments.length - 1];
                const url = arguments[0];
                fetch(url, { credentials: 'include' })
                  .then(async res => {
                    const text = await res.text();
                    callback({ ok: res.ok, status: res.status, html: text });
                  })
                  .catch(err => callback({ ok: false, error: err.toString() }));
                """;
            Object rawResult = driver.executeAsyncScript(script, detailUrl);
            if (rawResult instanceof Map<?, ?> resultMap) {
                boolean ok = Boolean.TRUE.equals(resultMap.get("ok"));
                String html = resultMap.get("html") != null ? resultMap.get("html").toString() : "";
                if (ok && !html.isBlank() && !detailPageParser.isBlockedPage(html)) {
                    return html;
                }
                System.out.println("詳細頁 fetch 未直接取得內容 (status=" + resultMap.get("status") + ")，改用瀏覽器導航: " + detailUrl);
            }
        } catch (Exception e) {
            System.out.println("詳細頁 fetch 例外，改用瀏覽器導航: " + e.getMessage());
        }

        driver.get(detailUrl);
        waitForPageReady(
                driver,
                By.xpath("//*[contains(text(),'發文字號') or contains(text(),'檔案下載')]"),
                30);
        return driver.getPageSource();
    }

    private ChromeDriver connectToExistingChrome() {
        String address = (debuggerAddress != null && !debuggerAddress.isBlank())
                ? debuggerAddress
                : "127.0.0.1:9222";
        ChromeOptions options = new ChromeOptions();
        options.setExperimentalOption("debuggerAddress", address);

        try {
            ChromeDriver driver = new ChromeDriver(options);
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
            return driver;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "無法連線到本機 Chrome (" + address + ")。請先用終端機啟動帶有 --remote-debugging-port=9222 的 Chrome。",
                    e);
        }
    }

    private void waitForPageReady(WebDriver driver, By expectedElement, int timeoutSeconds) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));
        try {
            wait.until(d -> {
                String title = d.getTitle();
                if (title == null || title.isBlank() || isBlockedTitle(title)) {
                    return false;
                }
                Object readyState = ((JavascriptExecutor) d).executeScript("return document.readyState");
                if (!"complete".equals(readyState)) {
                    return false;
                }
                return !d.findElements(expectedElement).isEmpty();
            });
        } catch (Exception e) {
            System.err.println("等待頁面逾時 — URL: " + driver.getCurrentUrl()
                    + " | Title: [" + driver.getTitle() + "]"
                    + " | 找到目標元素數量: " + driver.findElements(expectedElement).size());
            throw e;
        }
    }

    private boolean isBlockedTitle(String title) {
        if (title == null) {
            return true;
        }
        return title.contains("請稍候")
                || title.contains("Just a moment")
                || title.contains("Attention Required");
    }

    private boolean downloadViaBrowserFetch(
            ChromeDriver driver,
            String attachmentUrl,
            String linkText,
            Map<String, byte[]> pdfAttachments) {
        try {
            String script = """
                const callback = arguments[arguments.length - 1];
                const url = arguments[0];
                fetch(url, { credentials: 'include' })
                  .then(async res => {
                    if (!res.ok) {
                      callback({ ok: false, error: 'HTTP ' + res.status });
                      return;
                    }
                    const contentType = res.headers.get('content-type') || '';
                    const disposition = res.headers.get('content-disposition') || '';
                    const buf = await res.arrayBuffer();
                    const bytes = new Uint8Array(buf);
                    let binary = '';
                    const chunkSize = 0x8000;
                    for (let i = 0; i < bytes.length; i += chunkSize) {
                      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
                    }
                    callback({
                      ok: true,
                      contentType: contentType,
                      disposition: disposition,
                      base64: btoa(binary)
                    });
                  })
                  .catch(err => callback({ ok: false, error: err.toString() }));
                """;

            Object rawResult = driver.executeAsyncScript(script, attachmentUrl);
            if (!(rawResult instanceof Map<?, ?> resultMap)) {
                return false;
            }

            if (!Boolean.TRUE.equals(resultMap.get("ok"))) {
                System.err.println("瀏覽器 fetch 下載失敗 (" + resultMap.get("error") + "): " + attachmentUrl);
                return false;
            }

            String contentType = String.valueOf(resultMap.get("contentType")).toLowerCase();
            if (contentType.contains("text/html")) {
                System.err.println("瀏覽器 fetch 回傳為 HTML（可能遭驗證阻擋），略過: " + attachmentUrl);
                return false;
            }

            String base64 = (String) resultMap.get("base64");
            if (base64 == null || base64.isEmpty()) {
                return false;
            }

            byte[] fileBytes = Base64.getDecoder().decode(base64);
            if (fileBytes.length == 0) {
                return false;
            }

            String disposition = resultMap.get("disposition") != null ? resultMap.get("disposition").toString() : "";
            String fileName = resolveFileName(disposition, linkText, attachmentUrl, contentType);
            if (fileName.toLowerCase().endsWith(".pdf") && !isValidPdfHeader(fileBytes)) {
                System.err.println("檔案非有效 PDF 檔頭，略過: " + attachmentUrl);
                return false;
            }

            fileName = ensureUniqueFileName(fileName, pdfAttachments);
            pdfAttachments.put(fileName, fileBytes);
            System.out.println("已透過瀏覽器 fetch 載入記憶體: " + fileName + " (" + fileBytes.length + " bytes)");
            return true;
        } catch (Exception e) {
            System.err.println("瀏覽器 fetch 發生例外: " + attachmentUrl + " - " + e.getMessage());
            return false;
        }
    }

    private boolean downloadViaHttpClient(
            String attachmentUrl,
            String linkText,
            String refererUrl,
            String cookieHeader,
            String userAgent,
            Map<String, byte[]> pdfAttachments) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(attachmentUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", userAgent)
                    .header("Referer", refererUrl)
                    .GET();

            if (!cookieHeader.isEmpty()) {
                requestBuilder.header("Cookie", cookieHeader);
            }

            HttpResponse<byte[]> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                System.err.println("HttpClient 下載失敗 (HTTP " + response.statusCode() + "): " + attachmentUrl);
                return false;
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
            if (contentType.contains("text/html")) {
                System.err.println("HttpClient 回傳為 HTML 頁面（可能遭驗證阻擋），略過: " + attachmentUrl);
                return false;
            }

            byte[] fileBytes = response.body();
            if (fileBytes == null || fileBytes.length == 0) {
                System.err.println("附件內容為空，略過: " + attachmentUrl);
                return false;
            }

            String contentDisposition = response.headers().firstValue("Content-Disposition").orElse("");
            String fileName = resolveFileName(contentDisposition, linkText, attachmentUrl, contentType);
            if (fileName.toLowerCase().endsWith(".pdf") && !isValidPdfHeader(fileBytes)) {
                System.err.println("HttpClient 下載檔案非有效 PDF 檔頭，略過: " + attachmentUrl);
                return false;
            }

            fileName = ensureUniqueFileName(fileName, pdfAttachments);
            pdfAttachments.put(fileName, fileBytes);
            System.out.println("已透過 HttpClient 載入記憶體: " + fileName + " (" + fileBytes.length + " bytes)");
            return true;
        } catch (Exception e) {
            System.err.println("HttpClient 下載附件至記憶體失敗: " + attachmentUrl + " - " + e.getMessage());
            return false;
        }
    }

    private boolean isValidPdfHeader(byte[] bytes) {
        if (bytes.length < 4) {
            return false;
        }
        String header = new String(bytes, 0, 4, StandardCharsets.US_ASCII);
        return "%PDF".equals(header);
    }

    private String buildCookieHeader(Set<Cookie> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return "";
        }
        return cookies.stream()
                .map(c -> c.getName() + "=" + c.getValue())
                .collect(Collectors.joining("; "));
    }

    private String extractUserAgent(WebDriver driver) {
        try {
            Object ua = ((JavascriptExecutor) driver).executeScript("return navigator.userAgent;");
            if (ua != null && !ua.toString().isBlank()) {
                return ua.toString();
            }
        } catch (Exception ignored) {
        }
        return "Mozilla/5.0";
    }

    private String resolveFileName(
            String contentDisposition, String linkText, String attachmentUrl, String contentType) {
        if (contentDisposition != null && !contentDisposition.isBlank()) {
            Matcher starMatcher = FILENAME_STAR_PATTERN.matcher(contentDisposition);
            if (starMatcher.find()) {
                return sanitizeFileName(URLDecoder.decode(starMatcher.group(1), StandardCharsets.UTF_8));
            }
            Matcher normalMatcher = FILENAME_PATTERN.matcher(contentDisposition);
            if (normalMatcher.find()) {
                return sanitizeFileName(URLDecoder.decode(normalMatcher.group(1), StandardCharsets.UTF_8));
            }
        }

        String candidate = (linkText != null && !linkText.isBlank())
                ? linkText.trim()
                : attachmentUrl.substring(attachmentUrl.lastIndexOf('/') + 1);

        candidate = sanitizeFileName(candidate);
        if (!candidate.toLowerCase().endsWith(".pdf") && !candidate.toLowerCase().endsWith(".odt")) {
            if (contentType.contains("pdf") || attachmentUrl.toLowerCase().contains(".pdf")) {
                candidate += ".pdf";
            } else if (contentType.contains("opendocument") || attachmentUrl.toLowerCase().contains(".odt")) {
                candidate += ".odt";
            }
        }
        return candidate;
    }

    private String sanitizeFileName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "attachment.pdf" : cleaned;
    }

    private String ensureUniqueFileName(String fileName, Map<String, byte[]> existing) {
        if (!existing.containsKey(fileName)) {
            return fileName;
        }
        int dotIndex = fileName.lastIndexOf('.');
        String base = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String ext = dotIndex > 0 ? fileName.substring(dotIndex) : "";
        int index = 2;
        while (existing.containsKey(base + "_" + index + ext)) {
            index++;
        }
        return base + "_" + index + ext;
    }
}
