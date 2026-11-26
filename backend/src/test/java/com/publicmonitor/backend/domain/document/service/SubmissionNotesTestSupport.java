package com.publicmonitor.backend.domain.document.service;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public final class SubmissionNotesTestSupport {
    private SubmissionNotesTestSupport() {}
    public static SubmissionNotesService service() {
        var service = mock(SubmissionNotesService.class);
        lenient().when(service.resolve(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));
        return service;
    }
}
