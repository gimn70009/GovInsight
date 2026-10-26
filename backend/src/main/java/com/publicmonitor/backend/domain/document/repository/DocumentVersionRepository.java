package com.publicmonitor.backend.domain.document.repository;

import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update DocumentVersion v set v.participationRewriteKey = :key, v.participationRewriteJson = null where v.id = :id")
    void beginParticipationRewrite(Long id, String key);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update DocumentVersion v set v.participationRewriteJson = :json where v.id = :id and v.participationRewriteKey = :key")
    int finishParticipationRewrite(Long id, String key, String json);

    Optional<DocumentVersion> findTopByDocumentIdOrderByVersionNoDesc(Long documentId);
}
