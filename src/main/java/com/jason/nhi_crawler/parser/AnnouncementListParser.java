package com.jason.nhi_crawler.parser;

import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import com.jason.nhi_crawler.model.AnnouncementRow;

/**
 * 把「公告列表頁的 HTML 文字」轉成 {@link AnnouncementRow} 清單。
 *
 * <p>這個類別刻意只做一件事：讀 HTML、吐資料。它不連 Chrome、不上網、不寄信。
 * 好處是同一份 HTML 丟進來，永遠得到同樣的結果，所以可以用存下來的網頁檔案做單元測試，
 * 不必開瀏覽器，也不用擔心 Cloudflare。健保署網站改版時，通常也只需要改這個檔案。
 */
@Component
public class AnnouncementListParser {

    /**
     * @param html              列表頁的完整 HTML
     * @param baseUrl           列表頁網址，用來把相對連結（/ch/cp-xxx.html）補成完整網址
     * @param targetPublishDate 要篩選的民國刊登日期（例如 115-11-13）；
     *                          傳 null 或空字串時，自動以列表第一筆的刊登日期當作「最新」
     * @return 刊登日期符合的公告列，依網頁上的順序排列
     * @throws IllegalStateException 頁面上完全找不到公告連結時（多半是被驗證頁擋住，或網站改版）
     */
    public List<AnnouncementRow> parse(String html, String baseUrl, String targetPublishDate) {
        Document doc = Jsoup.parse(html, baseUrl);

        if (doc.select("td.title a").isEmpty()) {
            throw new IllegalStateException("未找到公告連結，可能是驗證未通過或網頁結構改變");
        }

        boolean autoLatest = targetPublishDate == null || targetPublishDate.isBlank();
        String resolvedDate = autoLatest ? null : targetPublishDate;
        List<AnnouncementRow> matched = new ArrayList<>();

        for (Element row : doc.select("tr")) {
            AnnouncementRow parsed = parseRow(row);
            if (parsed == null) {
                continue;
            }

            if (resolvedDate == null) {
                resolvedDate = parsed.publishDate();
            }

            if (resolvedDate.equals(parsed.publishDate())) {
                matched.add(parsed);
            } else if (autoLatest && !matched.isEmpty()) {
                // 列表依刊登日期由新到舊排列：最新那一天的公告讀完就可以停了
                break;
            }
        }

        return matched;
    }

    /**
     * 解析表格中的一列；表頭列或沒有公告連結的列回傳 null。
     */
    private AnnouncementRow parseRow(Element row) {
        if (row.select("td").isEmpty()) {
            return null;
        }

        Element titleLink = row.selectFirst("td.title a");
        if (titleLink == null || titleLink.attr("href").isBlank()) {
            return null;
        }

        return new AnnouncementRow(
                titleLink.text(),
                // absUrl 會依照 baseUrl 把相對路徑補完整，比手動拼 "https://www.nhi.gov.tw" + href 可靠
                titleLink.absUrl("href"),
                row.select("td[data-th=發文字號]").text(),
                row.select("td[data-th=發文日期] time").text().trim(),
                row.select("td[data-th=刊登日期] time").text().trim(),
                row.select("td[data-th=刊登期限] time").text().trim());
    }
}
