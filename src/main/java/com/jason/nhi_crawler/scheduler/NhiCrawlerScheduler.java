package com.jason.nhi_crawler.scheduler;

import java.time.ZoneId;
import java.time.chrono.MinguoDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.jason.nhi_crawler.model.Announcement;
import com.jason.nhi_crawler.service.EmailService;
import com.jason.nhi_crawler.service.ScraperService;

@Component
public class NhiCrawlerScheduler {

    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter ROC_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyy-MM-dd");

    private final ScraperService scraperService;
    private final EmailService emailService;
    private final String recipient;

    public NhiCrawlerScheduler(
            ScraperService scraperService,
            EmailService emailService,
            @Value("${nhi.mail.recipient:${spring.mail.username}}") String recipient) {
        this.scraperService = scraperService;
        this.emailService = emailService;
        this.recipient = recipient;
    }

    /**
     * 每天台灣時間早上 08:00 自動執行健保署最新公告爬取與寄信。
     */
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Taipei")
    public void runDailyNhiCheck() {
        String todayRoc = MinguoDate.now(TAIPEI_ZONE).format(ROC_DATE_FORMATTER);
        System.out.println("排程啟動：檢查健保署今日 (" + todayRoc + ") 法規公告...");

        try {
            List<Announcement> announcements = scraperService.fetchAnnouncementsByDate(todayRoc);
            if (announcements.isEmpty()) {
                System.out.println("今日 (" + todayRoc + ") 尚無新公告，略過寄信。");
                return;
            }

            String subject = "【健保署公告通知】" + todayRoc + " 最新法規公告（共 " + announcements.size() + " 筆）";
            emailService.sendMailWithAnnouncements(recipient, subject, announcements);
        } catch (Exception e) {
            System.err.println("健保署爬蟲排程執行失敗，準備寄送系統告警信: " + e.getMessage());
            try {
                emailService.sendFailureAlert(recipient, e);
            } catch (Exception alertEx) {
                System.err.println("寄送系統告警信失敗: " + alertEx.getMessage());
            }
        }
    }
}
