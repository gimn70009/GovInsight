package com.publicmonitor.backend.domain.document.repository;

import com.publicmonitor.backend.domain.document.entity.DocumentProposalDraft;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentProposalDraftRepository extends JpaRepository<DocumentProposalDraft, Long> {
    @Query("""
            select d from DocumentProposalDraft d join fetch d.attachment a
            where d.user.id = :userId and a.documentVersion.id = :versionId
            order by d.lastViewedAt desc, d.id desc
            """)
    List<DocumentProposalDraft> findSaved(Long userId, Long versionId);

    @Query("""
            select d from DocumentProposalDraft d join fetch d.attachment a
            where d.user.id = :userId and a.documentVersion.id = :versionId
                and a.id = :attachmentId and d.partIndex = :partIndex
            """)
    Optional<DocumentProposalDraft> findSavedSource(Long userId, Long versionId, Long attachmentId, int partIndex);
}
