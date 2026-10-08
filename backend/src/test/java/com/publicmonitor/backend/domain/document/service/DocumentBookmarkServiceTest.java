package com.publicmonitor.backend.domain.document.service;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryRow;
import com.publicmonitor.backend.domain.document.repository.*;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.user.entity.User;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.Test;

class DocumentBookmarkServiceTest {
    private final DocumentBookmarkRepository bookmarks = mock(DocumentBookmarkRepository.class);
    private final DocumentVersionRepository documents = mock(DocumentVersionRepository.class);
    private final DocumentDetectionRepository detections = mock(DocumentDetectionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final DocumentBookmarkService service = new DocumentBookmarkService(bookmarks, documents,
            detections, users, new DocumentDetectionQueryService(detections));

    @Test void repeatedSaveDoesNotInsertAgain() {
        when(users.findForBookmarkUpdate(1L)).thenReturn(Optional.of(mock(User.class)));
        when(documents.findById(2L)).thenReturn(Optional.of(mock(DocumentVersion.class)));
        when(bookmarks.existsByUserIdAndVersionId(1L, 2L)).thenReturn(false, true);
        service.set(1L, 2L, true);
        service.set(1L, 2L, true);
        verify(bookmarks, times(1)).save(any(DocumentBookmark.class));
    }
    @Test void unknownDocumentCannotBeSaved() {
        when(users.findForBookmarkUpdate(1L)).thenReturn(Optional.of(mock(User.class)));
        when(documents.findById(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.set(1L, 2L, true)).isInstanceOf(DocumentDetectionException.class);
        verifyNoInteractions(bookmarks);
    }

    @Test void filteredBookmarksRequestOnlyTheRequestedDatabasePage() {
        var from = LocalDateTime.of(2026, 10, 1, 0, 0);
        var to = from.plusDays(1);
        var page = PageRequest.of(2, 1);
        var item = new DocumentDetectionSummaryRow(3L, 15L, 8L, 10L, "기관", "게시판",
                "50%_AI!", DocumentChangeType.NEW_DOCUMENT, 0, null, 80, OpportunityPriority.HIGH, from);
        when(detections.findFilteredBookmarkedSummaries(7L, null, from, to, "%50!%!_ai!!%",
                OpportunityPriority.HIGH, true, page))
                .thenReturn(new PageImpl<>(List.of(item), page, 8));

        var response = service.findAll(7L, 2, 1, from, to, DocumentDetectionSort.OPPORTUNITY_SCORE,
                " 50%_AI! ", OpportunityPriority.HIGH);

        assertThat(response.totalElements()).isEqualTo(8);
        assertThat(response.totalPages()).isEqualTo(8);
        assertThat(response.content()).singleElement().satisfies(row -> {
            assertThat(row.versionId()).isEqualTo(10L);
            assertThat(row.opportunityScore()).isEqualTo(80);
            assertThat(row.opportunityPriority()).isEqualTo(OpportunityPriority.HIGH);
        });
        verify(detections).findFilteredBookmarkedSummaries(7L, null, from, to, "%50!%!_ai!!%",
                OpportunityPriority.HIGH, true, page);
        verifyNoMoreInteractions(detections);
    }
}
