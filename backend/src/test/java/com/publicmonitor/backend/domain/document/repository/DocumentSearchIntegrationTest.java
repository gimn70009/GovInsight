package com.publicmonitor.backend.domain.document.repository;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.analysis.entity.*;
import com.publicmonitor.backend.domain.analysis.service.OpportunityScoreCalculator;
import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.document.service.*;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import com.publicmonitor.backend.domain.user.entity.*;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:document-search;MODE=Oracle;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class DocumentSearchIntegrationTest {
    @Autowired EntityManager em;
    @Autowired DocumentDetectionRepository detections;
    @Autowired DocumentBookmarkRepository bookmarks;
    @Autowired DocumentVersionRepository versions;
    @Autowired UserRepository users;
    private DocumentDetectionQueryService query;
    private DocumentBookmarkService saved;
    private MonitoringSource source;
    private MonitoringRun run;
    private MonitoringRunSource runSource;
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 2, 12, 0);
    private int sequence;

    @BeforeEach void setup() {
        query = new DocumentDetectionQueryService(detections, new ObjectMapper());
        saved = new DocumentBookmarkService(bookmarks, versions, detections, users, query);
        source = MonitoringSource.create("과학기술정보통신부", "RND 게시판", null, "https://example.org", null, 1, true);
        em.persist(source);
        run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, now); em.persist(run);
        runSource = MonitoringRunSource.create(run, source); em.persist(runSource);
    }

    private DocumentVersion add(String title, LocalDateTime at, int fit, int value, int feasible, int urgency) {
        var document = Document.create(source, "https://example.org/" + ++sequence, null, at); em.persist(document);
        var version = DocumentVersion.create(document, 1, title, "본문", "a".repeat(64), at, 0, at); em.persist(version);
        em.persist(DocumentDetection.create(runSource, document, version, DocumentChangeType.NEW_DOCUMENT, at));
        if (fit >= 0) {
            String assessment = """
                    {"dimensions":[{"type":"COMPANY_FIT","score":%d},
                    {"type":"BUSINESS_VALUE","score":%d},{"type":"FEASIBILITY","score":%d},
                    {"type":"URGENCY","score":%d}]}
                    """.formatted(fit, value, feasible, urgency);
            int score = OpportunityScoreCalculator.calculate(Map.of(OpportunityDimensionType.COMPANY_FIT, fit,
                    OpportunityDimensionType.BUSINESS_VALUE, value, OpportunityDimensionType.FEASIBILITY, feasible,
                    OpportunityDimensionType.URGENCY, urgency));
            em.persist(DocumentAnalysis.create(version, "요약", "[]", DocumentImportance.NORMAL, null,
                    null, null, null, score, assessment, "[]", "test", now));
        }
        return version;
    }

    @Test void 첫페이지_밖의_검색결과도_찾고_필터된_건수와_다음페이지를_반환한다() {
        for (int i = 0; i < 25; i++) add("일반 공고 " + i, now.plusMinutes(i), -1, 0, 0, 0);
        for (int i = 0; i < 25; i++) add("스마트워치 AI 지원 " + i, now.minusDays(1).plusMinutes(i), -1, 0, 0, 0);
        em.flush();
        assertThat(query.findAll(0, 20, null, null, run.getId()).content())
                .allMatch(row -> row.title().startsWith("일반"));
        var first = query.findAll(0, 20, null, null, run.getId(), DocumentDetectionSort.LATEST, "  스마트워치 ai  ", null);
        assertThat(first.totalElements()).isEqualTo(25);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content()).hasSize(20).allMatch(row -> row.title().startsWith("스마트워치"));
        assertThat(query.findAll(1, 20, null, null, run.getId(), DocumentDetectionSort.LATEST, "스마트워치", null).content()).hasSize(5);
        assertThat(query.findAll(0, 20, now, null, run.getId(), DocumentDetectionSort.LATEST, "스마트워치", null).totalElements()).isZero();
        assertThat(query.findAll(0, 20, null, null, run.getId() + 9999, DocumentDetectionSort.LATEST, "스마트워치", null).totalElements()).isZero();
    }

    @Test void 기관과_게시판_검색_및_SQL특수문자_일반문자_검색을_지원한다() {
        add("할인 50%_AI! 공고", now, -1, 0, 0, 0);
        add("할인 500XAI 공고", now.minusMinutes(1), -1, 0, 0, 0);
        em.flush();
        for (String keyword : new String[]{"과학기술", "rnd", "   "}) {
            assertThat(query.findAll(0, 1, null, null, null, DocumentDetectionSort.LATEST, keyword, null).totalElements()).isEqualTo(2);
        }
        assertThat(query.findAll(0, 20, null, null, null, DocumentDetectionSort.LATEST, "50%_ai!", null).totalElements()).isEqualTo(1);
        assertThat(query.findAll(0, 20, null, null, null, DocumentDetectionSort.LATEST, "없는 검색어", null).content()).isEmpty();
    }

    @Test void 우선순위는_200건_묶음_밖에서도_기존_세부점수_기준으로_계산하고_페이징한다() {
        for (int i = 0; i < 205; i++) add("사업 참고 " + i, now.plusMinutes(i), 30, 30, 30, 30);
        for (int i = 0; i < 22; i++) add("사업 우선 " + i, now.minusDays(1).plusMinutes(i), 80, 80, 80, 80);
        add("사업 긴급", now.minusDays(2), 55, 55, 55, 90);
        add("사업 검토", now.minusDays(3), 60, 60, 60, 60);
        add("사업 실행성 낮음", now.minusDays(4), 90, 90, 20, 90);
        add("사업 미분석", now.minusDays(5), -1, 0, 0, 0);
        em.flush();
        var result = query.findAll(0, 20, null, null, run.getId(), DocumentDetectionSort.LATEST, "사업", OpportunityPriority.HIGH);
        assertThat(result.totalElements()).isEqualTo(23);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.content()).hasSize(20).allMatch(row -> row.opportunityPriority() == OpportunityPriority.HIGH);
        var next = query.findAll(1, 20, null, null, run.getId(), DocumentDetectionSort.LATEST, "사업", OpportunityPriority.HIGH);
        assertThat(next.content()).hasSize(3).anyMatch(row -> row.title().equals("사업 긴급"));
        assertThat(next.totalElements()).isEqualTo(23);
        assertThat(query.findAll(0, 20, null, null, run.getId(), DocumentDetectionSort.LATEST, "사업", OpportunityPriority.NORMAL).totalElements()).isEqualTo(2);
        assertThat(query.findAll(0, 20, null, null, run.getId(), DocumentDetectionSort.LATEST, "사업", OpportunityPriority.LOW).totalElements()).isEqualTo(205);
        var sorted = query.findAll(0, 20, null, null, run.getId(), DocumentDetectionSort.OPPORTUNITY_SCORE, "사업", OpportunityPriority.HIGH);
        assertThat(sorted.content().getFirst().opportunityScore()).isEqualTo(80);
    }

    @Test void 북마크도_전체에서_검색하고_계정과_최신감지_및_기간_범위를_유지한다() {
        var first = User.create("first", "test", Role.ADMIN); em.persist(first);
        var second = User.create("second", "test", Role.ADMIN); em.persist(second);
        for (int i = 0; i < 25; i++) {
            var version = add("스마트워치 " + i, now.minusMinutes(i), 80, 80, 80, 80);
            bookmarks.save(DocumentBookmark.create(first, version));
        }
        var other = add("스마트워치 다른계정", now, 80, 80, 80, 80);
        bookmarks.save(DocumentBookmark.create(second, other));
        var duplicated = add("중복 버전", now.minusDays(1), 80, 80, 80, 80);
        bookmarks.save(DocumentBookmark.create(first, duplicated));
        var newerRun = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, now); em.persist(newerRun);
        var newerSource = MonitoringRunSource.create(newerRun, source); em.persist(newerSource);
        em.persist(DocumentDetection.create(newerSource, duplicated.getDocument(), duplicated, DocumentChangeType.UNCHANGED_DOCUMENT, now));
        em.flush();
        var result = saved.findAll(first.getId(), 0, 20, null, null, DocumentDetectionSort.LATEST, "스마트워치", OpportunityPriority.HIGH);
        assertThat(result.totalElements()).isEqualTo(25);
        assertThat(result.content()).hasSize(20).noneMatch(row -> row.title().contains("다른계정"));
        assertThat(saved.findAll(first.getId(), 1, 20, null, null, DocumentDetectionSort.OPPORTUNITY_SCORE, "스마트워치", OpportunityPriority.HIGH).content()).hasSize(5);
        assertThat(saved.findAll(second.getId(), 0, 20, null, null, DocumentDetectionSort.LATEST, "스마트워치", null).totalElements()).isEqualTo(1);
        assertThat(saved.findAll(first.getId(), 0, 20, null, null, DocumentDetectionSort.LATEST, "중복", null).totalElements()).isEqualTo(1);
        assertThat(saved.findAll(first.getId(), 0, 20, null, now.minusHours(1), DocumentDetectionSort.LATEST, "중복", null).totalElements()).isZero();
        assertThat(saved.findAll(first.getId(), 0, 20, null, null, DocumentDetectionSort.LATEST, "스마트워치", OpportunityPriority.LOW).totalElements()).isZero();
    }
}
