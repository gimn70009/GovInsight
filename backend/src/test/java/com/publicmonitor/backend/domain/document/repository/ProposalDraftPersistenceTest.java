package com.publicmonitor.backend.domain.document.repository;

import static org.assertj.core.api.Assertions.*;
import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.service.ProposalDraftStore;
import com.publicmonitor.backend.domain.document.service.ProposalSourceService;
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
@Import({JpaAuditingConfig.class, ProposalDraftStore.class, ProposalSourceService.class, ProposalDraftPersistenceTest.JsonConfig.class})
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

    @Test
    void 잘못_저장된_공고_초안은_목록에서_제외하지만_DB와_정상_초안은_보존한다() {
        store.save(first.getId(), detection.getId(), request(0), result);
        var notice = DocumentAttachment.create(version, "공고.hwpx", "https://example.org/notice", "hwpx",
                null, 100L, null, "선정 공고\n지원 대상\n평가 기준", AttachmentParseStatus.COMPLETED, null);
        em.persist(notice);
        var historical = DocumentProposalDraft.create(first, notice, 0,
                new ObjectMapper().writeValueAsString(result), LocalDateTime.now());
        em.persist(historical); em.flush(); em.clear();
        assertThat(store.list(first.getId(), detection.getId())).hasSize(1)
                .allSatisfy(item -> assertThat(item.attachmentId()).isEqualTo(attachment.getId()));
        assertThat(drafts.findById(historical.getId())).isPresent();
        assertThat(drafts.count()).isEqualTo(2);
    }

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

    @Test
    void 재작성은_직전_초안을_보관하고_복원과_재복원을_멱등하게_처리한다() {
        store.save(first.getId(), detection.getId(), request(0), result);
        em.flush(); em.clear();
        var original = store.list(first.getId(), detection.getId()).getFirst();
        var next = new ProposalWriteResponse("COMPLETED", "신청서.hwpx", false, result.sections(), "새 초안");
        assertThat(original.revision()).isZero();
        assertThat(original.canRestorePrevious()).isFalse();
        assertThat(store.replace(first.getId(), detection.getId(), request(0), 0, "rewrite", next)).isEqualTo(next);
        em.flush(); em.clear();
        var rewritten = store.list(first.getId(), repeated.getId()).getFirst();
        assertThat(rewritten.revision()).isEqualTo(1);
        assertThat(rewritten.canRestorePrevious()).isTrue();
        assertThat(rewritten.createdAt()).isEqualTo(original.createdAt());
        assertThat(rewritten.lastOperationId()).isEqualTo("rewrite");
        assertThat(store.replace(first.getId(), detection.getId(), request(0), 0, "rewrite", next)).isEqualTo(next);
        assertThat(store.restore(first.getId(), detection.getId(), request(0), 1, "restore")).isEqualTo(result);
        em.flush(); em.clear();
        assertThat(store.restore(first.getId(), detection.getId(), request(0), 1, "restore")).isEqualTo(result);
        assertThat(store.list(first.getId(), detection.getId()).getFirst().revision()).isEqualTo(2);
        assertThat(store.restore(first.getId(), detection.getId(), request(0), 2, "redo")).isEqualTo(next);
        em.flush(); em.clear();
        assertThat(store.list(first.getId(), detection.getId()).getFirst().revision()).isEqualTo(3);
        assertThat(drafts.count()).isEqualTo(1);
    }

    @Test
    void 오래된_요청과_실패_결과는_현재와_직전_초안을_바꾸지_않는다() {
        store.save(first.getId(), detection.getId(), request(0), result);
        em.flush(); em.clear();
        assertThatThrownBy(() -> store.restore(first.getId(), detection.getId(), request(0), 0, "missing"))
                .isInstanceOf(DocumentDetectionException.class);
        var next = new ProposalWriteResponse("COMPLETED", "신청서.hwpx", false, result.sections(), "새 초안");
        store.replace(first.getId(), detection.getId(), request(0), 0, "rewrite", next);
        em.flush(); em.clear();
        assertThatThrownBy(() -> store.beforeChange(first.getId(), detection.getId(), request(0), 0, "stale"))
                .isInstanceOf(DocumentDetectionException.class);
        assertThatThrownBy(() -> store.replace(first.getId(), detection.getId(), request(0), 0, "stale", result))
                .isInstanceOf(DocumentDetectionException.class);
        assertThatThrownBy(() -> store.restore(first.getId(), detection.getId(), request(0), 0, "stale"))
                .isInstanceOf(DocumentDetectionException.class);
        assertThatThrownBy(() -> store.replace(first.getId(), detection.getId(), request(0), 1, "failed", ProposalWriteResponse.unavailable()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.restore(second.getId(), detection.getId(), request(0), 1, "other-user"))
                .isInstanceOf(DocumentDetectionException.class);
        assertThatThrownBy(() -> store.replace(first.getId(), revised.getId(), request(0), 1, "other-version", result))
                .isInstanceOf(DocumentDetectionException.class);
        em.flush(); em.clear();
        var unchanged = store.list(first.getId(), detection.getId()).getFirst();
        assertThat(unchanged.result()).isEqualTo(next);
        assertThat(unchanged.revision()).isEqualTo(1);
        assertThat(store.restore(first.getId(), detection.getId(), request(0), 1, "restore")).isEqualTo(result);
    }

    @Test
    void 읽기전용_OSIV_스냅샷도_재작성_결과를_저장하고_최신_버전을_다시_확인한다() {
        store.save(first.getId(), detection.getId(), request(0), result);
        em.flush(); em.clear();
        var entity = drafts.findAll().getFirst();
        em.unwrap(org.hibernate.Session.class).setReadOnly(entity, true);
        var next = new ProposalWriteResponse("COMPLETED", "신청서.hwpx", false, result.sections(), "새 초안");
        store.beforeChange(first.getId(), detection.getId(), request(0), 0, "rewrite");
        store.replace(first.getId(), detection.getId(), request(0), 0, "rewrite", next);
        em.flush(); em.clear();
        assertThat(store.list(first.getId(), detection.getId()).getFirst().result()).isEqualTo(next);
        var stale = drafts.findAll().getFirst();
        em.createNativeQuery("update document_proposal_drafts set revision = 2 where draft_id = :id")
                .setParameter("id", stale.getId()).executeUpdate();
        assertThat(stale.getRevision()).isEqualTo(1);
        assertThatThrownBy(() -> store.replace(first.getId(), detection.getId(), request(0), 1, "stale", result))
                .isInstanceOf(DocumentDetectionException.class);
        assertThat(stale.getRevision()).isEqualTo(2);
    }

    private ProposalWriteRequest request(int index) { return new ProposalWriteRequest(attachment.getId(), index); }
    private DocumentDetection detect(MonitoringSource source, Document document, DocumentVersion version, LocalDateTime now) {
        var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, now); em.persist(run);
        var runSource = MonitoringRunSource.create(run, source); em.persist(runSource);
        var detection = DocumentDetection.create(runSource, document, version, DocumentChangeType.NEW_DOCUMENT, now);
        em.persist(detection); return detection;
    }
}
