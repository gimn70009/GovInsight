package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.DocumentDetailSnapshot;
import com.publicmonitor.backend.domain.document.repository.*;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service @RequiredArgsConstructor
@org.springframework.context.annotation.Lazy
public class DocumentDetailSnapshotStore {
    public static final String POLICY = "document-detail-view-v1";
    private final DocumentDetailSnapshotRepository snapshots;
    private final DocumentVersionRepository versions;
    private final ObjectMapper mapper;
    private final Clock clock;
    public record Claim(Long versionId, long revision, String token) {}
    LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    @Transactional
    public void enqueueMissing(Long id) {
        if (!snapshots.existsById(id)) snapshots.save(DocumentDetailSnapshot.pending(versions.getReferenceById(id), now()));
    }
    @Transactional
    public Claim claim(Long id) {
        var row = snapshots.findForUpdate(id).orElse(null);
        if (row == null) return null;
        String token = row.claim(now(), POLICY);
        return token == null ? null : new Claim(id, row.getRevision(), token);
    }
    @Transactional
    public boolean complete(Claim claim, DocumentDetailPayload payload) {
        // Paid extractors finish via their own transaction. Refresh once on a lost/expired callback.
        var next = payload.pending() ? now().plusMinutes(3) : LocalDateTime.of(9999, 1, 1, 0, 0);
        if (payload.pending()) {
            var previous = snapshots.findById(claim.versionId()).map(DocumentDetailSnapshot::getPayloadJson).orElse(null);
            if (previous != null) {
                try { payload = payload.preservePendingContent(mapper.readValue(previous, DocumentDetailPayload.class)); }
                catch (RuntimeException invalidPrevious) { /* A corrupt old display must not prevent replacement. */ }
            }
        }
        return snapshots.complete(claim.versionId(), claim.revision(), claim.token(), mapper.writeValueAsString(payload), next) == 1;
    }
    @Transactional
    public void fail(Claim claim) { snapshots.fail(claim.versionId(), claim.revision(), claim.token(), now().plusSeconds(30)); }
}
