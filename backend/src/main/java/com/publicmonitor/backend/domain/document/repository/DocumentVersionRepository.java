package com.publicmonitor.backend.domain.document.repository;

import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update DocumentVersion v set v.participationRewriteKey = :key, v.participationRewriteJson = :json where v.id = :id and (v.participationRewriteKey = :previousKey or (v.participationRewriteKey is null and :previousKey is null)) and (v.participationRewriteKey is null or v.participationRewriteKey <> :key)")
    int claimParticipationRewrite(Long id, String key, String previousKey, String json);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update DocumentVersion v set v.participationRewriteJson = :json where v.id = :id and v.participationRewriteKey = :key")
    int finishParticipationRewrite(Long id, String key, String json);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update DocumentVersion v set v.submissionNotesKey = :key, v.submissionNotesJson = :json where v.id = :id and (v.submissionNotesKey = :previousKey or (v.submissionNotesKey is null and :previousKey is null))")
    int claimSubmissionNotes(Long id, String key, String previousKey, String json);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update DocumentVersion v set v.submissionNotesJson = :json where v.id = :id and v.submissionNotesKey = :key")
    int finishSubmissionNotes(Long id, String key, String json);

    Optional<DocumentVersion> findTopByDocumentIdOrderByVersionNoDesc(Long documentId);
}
