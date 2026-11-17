package com.jason.nhi_crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import com.jason.nhi_crawler.model.Announcement;
import com.jason.nhi_crawler.service.EmailService;
import com.jason.nhi_crawler.service.ScraperService;

@SpringBootTest
class NhiCrawlerApplicationTests {

	@Autowired
	private ScraperService scraperService;

	@Autowired
	private EmailService emailService;

	@Value("${spring.mail.username}")
	private String testRecipient;

	@Test
	void contextLoads() {
	}

	@Test
	public void scraperServiceTest() {
		System.out.println("開始測試爬蟲（接管本機 Chrome 9222 port）與 PDF 記憶體載入...");

		List<Announcement> resultList = scraperService.fetchLatestAnnouncement();
		assertFalse(resultList.isEmpty(), "應該至少抓到一筆健保署公告");

		for (Announcement ann : resultList) {
			System.out.println("公告標題: " + ann.getTitle());
			System.out.println("附件網址數: " + ann.getPdfUrls().size());
			assertTrue(ann.getFailedPdfUrls().isEmpty(), "不應有下載失敗的附件: " + ann.getFailedPdfUrls());

			Map<String, byte[]> attachments = ann.getPdfAttachments();
			assertNotNull(attachments, "pdfAttachments 不應為 null");
			System.out.println("已載入記憶體附件數: " + attachments.size());

			for (Map.Entry<String, byte[]> entry : attachments.entrySet()) {
				String fileName = entry.getKey();
				byte[] bytes = entry.getValue();
				System.out.println(" - " + fileName + " (" + bytes.length + " bytes)");

				assertTrue(bytes.length > 0, "附件位元組大小應大於 0: " + fileName);
				if (fileName.toLowerCase().endsWith(".pdf") && bytes.length >= 4) {
					String header = new String(bytes, 0, 4, StandardCharsets.US_ASCII);
					assertEquals("%PDF", header, "PDF 檔頭應為 %PDF: " + fileName);
				}
			}
		}

		System.out.println("爬蟲與 PDF 記憶體測試完成，resultList size = " + resultList.size());
	}

	@Test
	public void scrapeAndSendEmailIntegrationTest() {
		System.out.println("開始整合測試：接管本機 Chrome 爬取公告 + 記憶體 PDF 夾帶寄信...");

		List<Announcement> resultList = scraperService.fetchLatestAnnouncement();
		assertFalse(resultList.isEmpty(), "應該至少抓到一筆健保署公告");

		String subject = "[測試] 健保署最新公告與附件";
		emailService.sendMailWithAnnouncements(testRecipient, subject, resultList);

		System.out.println("整合測試完成，已寄出至: " + testRecipient);
	}
}
