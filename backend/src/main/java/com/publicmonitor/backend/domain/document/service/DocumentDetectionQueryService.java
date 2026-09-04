package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.analysis.entity.OpportunityDimensionType;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import com.publicmonitor.backend.domain.analysis.service.OpportunityScoreCalculator;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionResponseCode;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryResponse;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryRow;
import com.publicmonitor.backend.global.response.PageResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Lazy
@RequiredArgsConstructor
public class DocumentDetectionQueryService {

    private final DocumentDetectionRepository detectionRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public PageResponse<DocumentDetectionSummaryResponse> findAll(
            int page, int size, LocalDateTime fromDateTime, LocalDateTime toDateTime
    ) {
        return findAll(page, size, fromDateTime, toDateTime, null, DocumentDetectionSort.LATEST);
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentDetectionSummaryResponse> findAll(
            int page,
            int size,
            LocalDateTime fromDateTime,
            LocalDateTime toDateTime,
            Long runId
    ) {
        return findAll(page, size, fromDateTime, toDateTime, runId, DocumentDetectionSort.LATEST);
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentDetectionSummaryResponse> findAll(
            int page,
            int size,
            LocalDateTime fromDateTime,
            LocalDateTime toDateTime,
            Long runId,
            DocumentDetectionSort sort
    ) {
        if (fromDateTime != null && toDateTime != null && fromDateTime.isAfter(toDateTime)) {
            throw new DocumentDetectionException(DocumentDetectionResponseCode.INVALID_DATE_RANGE);
        }
        PageRequest pageRequest = PageRequest.of(page, size);
        var summaries = sort == DocumentDetectionSort.OPPORTUNITY_SCORE
                ? detectionRepository.findSummariesOrderByOpportunityScore(runId, fromDateTime, toDateTime, pageRequest)
                : detectionRepository.findSummaries(runId, fromDateTime, toDateTime, pageRequest);
        return PageResponse.from(summaries.map(this::toResponse));
    }

    private DocumentDetectionSummaryResponse toResponse(DocumentDetectionSummaryRow row) {
        OpportunitySummary opportunity = opportunity(row);
        return new DocumentDetectionSummaryResponse(
                row.runId(),
                row.detectionId(),
                row.documentId(),
                row.versionId(),
                row.organizationName(),
                row.boardName(),
                row.title(),
                row.changeType(),
                row.attachmentCount(),
                row.importance(),
                opportunity.totalScore(),
                opportunity.priority(),
                row.lastCheckedAt()
        );
    }
    private OpportunitySummary opportunity(DocumentDetectionSummaryRow row) {
        if (row.opportunityAssessment() == null || row.opportunityAssessment().isBlank()) {
            return new OpportunitySummary(row.opportunityScore(), null);
        }
        StoredOpportunity stored = objectMapper.readValue(
                row.opportunityAssessment(),
                StoredOpportunity.class
        );
        Map<OpportunityDimensionType, Integer> scores = stored.dimensions().stream()
                .map(this::toScore)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(DimensionScore::type, DimensionScore::score));
        int totalScore = OpportunityScoreCalculator.calculate(scores);
        OpportunityPriority priority = OpportunityScoreCalculator.priority(
                totalScore,
                scores.getOrDefault(OpportunityDimensionType.COMPANY_FIT, 0),
                scores.getOrDefault(OpportunityDimensionType.FEASIBILITY, 0),
                scores.getOrDefault(OpportunityDimensionType.URGENCY, 0)
        );
        return new OpportunitySummary(totalScore, priority);
    }

    private record OpportunitySummary(Integer totalScore, OpportunityPriority priority) {
    }

    private record StoredOpportunity(List<StoredDimension> dimensions) {
    }

    private DimensionScore toScore(StoredDimension stored) {
        try {
            return new DimensionScore(
                    OpportunityDimensionType.valueOf(stored.type()),
                    stored.score()
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record DimensionScore(OpportunityDimensionType type, Integer score) {
    }

    private record StoredDimension(String type, Integer score, String reason) {
    }
}
