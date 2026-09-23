package com.publicmonitor.backend.domain.report.service;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.report.client.dto.PythonReportDocumentRequest;
import java.util.ArrayList;
import java.util.List;

final class ReportAttachmentExcerpts {
    private static final int TOTAL_LIMIT = 120_000;
    private static final int FILE_LIMIT = 40_000;
    private static final String OMITTED = "\n[일부 원문 생략: 파일 뒷부분 미전달]";

    private ReportAttachmentExcerpts() {}

    static List<PythonReportDocumentRequest.Attachment> build(List<DocumentAttachment> attachments) {
        var texts = attachments.stream().map(a -> a.getParseStatus() == AttachmentParseStatus.COMPLETED
                ? a.getExtractedText() : null).toList();
        int[] limits = new int[texts.size()];
        int remaining = TOTAL_LIMIT;
        while (remaining > 0) {
            int active = 0;
            for (int i = 0; i < texts.size(); i++) {
                if (texts.get(i) != null && limits[i] < Math.min(FILE_LIMIT, texts.get(i).length())) active++;
            }
            if (active == 0) break;
            int share = Math.max(1, remaining / active);
            for (int i = 0; i < texts.size() && remaining > 0; i++) {
                if (texts.get(i) == null) continue;
                int amount = Math.min(remaining,
                        Math.min(share, Math.min(FILE_LIMIT, texts.get(i).length()) - limits[i]));
                limits[i] += amount;
                remaining -= amount;
            }
        }
        var result = new ArrayList<PythonReportDocumentRequest.Attachment>();
        for (int i = 0; i < attachments.size(); i++) {
            var attachment = attachments.get(i);
            String text = texts.get(i);
            if (text != null && text.length() > limits[i]) {
                text = limits[i] > OMITTED.length()
                        ? text.substring(0, limits[i] - OMITTED.length()) + OMITTED : null;
            }
            result.add(new PythonReportDocumentRequest.Attachment(
                    attachment.getFileName(), attachment.getDownloadUrl(), text));
        }
        return result;
    }
}
