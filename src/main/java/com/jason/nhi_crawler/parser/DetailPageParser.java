package com.jason.nhi_crawler.parser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import com.jason.nhi_crawler.model.AttachmentLink;

/**
 * 從「公告詳細頁的 HTML 文字」找出所有附件連結。
 *
 * <p>和 {@link AnnouncementListParser} 一樣只負責「看懂網頁」，不負責下載。
 */
@Component
public class DetailPageParser {

    private static final String ATTACHMENT_SELECTOR = "a[href$=.pdf], a[href$=.odt], a[href*=dl-]";

    /**
     * @param html    詳細頁的完整 HTML
     * @param pageUrl 詳細頁網址，用來把相對連結補成完整網址
     * @return 去除重複網址後的附件連結，保留網頁上的出現順序
     */
    public List<AttachmentLink> extractAttachmentLinks(String html, String pageUrl) {
        List<AttachmentLink> links = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();

        for (Element anchor : Jsoup.parse(html, pageUrl).select(ATTACHMENT_SELECTOR)) {
            String url = anchor.absUrl("href");
            // 同一個檔案在頁面上常出現兩次（圖示一次、文字一次），只留第一個
            if (url.isEmpty() || !seenUrls.add(url)) {
                continue;
            }
            links.add(new AttachmentLink(url, anchor.text()));
        }
        return links;
    }

    /**
     * 判斷拿到的 HTML 是不是 Cloudflare 的「請稍候 / 確認您是人類」驗證頁，而不是真正的內容。
     */
    public boolean isBlockedPage(String html) {
        return html.contains("請稍候")
                || html.contains("Just a moment")
                || html.contains("Attention Required")
                || html.contains("challenges.cloudflare.com");
    }
}
