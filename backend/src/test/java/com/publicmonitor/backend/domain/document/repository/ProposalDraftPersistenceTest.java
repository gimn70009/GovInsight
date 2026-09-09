package com.publicmonitor.backend.domain.document.repository;

import static org.assertj.core.api.Assertions.*;
import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.service.ProposalDraftStore;
import com.publicmonitor.backend.domain.document.web.dto.*;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import com.publicmonitor.backend.domain.user.entity.*;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:proposal-drafts;MODE=Oracle;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false", "app.telegram.commands.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ProposalDraftStore.class, ProposalDraftPersistenceTest.JsonConfig.class})
class ProposalDraftPersistenceTest {
    @Autowired EntityManager em;
    @Autowired ProposalDraftStore store;
    @Autowired DocumentProposalDraftRepository drafts;
    private User first;
    private User second;
    private DocumentVersion version;
    private DocumentDetection detection;
    private DocumentDetection repeated;
    private DocumentDetection revised;
    private DocumentAttachment attachment;
    private final ProposalWriteResponse result = new ProposalWriteResponse("COMPLETED", "신청서.hwpx", true,
            List.of(new ProposalWriteResponse.Section("사업 목표", "구체적인 제안 본문입니다. ".repeat(150),
                    "원문 인용", "선택 이유", List.of("회사 근거"), List.of("확인 사항")),
                    new ProposalWriteResponse.Section("수행 방법", "구체적인 실행 방법입니다. ".repeat(150),
                    "다른 원문", "선택 이유", List.of(), List.of())), "안내");

    @TestConfiguration
    static class JsonConfig { @Bean ObjectMapper objectMapper() { return new ObjectMapper(); } }

    @BeforeEach
    void fixture() {
        first = User.create("first", "test", Role.ADMIN); em.persist(first);
        second = User.create("second", "test", Role.ADMIN); em.persist(second);
        var now = LocalDateTime.of(2026, 9, 9, 12, 0);
        var source = MonitoringSource.create("기관", "게시판", null, "https://example.org", null, 3, true); em.persist(source);
        var document = Document.create(source, "https://example.org/1", "1", now); em.persist(document);
        version = DocumentVersion.create(document, 1, "공고", "본문", "a".repeat(64), now, 1, now); em.persist(version);
        detection = detect(source, document, version, now);
        repeated = detect(source, document, version, now.plusDays(1));
        var revision = DocumentVersion.create(document, 2, "수정 공고", "수정 본문", "b".repeat(64), now, 0, now); em.persist(revision);
        revised = detect(source, document, revision, now.plusDays(2));
        attachment = DocumentAttachment.create(version, "제출서류.zip", "https://example.org/1.zip", "zip", null, 100L, null,
                "[파일: 신청서.hwpx]\n양식\n[파일: 계획서.hwpx]\n다른 양식", AttachmentParseStatus.COMPLETED, null);
        em.persist(attachment); em.flush();
    }

    @Test
    void 저장한_본문과_근거를_DB에서_복원하고_같은_버전의_다음_실행에서도_조회한다() {
        store.save(first.getId(), detection.getId(), request(0), result);
        em.flush(); em.clear();
        var saved = store.list(first.getId(), repeated.getId());
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().result()).isEqualTo(result);
        assertThat(saved.getFirst().attachmentName()).isEqualTo("제출서류.zip");
        assertThat(saved.getFirst().createdAt()).isNotNull();
        assertThat(store.list(second.getId(), detection.getId())).isEmpty();
        assertThat(store.list(first.getId(), revised.getId())).isEmpty();
    }

    @Test
    void 마지막으로_본_초안을_기억하고_완료_본문은_덮어쓰지_않는다() {
        store.save(first.getId(), detection.getId(), request(0), result);
        store.save(first.getId(), detection.getId(), request(1), result);
        em.flush();
        assertThat(store.list(first.getId(), detection.getId()).getFirst().partIndex()).isEqualTo(1);
        store.markViewed(first.getId(), detection.getId(), request(0));
        em.flush(); em.clear();
        assertThat(store.list(first.getId(), repeated.getId()).getFirst().partIndex()).isZero();
        var another = new ProposalWriteResponse("COMPLETED", "덮어쓰기.hwpx", false, result.sections(), "다른 결과");
        assertThat(store.save(first.getId(), detection.getId(), request(0), another)).isEqualTo(result);
        em.flush(); em.clear();
        assertThat(drafts.count()).isEqualTo(2);
        assertThat(store.reuse(first.getId(), repeated.getId(), request(0))).contains(result);
    }

    @Test
    void 다른_계정이나_다른_문서_버전의_초안은_선택하거나_재사용할_수_없다() {
        store.save(first.getId(), detection.getId(), request(0), result);
        assertThat(store.reuse(second.getId(), detection.getId(), request(0))).isEmpty();
        assertThat(store.reuse(first.getId(), revised.getId(), request(0))).isEmpty();
        assertThatThrownBy(() -> store.markViewed(second.getId(), detection.getId(), request(0)))
                .isInstanceOf(DocumentDetectionException.class);
    }

    @Test
    void 다른_문서의_첨부로_저장하지_않는다() {
        assertThatThrownBy(() -> store.save(first.getId(), revised.getId(), request(0), result))
                .isInstanceOf(DocumentDetectionException.class);
        assertThat(drafts.count()).isZero();
    }

    @Test
    void 실패_응답은_영구_보관하지_않는다() {
        assertThatThrownBy(() -> store.save(first.getId(), detection.getId(), request(0), ProposalWriteResponse.unavailable()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(drafts.count()).isZero();
    }

    private ProposalWriteRequest request(int index) { return new ProposalWriteRequest(attachment.getId(), index); }
    private DocumentDetection detect(MonitoringSource source, Document document, DocumentVersion version, LocalDateTime now) {
        var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, now); em.persist(run);
        var runSource = MonitoringRunSource.create(run, source); em.persist(runSource);
        var detection = DocumentDetection.create(runSource, document, version, DocumentChangeType.NEW_DOCUMENT, now);
        em.persist(detection); return detection;
    }
}
