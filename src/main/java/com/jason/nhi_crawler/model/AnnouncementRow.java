package com.jason.nhi_crawler.model;

/**
 * 公告列表頁上「一列」的文字資料（還沒有下載附件）。
 *
 * <p>為什麼用 record？這種只裝資料、建立後不會再改的物件，
 * 用 record 一行就能宣告，Java 會自動產生建構子、getter、equals 與 toString。
 * equals 讓測試可以直接比較「整筆資料是否相同」。
 */
public record AnnouncementRow(
        String title,
        String link,
        String referenceNo,
        String issueDate,
        String publishDate,
        String listingDeadline) {
}
