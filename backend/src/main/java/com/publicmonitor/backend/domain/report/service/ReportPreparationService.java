package com.publicmonitor.backend.domain.report.service;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.repository.AnalysisDocumentDetectionRepository;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PythonReportJobRequest> prepare(Long runId) {
        MonitoringRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ReportException(ReportResponseCode.RUN_NOT_FOUND));
        if (run.getStatus() != MonitoringRunStatus.COLLECTED) {
            throw new ReportException(ReportResponseCode.INVALID_RUN_STATUS);
        }

        Optional<MonitoringReport> existing = reportRepository.findByMonitoringRunId(runId);
        if (existing.isPresent() && existing.get().getStatus() != MonitoringReportStatus.FAILED) {
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

        List<PythonReportDocumentRequest> documents = detections.stream()
                .filter(detection -> analyses.containsKey(detection.getDocumentVersion().getId()))
                .map(detection -> toRequest(detection, analyses.get(detection.getDocumentVersion().getId())))
                .toList();
        if (documents.isEmpty()) {
            report.fail("보고서에 사용할 문서 분석 결과가 없습니다.");
            throw new ReportException(ReportResponseCode.ANALYSIS_NOT_FOUND);
        }

        return Optional.of(new PythonReportJobRequest(
                run.getId(),
                run.getRequestedAt(),
                run.getTotalSourceCount(),
                run.getDetectedDocumentCount(),
                run.getWarningCount(),
                documents
        ));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRequest(Long runId, String errorMessage) {
        reportRepository.findByMonitoringRunId(runId)
                .filter(report -> report.getStatus() != MonitoringReportStatus.COMPLETED)
                .ifPresent(report -> report.fail(errorMessage));
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
                analysis.getSummary(),
                parseKeyPoints(analysis.getKeyPoints()),
                analysis.getImportance(),
                analysis.getReason(),
                analysis.getEligibility(),
                analysis.getOpportunityScore(),
                objectMapper.readTree(analysis.getProposalDirection())
        );
    }

    private List<String> parseKeyPoints(String keyPoints) {
        if (keyPoints == null || keyPoints.isBlank()) {
            return List.of();
        }
        return Arrays.asList(objectMapper.readValue(keyPoints, String[].class));
    }
}
