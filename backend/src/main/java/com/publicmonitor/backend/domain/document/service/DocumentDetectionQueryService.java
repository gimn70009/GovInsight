package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryResponse;
import com.publicmonitor.backend.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Lazy
@RequiredArgsConstructor
public class DocumentDetectionQueryService {

    private final DocumentDetectionRepository detectionRepository;

    @Transactional(readOnly = true)
    public PageResponse<DocumentDetectionSummaryResponse> findAll(int page, int size) {
        return PageResponse.from(detectionRepository.findSummaries(PageRequest.of(page, size)));
    }
}
