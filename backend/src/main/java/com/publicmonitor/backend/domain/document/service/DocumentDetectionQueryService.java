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
        return findAll(page, size, fromDateTime, toDateTime, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentDetectionSummaryResponse> findAll(
            int page,
            int size,
            LocalDateTime fromDateTime,
            LocalDateTime toDateTime,
            Long runId
    ) {
        if (fromDateTime != null && toDateTime != null && fromDateTime.isAfter(toDateTime)) {
            throw new DocumentDetectionException(DocumentDetectionResponseCode.INVALID_DATE_RANGE);
        }
        return PageResponse.from(detectionRepository.findSummaries(
                runId, fromDateTime, toDateTime, PageRequest.of(page, size)
        ).map(this::toResponse));
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
                .collect(Collectors.toMap(StoredDimension::type, StoredDimension::score));
        int totalScore = row.opportunityScore() != null
                ? row.opportunityScore()
                : OpportunityScoreCalculator.calculate(scores);
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

    private record StoredDimension(
            OpportunityDimensionType type,
            Integer score,
            String reason
    ) {
    }
}
