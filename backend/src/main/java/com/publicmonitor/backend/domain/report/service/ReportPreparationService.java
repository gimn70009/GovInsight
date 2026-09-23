package com.publicmonitor.backend.domain.report.service;

import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.report.entity.ReportTask;
import com.publicmonitor.backend.domain.report.repository.ReportTaskRepository;
import java.time.Clock;
import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.repository.AnalysisDocumentDetectionRepository;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.repository.DocumentAttachmentRepository;
import com.publicmonitor.backend.domain.document.service.PreparationCompatibility;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.report.client.dto.PythonReportDocumentRequest;
import com.publicmonitor.backend.domain.report.client.dto.PythonReportJobRequest;
import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import com.publicmonitor.backend.domain.report.entity.MonitoringReportStatus;
import com.publicmonitor.backend.domain.report.exception.ReportException;
import com.publicmonitor.backend.domain.report.exception.ReportResponseCode;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Lazy
@Service
@RequiredArgsConstructor
public class ReportPreparationService {

    private final MonitoringRunRepository runRepository;
    private final AnalysisDocumentDetectionRepository detectionRepository;
    private final DocumentAnalysisRepository analysisRepository;
    private final MonitoringReportRepository reportRepository;
    private final ObjectMapper objectMapper;
    private final DocumentAttachmentRepository attachmentRepository;
    private final ReportTaskRepository taskRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PythonReportJobRequest> prepare(Long runId) {
        return prepareInTransaction(runId);
    }

    @Transactional
    public void enqueue(Long runId) { prepareInTransaction(runId); }

    private Optional<PythonReportJobRequest> prepareInTransaction(Long runId) {
        MonitoringRun run = runRepository.findForUpdate(runId)
                .orElseThrow(() -> new ReportException(ReportResponseCode.RUN_NOT_FOUND));
        if (run.getStatus() == MonitoringRunStatus.COMPLETED) return Optional.empty();
        if (run.getStatus() != MonitoringRunStatus.COLLECTED) {
            throw new ReportException(ReportResponseCode.INVALID_RUN_STATUS);
        }

        Optional<MonitoringReport> existing = reportRepository.findByMonitoringRunId(runId);
        if (existing.isPresent() && (existing.get().getStatus() == MonitoringReportStatus.COMPLETED
                || taskRepository.existsByReportId(existing.get().getId()))) {
            return Optional.empty();
        }
        MonitoringReport report = existing.orElseGet(() -> reportRepository.save(MonitoringReport.pending(run)));
        if (report.getStatus() == MonitoringReportStatus.FAILED) {
            report.prepareRetry();
        }

        List<DocumentDetection> detections =
                detectionRepository.findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(runId);
        List<Long> versionIds = detections.stream()
                .map(detection -> detection.getDocumentVersion().getId())
                .distinct()
                .toList();
        Map<Long, DocumentAnalysis> analyses = analysisRepository.findAllByDocumentVersionIdIn(versionIds).stream()
                .collect(Collectors.toMap(
                        analysis -> analysis.getDocumentVersion().getId(),
                        Function.identity()
                ));

        var minimum = MinimumReportBuilder.build(run, detections, analyses);
        report.saveMinimum(minimum.title(), minimum.summary());
        var now = java.time.LocalDateTime.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul")));
        PythonReportJobRequest request = null;
        String payload = null;
        String warning = null;
        try {
            var documents = detections.stream()
                    .map(detection -> toRequest(detection, analyses.get(detection.getDocumentVersion().getId())))
                    .toList();
            if (!documents.isEmpty()) {
                request = new PythonReportJobRequest(run.getId(), run.getRequestedAt(),
                        run.getTotalSourceCount(), run.getDetectedDocumentCount(), run.getWarningCount(), documents);
                payload = objectMapper.writeValueAsString(request);
            }
        } catch (RuntimeException exception) {
            warning = "상세 보고서 입력 준비 실패";
            request = null;
        }
        var task = ReportTask.pending(report, payload, now);
        if (payload == null) task.completeGeneration(minimum.title(), minimum.summary(), now, warning);
        taskRepository.save(task);
        return Optional.ofNullable(request);
    }

    private PythonReportDocumentRequest toRequest(
            DocumentDetection detection,
            DocumentAnalysis analysis
    ) {
        return new PythonReportDocumentRequest(
                detection.getId(),
                detection.getDocument().getId(),
                detection.getDocumentVersion().getId(),
                detection.getMonitoringRunSource().getMonitoringSource().getOrganizationName(),
                detection.getMonitoringRunSource().getMonitoringSource().getBoardName(),
                detection.getChangeType(),
                detection.getDocumentVersion().getTitle(),
                detection.getDocumentVersion().getPublishedAt(),
                detection.getDocument().getOriginalUrl(),
                analysis == null ? "분석 결과를 확인하지 못했습니다." : analysis.getSummary(),
                analysis == null ? List.of() : parseKeyPoints(analysis.getKeyPoints()),
                analysis == null ? DocumentImportance.NORMAL : analysis.getImportance(),
                analysis == null ? null : analysis.getReason(),
                analysis == null ? null : analysis.getEligibility(),
                analysis == null ? null : analysis.getOpportunityScore(),
                analysis == null ? null : objectMapper.readTree(PreparationCompatibility.normalize(analysis.getProposalDirection(), objectMapper)),
                excerpt(detection.getDocumentVersion().getContentText(), 80_000),
                analysis == null || analysis.getComparisonSummary() == null ? null : objectMapper.readTree(analysis.getComparisonSummary()),
                reportAttachments(detection.getDocumentVersion().getId())
        );
    }

    private List<PythonReportDocumentRequest.Attachment> reportAttachments(Long versionId) {
        return ReportAttachmentExcerpts.build(attachmentRepository.findAllByDocumentVersionId(versionId));
    }

    private String excerpt(String value, int limit) {
        return value == null || limit <= 0 ? null : value.substring(0, Math.min(value.length(), limit));
    }

    private List<String> parseKeyPoints(String keyPoints) {
        if (keyPoints == null || keyPoints.isBlank()) {
            return List.of();
        }
        return Arrays.asList(objectMapper.readValue(keyPoints, String[].class));
    }
}
