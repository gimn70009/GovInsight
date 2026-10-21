package com.publicmonitor.backend.domain.document.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.analysis.entity.*;
import com.publicmonitor.backend.domain.analysis.repository.AnalysisTaskRepository;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.document.service.DocumentDetectionDetailService;
import com.publicmonitor.backend.domain.document.service.DocumentDetectionQueryService;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryResponse;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:analysisstatus;MODE=Oracle;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class DocumentAnalysisStatusIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 14, 0);
    @Autowired EntityManager em;
    @Autowired DocumentDetectionRepository detections;
    @Autowired DocumentAnalysisRepository analyses;
    @Autowired AnalysisTaskRepository tasks;
    @Autowired DocumentAttachmentRepository attachments;
    @Autowired DocumentVersionRepository versions;

    @ParameterizedTest
    @CsvSource({
        "COLLECTED,PENDING,false,PENDING", "COLLECTED,RUNNING,false,PENDING",
        "COLLECTED,ANALYZED,false,FAILED", "COLLECTED,DONE,false,FAILED",
        "COLLECTED,FAILED,false,FAILED", "COMPLETED,NONE,false,FAILED",
        "FAILED,NONE,false,FAILED", "COLLECTED,NONE,false,PENDING",
        "COLLECTED,ANALYZED,true,COMPLETED", "COLLECTED,FAILED,true,COMPLETED",
        "COMPLETED,NONE,true,COMPLETED", "FAILED,NONE,true,COMPLETED"
    })
    void listAndDetailAgreeWithoutInferringFailureFromMissingScore(
            MonitoringRunStatus runStatus, String taskState, boolean hasAnalysis, DocumentAnalysisStatus expected) {
        var detection = fixture("본문", runStatus, taskState);
        if (hasAnalysis) {
            // Legacy successful analyses can have no opportunity score or priority.
            em.persist(DocumentAnalysis.create(detection.getDocumentVersion(), "저장된 분석", "[]",
                    DocumentImportance.NORMAL, "근거", AnalysisEligibility.REVIEW_REQUIRED,
                    AnalysisFavorability.NOT_APPLICABLE, "{\"sections\":[],\"draftStatus\":\"GENERATING\"}",
                    null, null, null, "[]", "test", NOW));
        }
        em.flush();
        em.clear();

        var page = new DocumentDetectionQueryService(detections).findAll(0, 20, null, null);
        assertThat(page.content()).singleElement().satisfies(row -> {
            assertThat(row.analysisStatus()).isEqualTo(expected);
            assertThat(row.opportunityScore()).isNull();
        });
        var detail = detailService().findById(detection.getId());
        assertThat(detail.analysisStatus()).isEqualTo(expected);
        if (expected == DocumentAnalysisStatus.FAILED) {
            assertThat(detail.analysisPending()).isFalse();
            assertThat(detail.analysisFailureReason()).contains("분석을 완료하지 못했어요");
        } else {
            assertThat(detail.analysisFailureReason()).isNull();
        }
        if (hasAnalysis && taskState.equals("ANALYZED")) assertThat(detail.analysisPending()).isTrue();
        var json = new ObjectMapper().valueToTree(detail);
        assertThat(json.path("analysisStatus").asString()).isEqualTo(expected.name());
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED,'',true", "FAILED,읽을 수 없는 첨부,true", "COMPLETED,첨부에 있는 본문,false"})
    void onlySuccessfullyParsedAttachmentTextCountsAsReadable(
            AttachmentParseStatus parseStatus, String text, boolean empty) {
        var detection = fixture(" \n\t", MonitoringRunStatus.COMPLETED, "DONE");
        em.persist(DocumentAttachment.create(detection.getDocumentVersion(), "공고.pdf", "https://example.org/file",
                "pdf", "application/pdf", 100L, "b".repeat(64), text, parseStatus, null));
        em.flush();
        em.clear();
        var detail = detailService().findById(detection.getId());
        assertThat(detail.analysisStatus()).isEqualTo(DocumentAnalysisStatus.FAILED);
        assertThat(detail.analysisFailureReason().contains("읽을 텍스트를 찾지 못했어요")).isEqualTo(empty);
    }

    @Test
    void imageOnlyNoticeAlreadyStoredNeedsNoReanalysisToShowFailure() {
        var detection = fixture(null, MonitoringRunStatus.COMPLETED, "NONE");
        em.flush();
        em.clear();
        var detail = detailService().findById(detection.getId());
        assertThat(detail.analysisStatus()).isEqualTo(DocumentAnalysisStatus.FAILED);
        assertThat(detail.analysisFailureReason()).contains("본문·첨부파일에서 읽을 텍스트", "원문을 확인");
        assertThat(new DocumentDetectionQueryService(detections).findAll(0, 20, null, null).content())
                .extracting(DocumentDetectionSummaryResponse::analysisStatus).containsExactly(DocumentAnalysisStatus.FAILED);
    }

    @Test
    void latestRetryControlsHistoricalRowsWithoutDuplicatingPageOrCount() {
        var old = fixture("본문", MonitoringRunStatus.COMPLETED, "DONE");
        var latest = addDetection(old.getDocumentVersion(), MonitoringRunStatus.COLLECTED, "RUNNING");
        // Same timestamp: the higher detection ID must win.
        em.flush();
        em.clear();
        var query = new DocumentDetectionQueryService(detections);
        var page = query.findAll(0, 1, null, null);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).singleElement().satisfies(row -> {
            assertThat(row.detectionId()).isEqualTo(latest.getId());
            assertThat(row.analysisStatus()).isEqualTo(DocumentAnalysisStatus.PENDING);
        });
        assertThat(query.findAll(0, 20, null, null, old.getMonitoringRunSource().getMonitoringRun().getId()).content())
                .singleElement().satisfies(row -> assertThat(row.analysisStatus()).isEqualTo(DocumentAnalysisStatus.PENDING));
        assertThat(detailService().findById(old.getId()).analysisStatus()).isEqualTo(DocumentAnalysisStatus.PENDING);
        tasks.findByRunId(latest.getMonitoringRunSource().getMonitoringRun().getId()).orElseThrow().complete();
        em.flush();
        em.clear();
        assertThat(query.findAll(0, 20, null, null).content()).hasSize(2)
                .allMatch(row -> row.analysisStatus() == DocumentAnalysisStatus.FAILED);
        assertThat(detailService().findById(old.getId()).analysisStatus()).isEqualTo(DocumentAnalysisStatus.FAILED);
    }

    @Test
    void participationCachePersistsWithoutOverwritingANewerRequest() {
        var version = fixture("참여 자격", MonitoringRunStatus.COMPLETED, "NONE").getDocumentVersion();
        em.flush();
        versions.beginParticipationRewrite(version.getId(), "old");
        versions.beginParticipationRewrite(version.getId(), "new");
        assertThat(versions.finishParticipationRewrite(version.getId(), "old", "wrong")).isZero();
        assertThat(versions.finishParticipationRewrite(version.getId(), "new", "{\"status\":\"READY\"}")).isOne();
        em.clear();
        var stored = versions.findById(version.getId()).orElseThrow();
        assertThat(stored.getParticipationRewriteKey()).isEqualTo("new");
        assertThat(stored.getParticipationRewriteJson()).isEqualTo("{\"status\":\"READY\"}");
        assertThat(stored.getContentText()).isEqualTo("참여 자격");
    }

    private DocumentDetection fixture(String body, MonitoringRunStatus runStatus, String taskState) {
        var source = MonitoringSource.create("검증 기관", "공고", null, "https://example.org", null, 2, true);
        em.persist(source);
        var document = Document.create(source, "https://example.org/notice", "notice", NOW);
        em.persist(document);
        var version = DocumentVersion.create(document, 1, "이미지 공고", body, "a".repeat(64), NOW, 0, NOW);
        em.persist(version);
        return addDetection(version, runStatus, taskState);
    }

    private DocumentDetection addDetection(DocumentVersion version, MonitoringRunStatus status, String taskState) {
        var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, NOW);
        ReflectionTestUtils.setField(run, "status", status);
        em.persist(run);
        var runSource = MonitoringRunSource.create(run, version.getDocument().getMonitoringSource());
        em.persist(runSource);
        var detection = DocumentDetection.create(runSource, version.getDocument(), version, DocumentChangeType.NEW_DOCUMENT, NOW);
        em.persist(detection);
        if (!taskState.equals("NONE")) {
            var task = AnalysisTask.pending(run, NOW);
            ReflectionTestUtils.setField(task, "state", AnalysisTask.State.valueOf(taskState));
            em.persist(task);
        }
        return detection;
    }

    private DocumentDetectionDetailService detailService() {
        return new DocumentDetectionDetailService(detections, analyses, tasks, attachments, new ObjectMapper(), rewriteService());
    }
    private com.publicmonitor.backend.domain.document.service.ParticipationRewriteService rewriteService() {
        var service = org.mockito.Mockito.mock(com.publicmonitor.backend.domain.document.service.ParticipationRewriteService.class);
        org.mockito.Mockito.lenient().when(service.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return service;
    }
}
