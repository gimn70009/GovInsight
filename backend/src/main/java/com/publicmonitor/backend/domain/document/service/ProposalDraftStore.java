package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.DocumentProposalDraft;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionResponseCode;
import com.publicmonitor.backend.domain.document.repository.*;
import com.publicmonitor.backend.domain.document.web.dto.*;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Lazy
@RequiredArgsConstructor
public class ProposalDraftStore {
    private final DocumentProposalDraftRepository drafts;
    private final DocumentDetectionRepository detections;
    private final DocumentAttachmentRepository attachments;
    private final UserRepository users;
    private final ObjectMapper mapper;
    private final ProposalSourceService sources;
    private final jakarta.persistence.EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<SavedProposalDraftResponse> list(Long userId, Long detectionId) {
        var excluded = sources.excludedFromDrafting(detectionId);
        return drafts.findSaved(userId, versionId(detectionId)).stream()
                .filter(draft -> !excluded.containsKey(new ProposalWriteRequest(
                        draft.getAttachment().getId(), draft.getPartIndex())))
                .map(this::response).toList();
    }

    @Transactional
    public Optional<ProposalWriteResponse> reuse(Long userId, Long detectionId, ProposalWriteRequest request) {
        users.findForProposalUpdate(userId).orElseThrow();
        return find(userId, detectionId, request).map(draft -> {
            draft.markViewed(LocalDateTime.now());
            return result(draft);
        });
    }

    @Transactional
    public void markViewed(Long userId, Long detectionId, ProposalWriteRequest request) {
        users.findForProposalUpdate(userId).orElseThrow();
        find(userId, detectionId, request).orElseThrow(() -> new DocumentDetectionException(
                DocumentDetectionResponseCode.PROPOSAL_DRAFT_NOT_FOUND)).markViewed(LocalDateTime.now());
    }

    @Transactional
    public ProposalWriteResponse save(Long userId, Long detectionId, ProposalWriteRequest request,
            ProposalWriteResponse result) {
        if (!"COMPLETED".equals(result.status()) || result.sections() == null || result.sections().isEmpty()) {
            throw new IllegalArgumentException("Only completed proposal drafts can be saved");
        }
        // The account lock covers only persistence, never the model call.
        var user = users.findForProposalUpdate(userId).orElseThrow();
        Long versionId = versionId(detectionId);
        var existing = drafts.findSavedSource(userId, versionId, request.attachmentId(), request.partIndex());
        if (existing.isPresent()) {
            existing.get().markViewed(LocalDateTime.now());
            return result(existing.get());
        }
        var attachment = attachments.findById(request.attachmentId())
                .filter(file -> file.getDocumentVersion().getId().equals(versionId))
                .orElseThrow(() -> new DocumentDetectionException(DocumentDetectionResponseCode.PROPOSAL_SOURCE_NOT_FOUND));
        drafts.save(DocumentProposalDraft.create(user, attachment, request.partIndex(),
                mapper.writeValueAsString(result), LocalDateTime.now()));
        return result;
    }

    @Transactional(readOnly = true)
    public SavedProposalDraftResponse beforeChange(Long userId, Long detectionId, ProposalWriteRequest request,
            long expectedRevision, String operationId) {
        var draft = required(userId, detectionId, request);
        checkRevision(draft, expectedRevision, operationId);
        return response(draft);
    }

    @Transactional
    public ProposalWriteResponse replace(Long userId, Long detectionId, ProposalWriteRequest request,
            long expectedRevision, String operationId, ProposalWriteResponse next) {
        if (!"COMPLETED".equals(next.status()) || next.sections() == null || next.sections().isEmpty()) {
            throw new IllegalArgumentException("Only completed drafts can replace a saved draft");
        }
        users.findForProposalUpdate(userId).orElseThrow();
        var draft = required(userId, detectionId, request);
        // Reload after the account lock; OSIV may still hold the pre-generation entity.
        entityManager.refresh(draft);
        // The snapshot transaction can leave this OSIV entity marked read-only.
        entityManager.unwrap(org.hibernate.Session.class).setReadOnly(draft, false);
        checkRevision(draft, expectedRevision, operationId);
        if (!operationId.equals(draft.getLastOperationId())) {
            draft.replace(mapper.writeValueAsString(next), operationId, LocalDateTime.now());
        }
        return result(draft);
    }

    @Transactional
    public ProposalWriteResponse restore(Long userId, Long detectionId, ProposalWriteRequest request,
            long expectedRevision, String operationId) {
        users.findForProposalUpdate(userId).orElseThrow();
        var draft = required(userId, detectionId, request);
        // Reload after the account lock; OSIV may still hold the pre-generation entity.
        entityManager.refresh(draft);
        // The snapshot transaction can leave this OSIV entity marked read-only.
        entityManager.unwrap(org.hibernate.Session.class).setReadOnly(draft, false);
        checkRevision(draft, expectedRevision, operationId);
        if (operationId.equals(draft.getLastOperationId())) return result(draft);
        if (draft.getPreviousResultJson() == null) {
            throw new DocumentDetectionException(DocumentDetectionResponseCode.PROPOSAL_PREVIOUS_NOT_FOUND);
        }
        draft.restore(operationId, LocalDateTime.now());
        return result(draft);
    }

    private DocumentProposalDraft required(Long userId, Long detectionId, ProposalWriteRequest request) {
        return find(userId, detectionId, request).orElseThrow(() ->
                new DocumentDetectionException(DocumentDetectionResponseCode.PROPOSAL_DRAFT_NOT_FOUND));
    }

    private void checkRevision(DocumentProposalDraft draft, long expectedRevision, String operationId) {
        if (!operationId.equals(draft.getLastOperationId()) && draft.getRevision() != expectedRevision) {
            throw new DocumentDetectionException(DocumentDetectionResponseCode.PROPOSAL_DRAFT_CONFLICT);
        }
    }

    private Optional<DocumentProposalDraft> find(Long userId, Long detectionId, ProposalWriteRequest request) {
        return drafts.findSavedSource(userId, versionId(detectionId), request.attachmentId(), request.partIndex());
    }

    @Transactional(readOnly = true)
    public Long getVersionId(Long detectionId) {
        return versionId(detectionId);
    }

    private Long versionId(Long detectionId) {
        return detections.findById(detectionId).orElseThrow(() ->
                new DocumentDetectionException(DocumentDetectionResponseCode.NOT_FOUND)).getDocumentVersion().getId();
    }

    private ProposalWriteResponse result(DocumentProposalDraft draft) {
        return mapper.readValue(draft.getResultJson(), ProposalWriteResponse.class);
    }

    private SavedProposalDraftResponse response(DocumentProposalDraft draft) {
        return new SavedProposalDraftResponse(draft.getAttachment().getId(), draft.getPartIndex(),
                draft.getAttachment().getFileName(), draft.getCreatedAt(), draft.getLastViewedAt(), result(draft),
                draft.getRevision(), draft.getPreviousResultJson() != null, draft.getLastOperationId());
    }
}
