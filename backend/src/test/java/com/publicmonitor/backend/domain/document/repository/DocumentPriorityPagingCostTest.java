package com.publicmonitor.backend.domain.document.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import com.publicmonitor.backend.domain.document.entity.Document;
import com.publicmonitor.backend.domain.document.entity.DocumentBookmark;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.service.DocumentBookmarkService;
import com.publicmonitor.backend.domain.document.service.DocumentDetectionQueryService;
import com.publicmonitor.backend.domain.document.service.DocumentDetectionSort;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryResponse;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.user.entity.Role;
import com.publicmonitor.backend.domain.user.entity.User;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import com.publicmonitor.backend.global.response.PageResponse;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:document-priority-paging-cost;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.publicmonitor.backend.domain.document.repository.DocumentPriorityPagingCostTest$SqlCapture",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "app.local-admin.enabled=false",
        "app.monitoring.schedule.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
public class DocumentPriorityPagingCostTest {

    private static final int CANDIDATES = 1_000;
    private static final int PAGE_SIZE = 20;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 12, 0);

    @Autowired EntityManager em;
    @Autowired DocumentDetectionRepository detections;
    @Autowired DocumentBookmarkRepository bookmarks;
    @Autowired DocumentVersionRepository versions;
    @Autowired UserRepository users;

    private DocumentDetectionQueryService query;
    private DocumentBookmarkService saved;
    private Statistics statistics;
    private Long runId;
    private Long userId;

    @BeforeEach
    void seedThousandCandidatesBeforeMeasuring() {
        query = new DocumentDetectionQueryService(detections);
        saved = new DocumentBookmarkService(bookmarks, versions, detections, users, query);
        statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();

        var user = User.create("paging-cost-user", "test", Role.ADMIN);
        em.persist(user);
        userId = user.getId();
        var source = MonitoringSource.create("성능 검증 기관", "지원 게시판", null,
                "https://example.org", null, 1, true);
        em.persist(source);
        var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, NOW);
        em.persist(run);
        runId = run.getId();
        var runSource = MonitoringRunSource.create(run, source);
        em.persist(runSource);

        for (int index = 0; index < CANDIDATES; index++) {
            var at = NOW.plusSeconds(index);
            var document = Document.create(source, "https://example.org/" + index, null, at);
            em.persist(document);
            var version = DocumentVersion.create(document, 1, "지원 사업 " + index,
                    "조회하지 않는 원문", "a".repeat(64), at, 0, at);
            em.persist(version);
            em.persist(DocumentDetection.create(runSource, document, version,
                    DocumentChangeType.NEW_DOCUMENT, at));
            int score = index % 2 == 1 ? 80 : 30;
            var priority = index % 2 == 1 ? OpportunityPriority.HIGH : OpportunityPriority.LOW;
            String assessment = """
                    {"dimensions":[{"type":"COMPANY_FIT","score":%1$d},
                    {"type":"BUSINESS_VALUE","score":%1$d},
                    {"type":"FEASIBILITY","score":%1$d},
                    {"type":"URGENCY","score":%1$d}],"reason":"%2$s"}
                    """.formatted(score, "저장된 분석 근거 ".repeat(500));
            em.persist(DocumentAnalysis.create(version, "조회하지 않는 분석 요약", "[]",
                    DocumentImportance.NORMAL, null, null, null, null,
                    score, priority, assessment, "[]", "test", NOW));
            em.persist(DocumentBookmark.create(user, version));
        }
        em.flush();
        em.clear();
    }

    @AfterEach
    void releaseSqlCapture() {
        SqlCapture.clear();
    }

    @Test
    void 감지_우선순위_검색은_천건에서도_요청한_페이지와_건수만_조회한다() {
        assertBoundedPages(page -> query.findAll(page, PAGE_SIZE, null, null, runId,
                DocumentDetectionSort.OPPORTUNITY_SCORE, "지원", OpportunityPriority.HIGH));
    }

    @Test
    void 북마크_우선순위_검색은_천건에서도_요청한_페이지와_건수만_조회한다() {
        assertBoundedPages(page -> saved.findAll(userId, page, PAGE_SIZE, null, null,
                DocumentDetectionSort.LATEST, "지원", OpportunityPriority.HIGH));
    }

    private void assertBoundedPages(IntFunction<PageResponse<DocumentDetectionSummaryResponse>> readPage) {
        for (int page : new int[]{0, 1, CANDIDATES / 2 / PAGE_SIZE}) {
            em.clear();
            statistics.clear();
            SqlCapture.clear();

            var result = readPage.apply(page);

            int expectedRows = page < CANDIDATES / 2 / PAGE_SIZE ? PAGE_SIZE : 0;
            assertThat(result.totalElements()).isEqualTo(CANDIDATES / 2);
            assertThat(result.totalPages()).isEqualTo(CANDIDATES / 2 / PAGE_SIZE);
            assertThat(result.content()).hasSize(expectedRows)
                    .allMatch(row -> row.opportunityPriority() == OpportunityPriority.HIGH);
            for (int row = 0; row < expectedRows; row++) {
                assertThat(result.content().get(row).title())
                        .isEqualTo("지원 사업 " + (CANDIDATES - 1 - 2 * (page * PAGE_SIZE + row)));
            }

            var statements = SqlCapture.statements();
            assertThat(statements).as("page %s SQL", page).hasSizeBetween(1, 2);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(statements.size());
            assertThat(statements).allSatisfy(sql -> {
                assertThat(sql).startsWith("select ");
                assertThat(sql).doesNotContain("opportunity_assessment", "summary", "key_points",
                        "proposal_direction", "similarity_profile", "similarity_embedding",
                        "used_tools", "content_text");
            });
            assertThat(statistics.getEntityLoadCount()).as("projection must not hydrate entities").isZero();
            assertThat(statistics.getEntityFetchCount()).isZero();
            assertThat(statistics.getCollectionLoadCount()).isZero();
            assertThat(statistics.getCollectionFetchCount()).isZero();
            long materializedRows = Arrays.stream(statistics.getQueries())
                    .map(statistics::getQueryStatistics)
                    .mapToLong(queryStatistics -> queryStatistics.getExecutionRowCount())
                    .sum();
            assertThat(materializedRows).as("page rows plus at most one count row")
                    .isLessThanOrEqualTo(expectedRows + 1L);
        }
    }

    public static final class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> STATEMENTS = ThreadLocal.withInitial(ArrayList::new);

        @Override
        public String inspect(String sql) {
            STATEMENTS.get().add(sql.toLowerCase(Locale.ROOT).strip());
            return sql;
        }

        static List<String> statements() {
            return List.copyOf(STATEMENTS.get());
        }

        static void clear() {
            STATEMENTS.remove();
        }
    }
}
