package com.publicmonitor.backend.domain.document.repository;

import com.publicmonitor.backend.domain.document.entity.DocumentBookmark;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentBookmarkRepository extends JpaRepository<DocumentBookmark, Long> {
    boolean existsByUserIdAndVersionId(Long userId, Long versionId);
    void deleteByUserIdAndVersionId(Long userId, Long versionId);
    @Query("select b.version.id from DocumentBookmark b where b.user.id = :userId")
    List<Long> findVersionIds(Long userId);
}
