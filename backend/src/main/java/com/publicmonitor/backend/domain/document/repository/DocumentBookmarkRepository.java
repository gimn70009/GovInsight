package com.publicmonitor.backend.domain.document.repository;

import com.publicmonitor.backend.domain.document.entity.DocumentBookmark;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentBookmarkRepository extends JpaRepository<DocumentBookmark, Long> {
    boolean existsByUserIdAndDocumentId(Long userId, Long documentId);
    void deleteByUserIdAndDocumentId(Long userId, Long documentId);
    @Query("select b.document.id from DocumentBookmark b where b.user.id = :userId")
    List<Long> findDocumentIds(Long userId);
}
