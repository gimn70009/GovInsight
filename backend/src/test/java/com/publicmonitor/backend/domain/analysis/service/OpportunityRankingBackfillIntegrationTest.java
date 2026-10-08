package com.publicmonitor.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisEligibility;
import com.publicmonitor.backend.domain.analysis.entity.AnalysisFavorability;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:opportunity-backfill;MODE=Oracle;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.show-sql=false", "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, OpportunityRankingBackfillService.class, OpportunityRankingBackfillWriter.class,
        OpportunityRankingCalculator.class, OpportunityRankingBackfillIntegrationTest.MapperConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OpportunityRankingBackfillIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 9, 0);
    private static final String ASSESSMENT = """
            {"dimensions":[{"type":"COMPANY_FIT","score":80},
            {"type":"BUSINESS_VALUE","score":70},{"type":"FEASIBILITY","score":60},
            {"type":"URGENCY","score":90}]}
            """;
    @Autowired EntityManager em;
    @Autowired DocumentAnalysisRepository analyses;
    @Autowired OpportunityRankingBackfillService backfill;
    @Autowired PlatformTransactionManager transactionManager;
    private TransactionTemplate tx;
    private final AtomicInteger sequence = new AtomicInteger();

    @TestConfiguration
    static class MapperConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @BeforeEach
    void clearFixtures() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            em.createQuery("delete from DocumentAnalysis").executeUpdate();
            em.createQuery("delete from DocumentVersion").executeUpdate();
            em.createQuery("delete from Document").executeUpdate();
            em.createQuery("delete from MonitoringSource").executeUpdate();
        });
    }

    @Test
    void 두_묶음으로_기존_순위를_보완하고_미분류도_재시작_때_다시_읽지_않는다() {
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < 205; i++) {
                legacy(i == 203 ? null : i == 204 ? "{broken" : ASSESSMENT, null);
            }
        });

        var first = backfill.backfillAfter(0);
        var second = backfill.backfillAfter(first.lastId());

        assertThat(first.examined()).isEqualTo(200);
        assertThat(first.updated()).isEqualTo(200);
        assertThat(second.examined()).isEqualTo(5);
        assertThat(second.updated()).isEqualTo(5);
        assertThat(backfill.backfillAfter(0).examined()).isZero();
        tx.executeWithoutResult(status -> {
            var all = analyses.findAll();
            assertThat(all).hasSize(205).allMatch(a -> a.getOpportunityRankingVersion() == 1);
            assertThat(all.stream().filter(a -> a.getOpportunityPriority() == OpportunityPriority.HIGH))
                    .hasSize(203).allMatch(a -> a.getOpportunityScore() == 75);
            assertThat(all.stream().filter(a -> a.getOpportunityPriority() == null))
                    .hasSize(2).allMatch(a -> a.getOpportunityScore() == 99);
        });
    }

    @Test
    void 보완_조회_후_새_분석이_저장되면_오래된_계산으로_덮지_않는다() {
        Long id = tx.execute(status -> legacy(ASSESSMENT, null).getId());
        var old = tx.execute(status -> analyses.findRankingsForBackfill(0, 1, PageRequest.of(0, 200)).getFirst());
        tx.executeWithoutResult(status -> {
            var analysis = analyses.findById(id).orElseThrow();
            analysis.replaceAnalysis("새 분석", "[]", DocumentImportance.NORMAL, "새 근거",
                    AnalysisEligibility.REVIEW_REQUIRED, AnalysisFavorability.NOT_APPLICABLE,
                    "새 제안", 30, OpportunityPriority.LOW, "새 평가", "[]", "new-model", NOW.plusMinutes(1));
        });

        Integer updated = tx.execute(status -> analyses.backfillRanking(old.getId(), 75, OpportunityPriority.HIGH, 1));

        assertThat(updated).isZero();
        tx.executeWithoutResult(status -> {
            var actual = analyses.findById(id).orElseThrow();
            assertThat(actual.getSummary()).isEqualTo("새 분석");
            assertThat(actual.getOpportunityScore()).isEqualTo(30);
            assertThat(actual.getOpportunityPriority()).isEqualTo(OpportunityPriority.LOW);
            assertThat(actual.getOpportunityRankingVersion()).isEqualTo(1);
            assertThat(actual.getOpportunityAssessment()).isEqualTo("새 평가");
        });
    }

    @Test
    void 보완_전에_읽은_엔티티의_제안_법률_임베딩_갱신은_최신_순위를_덮지_않는다() {
        Long id = tx.execute(status -> legacy(ASSESSMENT, null).getId());

        tx.executeWithoutResult(status -> {
            var stale = analyses.findById(id).orElseThrow();
            assertThat(stale.getOpportunityRankingVersion()).isNull();
            assertThat(backfill.backfillAfter(0).updated()).isEqualTo(1);
            stale.updateProposal("새 제안", "새 도구", NOW.plusMinutes(1));
            stale.updateComparisonSummary("새 법률 검토");
            stale.updateSimilarity("새 검색 프로필", "[1,2]", "embedding-model");
        });

        tx.executeWithoutResult(status -> {
            var actual = analyses.findById(id).orElseThrow();
            assertThat(actual.getOpportunityScore()).isEqualTo(75);
            assertThat(actual.getOpportunityPriority()).isEqualTo(OpportunityPriority.HIGH);
            assertThat(actual.getOpportunityRankingVersion()).isEqualTo(1);
            assertThat(actual.getProposalDirection()).isEqualTo("새 제안");
            assertThat(actual.getComparisonSummary()).isEqualTo("새 법률 검토");
            assertThat(actual.getSimilarityProfile()).isEqualTo("새 검색 프로필");
        });
    }

    @Test
    void 보완_뒤_새_분석이_저장되면_새로운_순위가_최종값이다() {
        Long id = tx.execute(status -> legacy(ASSESSMENT, 0).getId());
        assertThat(backfill.backfillAfter(0).updated()).isEqualTo(1);

        tx.executeWithoutResult(status -> analyses.findById(id).orElseThrow().replaceAnalysis(
                "새 분석", "[]", DocumentImportance.NORMAL, "근거", AnalysisEligibility.REVIEW_REQUIRED,
                AnalysisFavorability.NOT_APPLICABLE, "제안", 60, OpportunityPriority.NORMAL,
                "새 평가", "[]", "new-model", NOW.plusMinutes(1)));

        tx.executeWithoutResult(status -> {
            var actual = analyses.findById(id).orElseThrow();
            assertThat(actual.getOpportunityScore()).isEqualTo(60);
            assertThat(actual.getOpportunityPriority()).isEqualTo(OpportunityPriority.NORMAL);
            assertThat(actual.getOpportunityRankingVersion()).isEqualTo(1);
        });
    }

    @Test
    void 최신_및_미래_계산버전은_조회와_조건부갱신에서_제외한다() {
        List<Long> ids = tx.execute(status -> List.of(legacy(ASSESSMENT, 1).getId(), legacy(ASSESSMENT, 2).getId()));

        assertThat(backfill.backfillAfter(0).examined()).isZero();
        tx.executeWithoutResult(status -> {
            for (Long id : ids) assertThat(analyses.backfillRanking(id, 75, OpportunityPriority.HIGH, 1)).isZero();
            assertThat(analyses.findAll()).allMatch(a -> a.getOpportunityScore() == 99);
        });
    }

    private DocumentAnalysis legacy(String assessment, Integer rankingVersion) {
        int index = sequence.incrementAndGet();
        var source = MonitoringSource.create("기관", "게시판", null, "https://example.org/" + index, null, 2, true);
        em.persist(source);
        var document = Document.create(source, "https://example.org/notice/" + index, null, NOW);
        em.persist(document);
        var version = DocumentVersion.create(document, 1, "공고", "본문", "a".repeat(64), NOW, 0, NOW);
        em.persist(version);
        var analysis = DocumentAnalysis.create(version, "저장 분석", "[]", DocumentImportance.NORMAL, "근거",
                AnalysisEligibility.REVIEW_REQUIRED, AnalysisFavorability.NOT_APPLICABLE,
                "기존 제안", 99, null, assessment, "[]", "old-model", NOW);
        ReflectionTestUtils.setField(analysis, "opportunityRankingVersion", rankingVersion);
        em.persist(analysis);
        return analysis;
    }
}
