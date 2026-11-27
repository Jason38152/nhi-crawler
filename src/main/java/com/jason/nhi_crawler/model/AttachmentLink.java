package com.jason.nhi_crawler.model;

/**
 * 詳細頁上的一個附件連結：完整網址與連結上顯示的文字（之後用來當檔名的備案）。
 */
public record AttachmentLink(String url, String text) {
}
