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
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
        return findAll(page, size, fromDateTime, toDateTime, runId, sort, null, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentDetectionSummaryResponse> findAll(
            int page, int size, LocalDateTime fromDateTime, LocalDateTime toDateTime,
            Long runId, DocumentDetectionSort sort, String query, OpportunityPriority priority
    ) {
        if (fromDateTime != null && toDateTime != null && fromDateTime.isAfter(toDateTime)) {
            throw new DocumentDetectionException(DocumentDetectionResponseCode.INVALID_DATE_RANGE);
        }
        PageRequest pageRequest = PageRequest.of(page, size);
        String pattern = searchPattern(query);
        return filteredPage(pageRequest, priority, batch -> detectionRepository.findFilteredSummaries(
                runId, fromDateTime, toDateTime, pattern, sort == DocumentDetectionSort.OPPORTUNITY_SCORE, batch));
    }

    static String searchPattern(String query) {
        if (query == null || query.isBlank()) return null;
        return "%" + query.strip().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    PageResponse<DocumentDetectionSummaryResponse> filteredPage(
            PageRequest requestedPage, OpportunityPriority priority,
            Function<Pageable, Page<DocumentDetectionSummaryRow>> fetch
    ) {
        if (priority == null) {
            return PageResponse.from(fetch.apply(requestedPage).map(this::toResponse));
        }
        // 우선순위는 CLOB의 세부 점수로 계산한다. 모든 후보를 제한된 묶음으로 읽고
        // 계산·필터 후 페이지를 잘라 기존 판정 기준과 정확한 전체 건수를 유지한다.
        var content = new ArrayList<DocumentDetectionSummaryResponse>();
        long total = 0;
        Pageable batch = PageRequest.of(0, 200);
        while (true) {
            var rows = fetch.apply(batch);
            for (var row : rows) {
                var response = toResponse(row);
                if (response.opportunityPriority() != priority) continue;
                if (total >= requestedPage.getOffset() && content.size() < requestedPage.getPageSize()) {
                    content.add(response);
                }
                total++;
            }
            if (!rows.hasNext()) break;
            batch = rows.nextPageable();
        }
        return PageResponse.from(new PageImpl<>(content, requestedPage, total));
    }

    DocumentDetectionSummaryResponse toResponse(DocumentDetectionSummaryRow row) {
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
