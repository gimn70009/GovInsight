package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.DocumentBookmark;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionResponseCode;
import com.publicmonitor.backend.domain.document.repository.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryResponse;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import com.publicmonitor.backend.global.response.PageResponse;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Lazy
@RequiredArgsConstructor
public class DocumentBookmarkService {
    private final DocumentBookmarkRepository bookmarks;
    private final DocumentVersionRepository versions;
    private final DocumentDetectionRepository detections;
    private final UserRepository users;
    private final DocumentDetectionQueryService queryService;

    @Transactional(readOnly = true)
    public List<Long> ids(Long userId) {
        return bookmarks.findVersionIds(userId);
    }

    @Transactional
    public void set(Long userId, Long versionId, boolean saved) {
        // Serialize writes per account so repeated PUTs remain idempotent.
        var user = users.findForBookmarkUpdate(userId).orElseThrow();
        var version = versions.findById(versionId).orElseThrow(
                () -> new DocumentDetectionException(DocumentDetectionResponseCode.NOT_FOUND));
        if (!saved) {
            bookmarks.deleteByUserIdAndVersionId(userId, versionId);
        } else if (!bookmarks.existsByUserIdAndVersionId(userId, versionId)) {
            bookmarks.save(DocumentBookmark.create(user, version));
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentDetectionSummaryResponse> findAll(
            Long userId, int page, int size, LocalDateTime from, LocalDateTime to,
            DocumentDetectionSort sort
    ) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new DocumentDetectionException(DocumentDetectionResponseCode.INVALID_DATE_RANGE);
        }
        var rows = detections.findBookmarkedSummaries(userId, null, from, to,
                sort == DocumentDetectionSort.OPPORTUNITY_SCORE, PageRequest.of(page, size));
        return PageResponse.from(rows.map(queryService::toResponse));
    }
}
