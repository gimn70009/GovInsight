package com.publicmonitor.backend.domain.email.service;

import com.publicmonitor.backend.domain.analysis.repository.AnalysisDocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.service.DocumentDetectionDetailService;
import com.publicmonitor.backend.domain.email.EmailMessageContent;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse;
import com.publicmonitor.backend.domain.report.EmailReviewBrief;
import com.publicmonitor.backend.domain.report.client.PythonEmailReviewClient;
import com.publicmonitor.backend.domain.report.BusinessSummaryEmailTemplate;
import com.publicmonitor.backend.domain.report.BusinessSummaryEmailView;
import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import com.publicmonitor.backend.domain.settings.service.EvaluationSettingsService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Lazy
@Service
@RequiredArgsConstructor
public class EmailReportContentService {
    private final AnalysisDocumentDetectionRepository detections;
    private final DocumentDetectionDetailService details;
    private final EvaluationSettingsService settings;
    private final Clock clock;
    private final PythonEmailReviewClient reviewClient;

    @Transactional(readOnly = true)
    public EmailMessageContent build(MonitoringReport report) {
        var run = report.getMonitoringRun();
        var notices = new ArrayList<BusinessSummaryEmailView>();
        var versions = new HashSet<Long>();
        var savedDetails = new LinkedHashMap<Long, DocumentDetectionDetailResponse>();
        var reviewSources = new ArrayList<EmailReviewBrief.Document>();
        for (var detection : detections.findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(run.getId())) {
            if (!versions.add(detection.getDocumentVersion().getId())) continue;
            var detail = details.findSummaryById(detection.getId());
            long versionId = detection.getDocumentVersion().getId();
            savedDetails.put(versionId, detail);
            var source = EmailReviewBrief.source(versionId, detail);
            if (source != null) reviewSources.add(source);
        }
        var reviews = reviewClient.summarize(reviewSources);
        var sentAt = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        // The settings screen exposes defaults even before the first saved analysis revision.
        Integer preparationDays = settings.find().data().criteria().preparationDays();
        savedDetails.forEach((versionId, detail) -> notices.add(
                BusinessSummaryEmailView.from(detail, sentAt, preparationDays, reviews.get(versionId))));
        // Keep useful opportunities first, with the original detection order for tied scores.
        notices.sort(Comparator.comparing(BusinessSummaryEmailView::score,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return BusinessSummaryEmailTemplate.render(report.getTitle(), notices,
                notices.isEmpty() && run.getDetectedDocumentCount() > 0);
    }
}
