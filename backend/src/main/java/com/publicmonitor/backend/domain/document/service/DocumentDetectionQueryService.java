package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionResponseCode;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryResponse;
import com.publicmonitor.backend.global.response.PageResponse;
import java.time.LocalDateTime;
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
        if (fromDateTime != null && toDateTime != null && fromDateTime.isAfter(toDateTime)) {
            throw new DocumentDetectionException(DocumentDetectionResponseCode.INVALID_DATE_RANGE);
        }
        return PageResponse.from(detectionRepository.findSummaries(
                fromDateTime, toDateTime, PageRequest.of(page, size)
        ));
    }
}
