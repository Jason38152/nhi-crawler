package com.jason.nhi_crawler.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Announcement {
    private String title;
    private String url;
    private String referenceNo;
    private String issueDate;
    private String publishDate;
    private String listingDeadline;

    private List<String> pdfUrls;
    private Map<String, byte[]> pdfAttachments;
    private List<String> failedPdfUrls;

    public Announcement(
            String title,
            String url,
            String referenceNo,
            String issueDate,
            String publishDate,
            String listingDeadline,
            List<String> pdfUrls) {
        this(title, url, referenceNo, issueDate, publishDate, listingDeadline, pdfUrls,
                Collections.emptyMap(), Collections.emptyList());
    }

    public Announcement(
            String title,
            String url,
            String referenceNo,
            String issueDate,
            String publishDate,
            String listingDeadline,
            List<String> pdfUrls,
            Map<String, byte[]> pdfAttachments,
            List<String> failedPdfUrls) {
        this.title = title;
        this.url = url;
        this.referenceNo = referenceNo;
        this.issueDate = issueDate;
        this.publishDate = publishDate;
        this.listingDeadline = listingDeadline;
        this.pdfUrls = pdfUrls != null ? List.copyOf(pdfUrls) : Collections.emptyList();
        this.pdfAttachments = pdfAttachments != null
                ? new LinkedHashMap<>(pdfAttachments)
                : Collections.emptyMap();
        this.failedPdfUrls = failedPdfUrls != null ? List.copyOf(failedPdfUrls) : Collections.emptyList();
    }

    public String getTitle() {
        return this.title;
    }

    public String getUrl() {
        return this.url;
    }

    public String getReferenceNo() {
        return this.referenceNo;
    }

    public String getIssueDate() {
        return this.issueDate;
    }

    public String getPublishDate() {
        return this.publishDate;
    }

    public String getListingDeadline() {
        return this.listingDeadline;
    }

    public List<String> getPdfUrls() {
        return this.pdfUrls;
    }

    public Map<String, byte[]> getPdfAttachments() {
        return this.pdfAttachments;
    }

    public List<String> getFailedPdfUrls() {
        return this.failedPdfUrls;
    }
}
