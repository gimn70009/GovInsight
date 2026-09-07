package com.publicmonitor.backend.domain.document.service;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.document.repository.*;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.user.entity.User;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DocumentBookmarkServiceTest {
    private final DocumentBookmarkRepository bookmarks = mock(DocumentBookmarkRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final DocumentBookmarkService service = new DocumentBookmarkService(bookmarks, documents,
            mock(DocumentDetectionRepository.class), users, mock(DocumentDetectionQueryService.class));

    @Test void repeatedSaveDoesNotInsertAgain() {
        when(users.findForBookmarkUpdate(1L)).thenReturn(Optional.of(mock(User.class)));
        when(documents.findById(2L)).thenReturn(Optional.of(mock(Document.class)));
        when(bookmarks.existsByUserIdAndDocumentId(1L, 2L)).thenReturn(false, true);
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
}
