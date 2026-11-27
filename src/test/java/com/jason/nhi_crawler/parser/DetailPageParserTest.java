package com.jason.nhi_crawler.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.jason.nhi_crawler.model.AttachmentLink;

class DetailPageParserTest {

    private static final String DETAIL_URL = "https://www.nhi.gov.tw/ch/cp-1001-aaaa-3258-1.html";

    private final DetailPageParser parser = new DetailPageParser();
    private final String detailHtml = FixtureLoader.load("announcement-detail.html");

    @Test
    void 找出PDF_ODT與dl下載連結_重複的只留一個_其他連結忽略() {
        List<AttachmentLink> links = parser.extractAttachmentLinks(detailHtml, DETAIL_URL);

        assertEquals(List.of(
                new AttachmentLink("https://www.nhi.gov.tw/Upload/files/notice-1150000001.pdf", "公告函.pdf"),
                new AttachmentLink("https://www.nhi.gov.tw/Upload/files/attachment-table.odt", "附件對照表.odt"),
                new AttachmentLink("https://www.nhi.gov.tw/dl-12345-abcdef.html", "修正總說明")),
                links);
    }

    @Test
    void 能分辨驗證頁和正常頁() {
        assertTrue(parser.isBlockedPage(FixtureLoader.load("cloudflare-challenge.html")));
        assertFalse(parser.isBlockedPage(detailHtml));
    }
}
