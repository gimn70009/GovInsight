package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.DocumentDetailSnapshot.State;
import com.publicmonitor.backend.domain.document.repository.DocumentDetailSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service @RequiredArgsConstructor
@org.springframework.context.annotation.Lazy
public class DocumentDetailSnapshotReader {
    private final DocumentDetailSnapshotRepository snapshots;
    private final ObjectMapper mapper;
    @Transactional(readOnly = true)
    public DocumentDetailPayload read(Long versionId) {
        var row = snapshots.findById(versionId).orElse(null);
        if (row == null) return DocumentDetailPayload.empty("PENDING");
        if (row.getPayloadJson() == null) return DocumentDetailPayload.empty(row.getState() == State.FAILED ? "UNAVAILABLE" : "PENDING");
        try {
            var payload = mapper.readValue(row.getPayloadJson(), DocumentDetailPayload.class);
            if (payload == null || payload.participationGuide() == null || payload.submissionGuide() == null)
                return DocumentDetailPayload.empty("UNAVAILABLE");
            if (row.getState() == State.FAILED) return payload.failed();
            return row.getState() == State.QUEUED || row.getState() == State.BUILDING ? payload.refreshing() : payload;
        } catch (RuntimeException invalid) { return DocumentDetailPayload.empty("UNAVAILABLE"); }
    }
}
