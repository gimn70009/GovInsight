package com.publicmonitor.backend.domain.analysis.service;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.exception.AnalysisResultException;
import com.publicmonitor.backend.domain.analysis.exception.AnalysisResultResponseCode;
import com.publicmonitor.backend.domain.analysis.repository.AnalysisDocumentDetectionRepository;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.analysis.web.dto.AnalysisResultRequest;
import com.publicmonitor.backend.domain.analysis.web.dto.AnalysisResultResponse;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.report.event.AnalysisStoredEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisResultService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final MonitoringRunRepository monitoringRunRepository;
    private final AnalysisDocumentDetectionRepository detectionRepository;
    private final DocumentAnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public AnalysisResultResponse receive(AnalysisResultRequest request) {
        MonitoringRun run = monitoringRunRepository.findById(request.runId())
                .orElseThrow(() -> new AnalysisResultException(AnalysisResultResponseCode.RUN_NOT_FOUND));
        if (run.getStatus() != MonitoringRunStatus.COLLECTED) {
            throw new AnalysisResultException(AnalysisResultResponseCode.INVALID_RUN_STATUS);
        }

        LocalDateTime analyzedAt = LocalDateTime.now(clock.withZone(SERVICE_ZONE));
        int storedCount = 0;
        int duplicateCount = 0;

        for (AnalysisResultRequest.AnalysisResult result : request.results()) {
            DocumentDetection detection = validatedDetection(
                    request.runId(), result.detectionId(), result.documentId(), result.versionId()
            );
            if (analysisRepository.existsByDocumentVersionId(result.versionId())) {
                duplicateCount++;
                continue;
            }
            analysisRepository.save(DocumentAnalysis.create(
                    detection.getDocumentVersion(),
                    result.summary().strip(),
                    objectMapper.writeValueAsString(result.keyPoints()),
                    result.importance(),
                    result.reason().strip(),
                    result.eligibility(),
                    result.favorableOrNot(),
                    objectMapper.writeValueAsString(result.proposal()),
                    objectMapper.writeValueAsString(result.usedTools()),
                    result.modelName().strip(),
                    analyzedAt
            ));
            storedCount++;
        }

        for (AnalysisResultRequest.AnalysisFailure failure : request.failures()) {
            validatedDetection(
                    request.runId(), failure.detectionId(), failure.documentId(), failure.versionId()
            );
        }

        log.info(
                "AI 분석 결과 저장 완료. runId={} jobId={} storedCount={} duplicateCount={} failedCount={}",
                request.runId(), request.jobId(), storedCount, duplicateCount, request.failures().size()
        );
        eventPublisher.publishEvent(new AnalysisStoredEvent(request.runId()));
        return new AnalysisResultResponse(
                request.runId(), storedCount, duplicateCount, request.failures().size()
        );
    }

    private DocumentDetection validatedDetection(
            Long runId,
            Long detectionId,
            Long documentId,
            Long versionId
    ) {
        DocumentDetection detection = detectionRepository.findById(detectionId)
                .orElseThrow(() -> new AnalysisResultException(AnalysisResultResponseCode.DETECTION_NOT_FOUND));
        boolean matches = detection.getMonitoringRunSource().getMonitoringRun().getId().equals(runId)
                && detection.getDocument().getId().equals(documentId)
                && detection.getDocumentVersion().getId().equals(versionId)
                && detection.getDocumentVersion().getDocument().getId().equals(documentId);
        if (!matches) {
            throw new AnalysisResultException(AnalysisResultResponseCode.RESULT_RELATION_MISMATCH);
        }
        return detection;
    }
}
