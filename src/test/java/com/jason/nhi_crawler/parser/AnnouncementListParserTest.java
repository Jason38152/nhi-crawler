package com.jason.nhi_crawler.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.jason.nhi_crawler.model.AnnouncementRow;

/**
 * 純單元測試：不啟動 Spring、不開 Chrome、不上網，只餵 HTML 檔案給 parser 看結果。
 * 整個測試類別通常在一秒內跑完。
 */
class AnnouncementListParserTest {

    private static final String LIST_URL = "https://www.nhi.gov.tw/ch/lp-3258-1.html";

    private final AnnouncementListParser parser = new AnnouncementListParser();
    private final String listHtml = FixtureLoader.load("announcement-list.html");

    @Test
    void 沒指定日期時_只抓最新刊登日期的公告() {
        List<AnnouncementRow> rows = parser.parse(listHtml, LIST_URL, null);

        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(r -> r.publishDate().equals("115-11-13")));
    }

    @Test
    void 指定日期時_只抓那一天的公告() {
        List<AnnouncementRow> rows = parser.parse(listHtml, LIST_URL, "115-11-11");

        assertEquals(1, rows.size());
        assertEquals("健保企字第1150000003號", rows.get(0).referenceNo());
    }

    @Test
    void 指定的日期沒有公告時_回傳空清單而不是丟例外() {
        List<AnnouncementRow> rows = parser.parse(listHtml, LIST_URL, "115-01-01");

        assertTrue(rows.isEmpty());
    }

    @Test
    void 每個欄位都正確讀出_相對連結會補成完整網址() {
        AnnouncementRow first = parser.parse(listHtml, LIST_URL, null).get(0);

        AnnouncementRow expected = new AnnouncementRow(
                "公告修正「全民健康保險藥物給付項目及支付標準」部分規定",
                "https://www.nhi.gov.tw/ch/cp-1001-aaaa-3258-1.html",
                "健保審字第1150000001號",
                "115-11-12",
                "115-11-13",
                "115-12-13");
        // record 自動產生 equals，所以可以一次比對整筆資料
        assertEquals(expected, first);
    }

    @Test
    void 拿到的是Cloudflare驗證頁時_丟出明確的錯誤() {
        String challengeHtml = FixtureLoader.load("cloudflare-challenge.html");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> parser.parse(challengeHtml, LIST_URL, null));
        assertTrue(e.getMessage().contains("未找到公告連結"));
    }
}
