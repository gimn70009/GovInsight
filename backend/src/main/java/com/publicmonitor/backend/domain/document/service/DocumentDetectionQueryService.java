package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionResponseCode;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryResponse;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryRow;
import com.publicmonitor.backend.global.response.PageResponse;
import java.time.LocalDateTime;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Lazy
@RequiredArgsConstructor
public class DocumentDetectionQueryService {

    private final DocumentDetectionRepository detectionRepository;

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
        return PageResponse.from(detectionRepository.findFilteredSummaries(
                runId, fromDateTime, toDateTime, pattern, priority,
                sort == DocumentDetectionSort.OPPORTUNITY_SCORE, pageRequest).map(this::toResponse));
    }

    static String searchPattern(String query) {
        if (query == null || query.isBlank()) return null;
        return "%" + query.strip().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    DocumentDetectionSummaryResponse toResponse(DocumentDetectionSummaryRow row) {
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
                row.opportunityScore(),
                row.opportunityPriority(),
                row.lastCheckedAt()
        );
    }
}
