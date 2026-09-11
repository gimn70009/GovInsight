package com.publicmonitor.backend.domain.document.entity;

import com.publicmonitor.backend.domain.user.entity.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "document_proposal_drafts", uniqueConstraints = @UniqueConstraint(
        name = "uk_proposal_draft_source", columnNames = {"user_id", "attachment_id", "part_index"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentProposalDraft {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "proposal_draft_sequence")
    @SequenceGenerator(name = "proposal_draft_sequence", sequenceName = "document_proposal_drafts_seq", allocationSize = 1)
    @Column(name = "draft_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attachment_id", nullable = false)
    private DocumentAttachment attachment;
    @Column(name = "part_index", nullable = false)
    private int partIndex;
    @Column(name = "result_json", nullable = false, columnDefinition = "CLOB")
    private String resultJson;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "last_viewed_at", nullable = false)
    private LocalDateTime lastViewedAt;

    @Column(name = "previous_result_json", columnDefinition = "CLOB")
    private String previousResultJson;
    @Column(name = "revision", nullable = false)
    private long revision;
    @Column(name = "last_operation_id", length = 36)
    private String lastOperationId;

    public void replace(String nextResult, String operationId, LocalDateTime now) {
        previousResultJson = resultJson;
        resultJson = nextResult;
        revision++;
        lastOperationId = operationId;
        lastViewedAt = now;
    }

    public void restore(String operationId, LocalDateTime now) {
        replace(previousResultJson, operationId, now);
    }

    public static DocumentProposalDraft create(User user, DocumentAttachment attachment, int partIndex,
            String resultJson, LocalDateTime now) {
        var draft = new DocumentProposalDraft();
        draft.user = user;
        draft.attachment = attachment;
        draft.partIndex = partIndex;
        draft.resultJson = resultJson;
        draft.createdAt = now;
        draft.lastViewedAt = now;
        return draft;
    }

    public void markViewed(LocalDateTime now) {
        lastViewedAt = now;
    }
}
