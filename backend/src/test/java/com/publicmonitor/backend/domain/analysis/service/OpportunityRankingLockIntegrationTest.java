package com.publicmonitor.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.document.entity.Document;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:opportunity-ranking-lock;MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, OpportunityRankingBackfillWriter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OpportunityRankingLockIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 9, 0);
    private static final String OLD_ASSESSMENT = """
            {"dimensions":[{"type":"COMPANY_FIT","score":80},{"type":"BUSINESS_VALUE","score":70},
            {"type":"FEASIBILITY","score":60},{"type":"URGENCY","score":90}]}
            """;
    private static final String NEW_ASSESSMENT = """
            {"dimensions":[{"type":"COMPANY_FIT","score":60},{"type":"BUSINESS_VALUE","score":60},
            {"type":"FEASIBILITY","score":60},{"type":"URGENCY","score":60}]}
            """;
    private static final OpportunityRankingCalculator.Ranking OLD_RANKING =
            new OpportunityRankingCalculator.Ranking(75, OpportunityPriority.HIGH, false);

    @Autowired EntityManager em;
    @Autowired DocumentAnalysisRepository analyses;
    @Autowired OpportunityRankingBackfillWriter writer;
    @Autowired PlatformTransactionManager transactionManager;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            em.createQuery("delete from DocumentAnalysis").executeUpdate();
            em.createQuery("delete from DocumentVersion").executeUpdate();
            em.createQuery("delete from Document").executeUpdate();
            em.createQuery("delete from MonitoringSource").executeUpdate();
        });
    }

    @Test
    void 새_분석이_기존과_같은_총점이어도_이전_JSON_보완이_끼어들지_않는다() throws Exception {
        var fixture = seed();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writerStarted = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var replacement = workers.submit(() -> tx.execute(status -> {
                    var analysis = analyses.findByDocumentVersionIdForUpdate(fixture.versionId()).orElseThrow();
                    assertThat(analysis.getOpportunityScore()).isEqualTo(60);
                    locked.countDown();
                    await(release);
                    replace(analysis);
                    return true;
                }));
                await(locked);
                var backfill = workers.submit(() -> {
                    writerStarted.countDown();
                    return writer.write(fixture.analysisId(), OLD_RANKING);
                });
                await(writerStarted);
                assertThatThrownBy(() -> backfill.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);

                release.countDown();
                assertThat(replacement.get(10, TimeUnit.SECONDS)).isTrue();
                assertThat(backfill.get(10, TimeUnit.SECONDS)).isZero();
            } finally {
                release.countDown();
            }
        }
        assertNewRanking(fixture.analysisId());
    }

    @Test
    void 보완이_먼저_끝나면_새_분석은_최신_점수를_읽은_뒤_기존과_같은_총점으로_교체한다() throws Exception {
        var fixture = seed();
        var updated = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var replacementStarted = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var backfill = workers.submit(() -> tx.execute(status -> {
                    int count = analyses.backfillRanking(fixture.analysisId(), 75, OpportunityPriority.HIGH, 1);
                    updated.countDown();
                    await(release);
                    return count;
                }));
                await(updated);
                var replacement = workers.submit(() -> {
                    replacementStarted.countDown();
                    return tx.execute(status -> {
                        var analysis = analyses.findByDocumentVersionIdForUpdate(fixture.versionId()).orElseThrow();
                        int latestScore = analysis.getOpportunityScore();
                        replace(analysis);
                        return latestScore;
                    });
                });
                await(replacementStarted);
                assertThatThrownBy(() -> replacement.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);

                release.countDown();
                assertThat(backfill.get(10, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(replacement.get(10, TimeUnit.SECONDS)).isEqualTo(75);
            } finally {
                release.countDown();
            }
        }
        assertNewRanking(fixture.analysisId());
    }

    private Fixture seed() {
        return tx.execute(status -> {
            var source = MonitoringSource.create("기관", "공고", null, "https://example.org", null, 1, true);
            em.persist(source);
            var document = Document.create(source, "https://example.org/notice", null, NOW);
            em.persist(document);
            var version = DocumentVersion.create(document, 1, "공고", "본문", "a".repeat(64), NOW, 0, NOW);
            em.persist(version);
            // The old persisted score differs from the score now derived from its JSON.
            var analysis = DocumentAnalysis.create(version, "기존 분석", "[]", DocumentImportance.NORMAL,
                    null, null, null, "기존 제안", 60, null, OLD_ASSESSMENT, "[]", "test", NOW);
            ReflectionTestUtils.setField(analysis, "opportunityRankingVersion", null);
            em.persist(analysis);
            em.flush();
            return new Fixture(analysis.getId(), version.getId());
        });
    }

    private void replace(DocumentAnalysis analysis) {
        analysis.replaceAnalysis("새 분석", "[]", DocumentImportance.NORMAL, null, null, null,
                "새 제안", 60, OpportunityPriority.NORMAL, NEW_ASSESSMENT, "[]", "new-model", NOW.plusMinutes(1));
    }

    private void assertNewRanking(Long id) {
        tx.executeWithoutResult(status -> {
            var analysis = analyses.findById(id).orElseThrow();
            assertThat(analysis.getSummary()).isEqualTo("새 분석");
            assertThat(analysis.getOpportunityScore()).isEqualTo(60);
            assertThat(analysis.getOpportunityPriority()).isEqualTo(OpportunityPriority.NORMAL);
            assertThat(analysis.getOpportunityRankingVersion()).isEqualTo(1);
            assertThat(analysis.getOpportunityAssessment()).isEqualTo(NEW_ASSESSMENT);
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent operation did not arrive");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private record Fixture(Long analysisId, Long versionId) {}
}
