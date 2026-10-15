package com.publicmonitor.backend.domain.email.service;

import com.publicmonitor.backend.domain.analysis.repository.AnalysisDocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.service.DocumentDetectionDetailService;
import com.publicmonitor.backend.domain.email.EmailMessageContent;
import com.publicmonitor.backend.domain.report.BusinessSummaryEmailTemplate;
import com.publicmonitor.backend.domain.report.BusinessSummaryEmailView;
import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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

    @Transactional(readOnly = true)
    public EmailMessageContent build(MonitoringReport report) {
        var run = report.getMonitoringRun();
        var notices = new ArrayList<BusinessSummaryEmailView>();
        var versions = new HashSet<Long>();
        for (var detection : detections.findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(run.getId())) {
            if (!versions.add(detection.getDocumentVersion().getId())) continue;
            notices.add(BusinessSummaryEmailView.from(details.findById(detection.getId())));
        }
        // Keep useful opportunities first, with the original detection order for tied scores.
        notices.sort(Comparator.comparing(BusinessSummaryEmailView::score,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return BusinessSummaryEmailTemplate.render(report.getTitle(), notices,
                notices.isEmpty() && run.getDetectedDocumentCount() > 0);
    }
}
