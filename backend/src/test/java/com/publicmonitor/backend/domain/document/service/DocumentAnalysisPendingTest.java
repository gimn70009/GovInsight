package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisEligibility;
import com.publicmonitor.backend.domain.analysis.entity.AnalysisFavorability;
import com.publicmonitor.backend.domain.analysis.entity.AnalysisTask;
import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.analysis.repository.AnalysisTaskRepository;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.document.entity.Document;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.repository.DocumentAttachmentRepository;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DocumentAnalysisPendingTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 9, 0);
    private static final long VERSION_ID = 3L;
    private static final long DETECTION_ID = 4L;

    @Mock DocumentDetectionRepository detections;
    @Mock DocumentAnalysisRepository analyses;
    @Mock AnalysisTaskRepository tasks;
    @Mock DocumentAttachmentRepository attachments;

    @ParameterizedTest
    @EnumSource(AnalysisTask.State.class)
    void 분석_작업의_완료와_실패는_보고서_완료를_기다리지_않는다(AnalysisTask.State state) {
        DocumentDetection detection = prepare(MonitoringRunStatus.COLLECTED);
        AnalysisTask task = task(detection, state);
        given(tasks.findByRunId(1L)).willReturn(Optional.of(task));

        var response = service().findById(DETECTION_ID);

        assertThat(response.analysis()).isNull();
        assertThat(response.analysisPending())
                .isEqualTo(state != AnalysisTask.State.DONE && state != AnalysisTask.State.FAILED);
    }

    @ParameterizedTest
    @EnumSource(MonitoringRunStatus.class)
    void 작업_행이_없는_기존_실행은_진행_상태로_판단한다(MonitoringRunStatus status) {
        prepare(status);

        var response = service().findById(DETECTION_ID);

        assertThat(response.analysisPending()).isEqualTo(MonitoringRunStatus.inProgress().contains(status));
        assertThat(response.analysis()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = MonitoringRunStatus.class, names = {"COMPLETED", "FAILED"})
    void 실행이_종료되면_오래된_제안_생성중_표시도_대기하지_않는다(MonitoringRunStatus status) {
        DocumentDetection detection = prepare(status);
        given(analyses.findByDocumentVersionId(VERSION_ID)).willReturn(Optional.of(analysis(detection, "GENERATING")));

        var response = service().findById(DETECTION_ID);

        assertThat(response.analysisPending()).isFalse();
        assertThat(response.analysis().proposal().draftStatus()).isEqualTo("GENERATING");
        verifyNoInteractions(tasks);
    }

    @Test
    void 같은_버전의_최신_실행이_분석중이면_과거_감지_상세도_갱신을_기다린다() {
        DocumentDetection old = prepare(MonitoringRunStatus.COMPLETED);
        MonitoringRun latestRun = run(2L, MonitoringRunStatus.COLLECTED);
        DocumentDetection latest = detection(5L, latestRun, old.getDocumentVersion());
        given(detections.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(VERSION_ID))
                .willReturn(Optional.of(latest));
        given(tasks.findByRunId(2L)).willReturn(Optional.of(task(latest, AnalysisTask.State.RUNNING)));

        var response = service().findById(DETECTION_ID);

        assertThat(response.detectionId()).isEqualTo(DETECTION_ID);
        assertThat(response.analysis()).isNull();
        assertThat(response.analysisPending()).isTrue();
    }

    @Test
    void 같은_버전의_최신_실행이_종료되면_이전_작업_대기에_영향받지_않는다() {
        DocumentDetection old = prepare(MonitoringRunStatus.COLLECTED);
        DocumentDetection latest = detection(5L, run(2L, MonitoringRunStatus.COMPLETED), old.getDocumentVersion());
        given(detections.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(VERSION_ID))
                .willReturn(Optional.of(latest));

        assertThat(service().findById(DETECTION_ID).analysisPending()).isFalse();
        verifyNoInteractions(tasks);
    }

    @Test
    void 종료_상태를_조회하는_동안_저장된_최종_결과를_같은_응답에_담는다() {
        DocumentDetection detection = prepare(MonitoringRunStatus.COLLECTED);
        AtomicReference<DocumentAnalysis> stored = new AtomicReference<>();
        given(tasks.findByRunId(1L)).willAnswer(invocation -> {
            stored.set(analysis(detection, "READY"));
            return Optional.of(task(detection, AnalysisTask.State.DONE));
        });
        given(analyses.findByDocumentVersionId(VERSION_ID))
                .willAnswer(invocation -> Optional.ofNullable(stored.get()));

        var response = service().findById(DETECTION_ID);

        assertThat(response.analysisPending()).isFalse();
        assertThat(response.analysis()).isNotNull();
        assertThat(response.analysis().proposal().draftStatus()).isEqualTo("READY");
        assertThat(new ObjectMapper().valueToTree(response).path("analysisPending").asBoolean()).isFalse();
    }

    @Test
    void 작업_실패_후에도_이미_저장한_분석은_반환한다() {
        DocumentDetection detection = prepare(MonitoringRunStatus.COLLECTED);
        given(tasks.findByRunId(1L)).willReturn(Optional.of(task(detection, AnalysisTask.State.FAILED)));
        given(analyses.findByDocumentVersionId(VERSION_ID)).willReturn(Optional.of(analysis(detection, "FAILED")));

        var response = service().findById(DETECTION_ID);

        assertThat(response.analysisPending()).isFalse();
        assertThat(response.analysis().summary()).isEqualTo("저장된 분석");
        assertThat(response.analysis().proposal().draftStatus()).isEqualTo("FAILED");
    }

    private DocumentDetection prepare(MonitoringRunStatus status) {
        MonitoringSource source = MonitoringSource.create("기관", "공고", null, "https://example.com", null, 2, true);
        Document document = Document.create(source, "https://example.com/notice", "notice", NOW);
        DocumentVersion version = DocumentVersion.create(document, 1, "공고", "본문", "a".repeat(64), NOW, 0, NOW);
        ReflectionTestUtils.setField(version, "id", VERSION_ID);
        DocumentDetection detection = detection(DETECTION_ID, run(1L, status), version);
        given(detections.findById(DETECTION_ID)).willReturn(Optional.of(detection));
        return detection;
    }

    private MonitoringRun run(long id, MonitoringRunStatus status) {
        MonitoringRun run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, NOW);
        ReflectionTestUtils.setField(run, "id", id);
        ReflectionTestUtils.setField(run, "status", status);
        return run;
    }

    private DocumentDetection detection(long id, MonitoringRun run, DocumentVersion version) {
        MonitoringRunSource source = MonitoringRunSource.create(run, version.getDocument().getMonitoringSource());
        DocumentDetection detection = DocumentDetection.create(source, version.getDocument(), version,
                DocumentChangeType.UNCHANGED_DOCUMENT, NOW.plusMinutes(id));
        ReflectionTestUtils.setField(detection, "id", id);
        return detection;
    }

    private AnalysisTask task(DocumentDetection detection, AnalysisTask.State state) {
        AnalysisTask task = AnalysisTask.pending(detection.getMonitoringRunSource().getMonitoringRun(), NOW);
        ReflectionTestUtils.setField(task, "state", state);
        return task;
    }

    private DocumentAnalysis analysis(DocumentDetection detection, String draftStatus) {
        return DocumentAnalysis.create(detection.getDocumentVersion(), "저장된 분석", "[]", DocumentImportance.NORMAL,
                "근거", AnalysisEligibility.REVIEW_REQUIRED, AnalysisFavorability.NOT_APPLICABLE,
                "{\"sections\":[],\"draftStatus\":\"" + draftStatus + "\"}",
                null, null, "[]", "test-model", NOW);
    }

    private DocumentDetectionDetailService service() {
        return new DocumentDetectionDetailService(detections, analyses, tasks, attachments, new ObjectMapper());
    }
}
