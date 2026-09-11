package com.publicmonitor.backend.domain.document.repository;

import static org.assertj.core.api.Assertions.*;
import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.analysis.entity.*;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:comparisonversions;MODE=Oracle;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class DocumentDetectionVersionIntegrationTest {
    @Autowired EntityManager em;
    @Autowired DocumentDetectionRepository detections;
    @Autowired DocumentAnalysisRepository analyses;

    @Test
    void comparisonLinkUsesLatestDetectionOfAnalyzedVersion() {
        var source = MonitoringSource.create("기관", "게시판", null, "https://example.org", null, 3, true);
        em.persist(source);
        var now = LocalDateTime.of(2026, 9, 8, 12, 0);
        var document = Document.create(source, "https://example.org/1", "1", now);
        em.persist(document);
        var version = DocumentVersion.create(document, 1, "분석된 공고", "본문", "a".repeat(64), now, 0, now);
        em.persist(version);
        addDetection(source, document, version, now);
        var expected = addDetection(source, document, version, now);
        var revision = DocumentVersion.create(document, 2, "미분석 수정본", "새 본문", "b".repeat(64), now, 0, now);
        em.persist(revision);
        addDetection(source, document, revision, now.plusDays(1));
        em.flush();
        em.clear();
        assertThat(detections.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(version.getId()))
                .hasValueSatisfying(item -> {
                    assertThat(item.getId()).isEqualTo(expected.getId());
                    assertThat(item.getDocumentVersion().getId()).isEqualTo(version.getId());
                });
    }

    @Test
    void similarityQueryLoadsLatestAnalyzedVersionAndExcludesCurrentDocument() {
        var source = MonitoringSource.create("기관", "게시판", null, "https://example.org", null, 3, true);
        em.persist(source);
        var now = LocalDateTime.of(2026, 9, 8, 12, 0);
        var document = Document.create(source, "https://example.org/2", "2", now);
        em.persist(document);
        Long expectedVersion = null;
        for (int i = 1; i <= 3; i++) {
            var version = DocumentVersion.create(document, i, "공고", "본문", String.valueOf(i).repeat(64), now, 0, now);
            em.persist(version);
            if (i == 3) continue;
            var analysis = DocumentAnalysis.create(version, "요약", "[]", DocumentImportance.NORMAL,
                    "근거", AnalysisEligibility.REVIEW_REQUIRED, AnalysisFavorability.NOT_APPLICABLE,
                    "{}", 70, null, "[]", "test", now);
            em.persist(analysis);
            expectedVersion = version.getId();
        }
        em.flush();
        em.clear();
        assertThat(analyses.findLatestSimilarityCandidates(-1L)).extracting(item -> item.getDocumentVersion().getId())
                .containsExactly(expectedVersion);
        assertThat(analyses.findLatestSimilarityCandidates(document.getId())).isEmpty();
        var revision = analyses.similarityRevision();
        assertThat(revision.getTotal()).isEqualTo(2);
        assertThat(revision.getLastId()).isNotNull();
        assertThat(revision.getChangedAt()).isNotNull();
    }

    private DocumentDetection addDetection(MonitoringSource source, Document document, DocumentVersion version, LocalDateTime at) {
        var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, at); em.persist(run);
        var runSource = MonitoringRunSource.create(run, source); em.persist(runSource);
        var detection = DocumentDetection.create(runSource, document, version, DocumentChangeType.NEW_DOCUMENT, at);
        em.persist(detection);
        return detection;
    }
}
