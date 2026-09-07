package com.publicmonitor.backend.domain.document.repository;

import static org.assertj.core.api.Assertions.*;
import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import com.publicmonitor.backend.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import org.springframework.data.domain.PageRequest;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:bookmarks;MODE=Oracle;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class DocumentBookmarkIntegrationTest {
    @Autowired EntityManager em;
    @Autowired DocumentBookmarkRepository bookmarks;
    @Autowired DocumentDetectionRepository detections;

    @Test
    void savedDocumentsAreScopedToAccountAndUseLatestDetectionAcrossRuns() {
        var first = User.create("first", "test", Role.ADMIN); em.persist(first);
        var second = User.create("second", "test", Role.ADMIN); em.persist(second);
        var source = MonitoringSource.create("기관", "게시판", null, "https://example.org", null, 3, true);
        em.persist(source);
        var now = LocalDateTime.of(2026, 9, 7, 12, 0);
        var document = Document.create(source, "https://example.org/1", "1", now); em.persist(document);
        var version = DocumentVersion.create(document, 1, "저장한 공고", "본문", "a".repeat(64), now, 0, now);
        em.persist(version);
        addDetection(source, document, version, now.minusDays(1));
        var latest = addDetection(source, document, version, now);
        // A timestamp tie must still return exactly one row, using the greater ID.
        latest = addDetection(source, document, version, now);
        bookmarks.save(DocumentBookmark.create(first, document));
        em.flush();
        assertThat(bookmarks.findDocumentIds(second.getId())).isEmpty();
        assertThat(bookmarks.findDocumentIds(first.getId())).containsExactly(document.getId());
        var result = detections.findBookmarkedSummaries(first.getId(), null, null, null, false, PageRequest.of(0, 1));
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().getFirst().detectionId()).isEqualTo(latest.getId());
        assertThat(detections.findBookmarkedSummaries(second.getId(), null, null, null, true, PageRequest.of(0, 1))).isEmpty();
        assertThat(detections.findBookmarkedSummaries(first.getId(), null, null, now.minusHours(1), false, PageRequest.of(0, 1))).isEmpty();
        bookmarks.deleteByUserIdAndDocumentId(second.getId(), document.getId());
        assertThat(bookmarks.existsByUserIdAndDocumentId(first.getId(), document.getId())).isTrue();
        bookmarks.deleteByUserIdAndDocumentId(first.getId(), document.getId());
        assertThat(bookmarks.findDocumentIds(first.getId())).isEmpty();
    }

    private DocumentDetection addDetection(MonitoringSource source, Document document, DocumentVersion version, LocalDateTime at) {
        var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, at); em.persist(run);
        var runSource = MonitoringRunSource.create(run, source); em.persist(runSource);
        var detection = DocumentDetection.create(runSource, document, version, DocumentChangeType.NEW_DOCUMENT, at);
        em.persist(detection);
        return detection;
    }
}
