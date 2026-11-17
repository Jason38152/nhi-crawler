package com.jason.nhi_crawler.service;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.jason.nhi_crawler.model.Announcement;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {

    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JavaMailSender mailSender;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendMail(String to, String subject, String text) {
        sendMail(to, subject, text, Map.of());
    }

    public void sendFailureAlert(String to, Throwable error) {
        String now = ZonedDateTime.now(TAIPEI_ZONE).format(TIME_FORMATTER);
        String errorMessage = error != null && error.getMessage() != null ? error.getMessage() : "未知錯誤";

        String subject = "【系統告警】健保署爬蟲執行失敗 - 請檢查 Chrome 驗證狀態";
        String body = """
                健保署公告爬蟲排程執行失敗。

                發生時間: %s (Asia/Taipei)
                錯誤原因: %s

                建議排查步驟：
                1. 確認本機已開啟帶有 --remote-debugging-port=9222 的 Chrome 視窗。
                2. 若 Cloudflare「確認您是人類」憑證已過期，請在該 Chrome 視窗手動點選通過一次後再重新執行。
                """.formatted(now, errorMessage);

        sendMail(to, subject, body);
    }

    public void sendMailWithAnnouncements(String to, String subject, List<Announcement> announcements) {
        if (announcements == null || announcements.isEmpty()) {
            throw new IllegalArgumentException("公告清單為空，取消寄信。");
        }

        // 逐筆檢查每一則公告的附件完整性，只要有任一附件失敗就中止寄信
        List<String> allFailedUrls = new ArrayList<>();
        Map<String, byte[]> allAttachments = new LinkedHashMap<>();

        for (Announcement announcement : announcements) {
            if (announcement.getFailedPdfUrls() != null && !announcement.getFailedPdfUrls().isEmpty()) {
                allFailedUrls.addAll(announcement.getFailedPdfUrls());
            }

            Map<String, byte[]> annAttachments = announcement.getPdfAttachments();
            if (announcement.getPdfUrls() != null && !announcement.getPdfUrls().isEmpty()
                    && (annAttachments == null || annAttachments.isEmpty())) {
                throw new IllegalStateException("公告「" + announcement.getTitle() + "」有附件連結但未成功下載任何檔案，中止寄信。");
            }

            if (annAttachments != null) {
                for (Map.Entry<String, byte[]> entry : annAttachments.entrySet()) {
                    String uniqueName = ensureUniqueFileName(entry.getKey(), allAttachments);
                    allAttachments.put(uniqueName, entry.getValue());
                }
            }
        }

        if (!allFailedUrls.isEmpty()) {
            throw new IllegalStateException("以下附件下載失敗，為確保附件完整已中止寄信: " + allFailedUrls);
        }

        String body = formatToText(announcements);
        sendMail(to, subject, body, allAttachments);
    }

    public void sendMail(String to, String subject, String text, Map<String, byte[]> attachments) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, false);

            if (attachments != null) {
                for (Map.Entry<String, byte[]> entry : attachments.entrySet()) {
                    String fileName = entry.getKey();
                    byte[] fileBytes = entry.getValue();
                    if (fileBytes != null && fileBytes.length > 0) {
                        String contentType = resolveContentType(fileName);
                        helper.addAttachment(fileName, new ByteArrayResource(fileBytes), contentType);
                    }
                }
            }

            mailSender.send(message);
            int attachmentCount = attachments != null ? attachments.size() : 0;
            System.out.println("信件已寄出給 " + to + "（夾帶附件數：" + attachmentCount + "）");
        } catch (MessagingException e) {
            throw new RuntimeException("建立或寄送郵件失敗: " + e.getMessage(), e);
        }
    }

    public String formatToText(List<Announcement> announcements) {
        StringBuilder sb = new StringBuilder();
        sb.append("今日最新健保署公告\n\n");

        for (int i = 0; i < announcements.size(); i++) {
            Announcement ann = announcements.get(i);
            int attachmentCount = ann.getPdfAttachments() != null ? ann.getPdfAttachments().size() : 0;

            sb.append(i + 1).append(". 標題(Title): ").append(ann.getTitle()).append("\n");
            if (ann.getReferenceNo() != null && !ann.getReferenceNo().isBlank()) {
                sb.append("   發文字號: ").append(ann.getReferenceNo()).append("\n");
            }
            sb.append("   刊登日期(Date): ").append(ann.getPublishDate()).append("\n");
            sb.append("   公告連結(Link): ").append(ann.getUrl()).append("\n");
            sb.append("   夾帶附件數: ").append(attachmentCount).append("\n\n");
        }

        return sb.toString();
    }

    private String resolveContentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".odt")) {
            return "application/vnd.oasis.opendocument.text";
        }
        return "application/octet-stream";
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
