package com.publicmonitor.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisEligibility;
import com.publicmonitor.backend.domain.analysis.entity.AnalysisFavorability;
import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityDimensionType;
import com.publicmonitor.backend.domain.analysis.repository.AnalysisDocumentDetectionRepository;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.analysis.web.dto.AnalysisResultRequest;
import com.publicmonitor.backend.domain.analysis.web.dto.AnalysisResultResponse;
import com.publicmonitor.backend.domain.analysis.web.dto.ProposalResultRequest;
import com.publicmonitor.backend.domain.analysis.web.dto.ProposalResultResponse;
import com.publicmonitor.backend.domain.document.entity.Document;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.report.event.ProposalCompletedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AnalysisResultServiceTest {

    @Mock MonitoringRunRepository runRepository;
    @Mock AnalysisDocumentDetectionRepository detectionRepository;
    @Mock DocumentAnalysisRepository analysisRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    private AnalysisResultService service;
    private MonitoringRun run;
    private DocumentDetection detection;

    @BeforeEach
    void setUp() {
        service = new AnalysisResultService(
                runRepository,
                detectionRepository,
                analysisRepository,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-21T01:00:00Z"), ZoneOffset.UTC),
                eventPublisher
        );
        MonitoringSource source = MonitoringSource.create(
                "산업통상부", "사업공고", null, "https://example.com", null, 3, true
        );
        ReflectionTestUtils.setField(source, "id", 1L);
        run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, LocalDateTime.now());
        ReflectionTestUtils.setField(run, "id", 10L);
        ReflectionTestUtils.setField(run, "status", MonitoringRunStatus.COLLECTED);
        MonitoringRunSource runSource = MonitoringRunSource.create(run, source);
        ReflectionTestUtils.setField(runSource, "id", 20L);
        Document document = Document.create(source, "https://example.com/1", "1", LocalDateTime.now());
        ReflectionTestUtils.setField(document, "id", 30L);
        DocumentVersion version = DocumentVersion.create(
                document, 1, "지원사업", "본문", "a".repeat(64), null, 0, LocalDateTime.now()
        );
        ReflectionTestUtils.setField(version, "id", 40L);
        detection = DocumentDetection.create(
                runSource, document, version, DocumentChangeType.NEW_DOCUMENT, LocalDateTime.now()
        );
        ReflectionTestUtils.setField(detection, "id", 50L);
        given(runRepository.findById(10L)).willReturn(Optional.of(run));
        given(detectionRepository.findById(50L)).willReturn(Optional.of(detection));
    }

    @Test
    void 분석_결과를_문서_버전에_저장한다() {
        given(analysisRepository.findByDocumentVersionId(40L)).willReturn(Optional.empty());

        AnalysisResultResponse response = service.receive(request());

        ArgumentCaptor<DocumentAnalysis> captor = ArgumentCaptor.forClass(DocumentAnalysis.class);
        verify(analysisRepository).save(captor.capture());
        assertThat(captor.getValue().getSummary()).contains("지원사업");
        assertThat(captor.getValue().getKeyPoints()).contains("신청 기한");
        assertThat(captor.getValue().getImportance()).isEqualTo(DocumentImportance.HIGH);
        assertThat(captor.getValue().getEligibility()).isEqualTo(AnalysisEligibility.REVIEW_REQUIRED);
        assertThat(captor.getValue().getFavorableOrNot()).isEqualTo(AnalysisFavorability.NOT_APPLICABLE);
        assertThat(captor.getValue().getProposalDirection())
                .contains("sections", "핵심 판단", "제조 AI");
        assertThat(captor.getValue().getOpportunityScore()).isEqualTo(75);
        assertThat(captor.getValue().getOpportunityAssessment())
                .contains("COMPANY_FIT", "URGENCY");
        assertThat(response.storedAnalysisCount()).isEqualTo(1);
        assertThat(response.duplicateAnalysisCount()).isZero();
    }

    @Test
    void 같은_문서_버전의_분석_결과는_중복_저장하지_않는다() {
        DocumentAnalysis existing = mock(DocumentAnalysis.class);
        given(existing.requiresProposalSchemaUpgrade()).willReturn(false);
        given(analysisRepository.findByDocumentVersionId(40L)).willReturn(Optional.of(existing));

        AnalysisResultResponse response = service.receive(request());

        assertThat(response.storedAnalysisCount()).isZero();
        assertThat(response.duplicateAnalysisCount()).isEqualTo(1);
    }

    @Test
    void 기존_사업제안_스키마는_새_분석_결과로_교체한다() {
        DocumentAnalysis existing = mock(DocumentAnalysis.class);
        given(existing.requiresProposalSchemaUpgrade()).willReturn(true);
        given(analysisRepository.findByDocumentVersionId(40L)).willReturn(Optional.of(existing));

        AnalysisResultResponse response = service.receive(request());

        verify(existing).replaceAnalysis(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any()
        );
        verify(analysisRepository, never()).save(any());
        assertThat(response.storedAnalysisCount()).isEqualTo(1);
        assertThat(response.duplicateAnalysisCount()).isZero();
    }

    @Test
    void 분석이_전부_실패하면_보고서_생성_이벤트를_발행하지_않는다() {
        AnalysisResultRequest failedRequest = new AnalysisResultRequest(
                10L,
                UUID.fromString("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
                List.of(),
                List.of(new AnalysisResultRequest.AnalysisFailure(
                        50L,
                        30L,
                        40L,
                        "AI 모델 응답 시간이 초과되었습니다."
                ))
        );

        AnalysisResultResponse response = service.receive(failedRequest);

        assertThat(response.storedAnalysisCount()).isZero();
        assertThat(response.failedAnalysisCount()).isEqualTo(1);
        verify(eventPublisher, never()).publishEvent(any(ProposalCompletedEvent.class));
    }

    @Test
    void 먼저_저장된_분석에_사업_제안만_갱신한다() {
        DocumentAnalysis existing = mock(DocumentAnalysis.class);
        given(analysisRepository.findByDocumentVersionId(40L)).willReturn(Optional.of(existing));
        ProposalResultRequest proposalRequest = new ProposalResultRequest(
                10L,
                UUID.fromString("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
                List.of(new ProposalResultRequest.ProposalUpdate(
                        50L,
                        30L,
                        40L,
                        new AnalysisResultRequest.Proposal(List.of(
                                new AnalysisResultRequest.Section(
                                        "핵심 판단",
                                        "사업 제안 결과를 별도 백그라운드 단계에서 생성했습니다."
                                )
                        )),
                        List.of("get_document_content", "build_proposal_preparation")
                ))
        );

        ProposalResultResponse response = service.receiveProposal(proposalRequest);

        verify(existing).updateProposal(any(), any(), any());
        verify(eventPublisher).publishEvent(any(ProposalCompletedEvent.class));
        assertThat(response.updatedProposalCount()).isEqualTo(1);
    }

    private AnalysisResultRequest.OpportunityDimension opportunityDimension(
            OpportunityDimensionType type,
            int score
    ) {
        return new AnalysisResultRequest.OpportunityDimension(
                type,
                score,
                "공고 원문과 회사 프로필을 기준으로 산정한 평가 근거입니다."
        );
    }

    private AnalysisResultRequest request() {
        return new AnalysisResultRequest(
                10L,
                UUID.fromString("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
                List.of(new AnalysisResultRequest.AnalysisResult(
                        50L,
                        30L,
                        40L,
                        "지원사업의 신청 기한과 대상을 정리한 분석 결과입니다.",
                        List.of("신청 기한 확인", "지원 대상 검토"),
                        DocumentImportance.HIGH,
                        "접수 기한이 있어 빠른 검토가 필요합니다.",
                        AnalysisEligibility.REVIEW_REQUIRED,
                        AnalysisFavorability.NOT_APPLICABLE,
                        new AnalysisResultRequest.Proposal(List.of(
                                new AnalysisResultRequest.Section(
                                        "핵심 판단",
                                        "제조 AI 기술을 활용한 사업 제안 가능성을 검토합니다."
                                )
                        )),
                        new AnalysisResultRequest.Opportunity(List.of(
                                opportunityDimension(OpportunityDimensionType.COMPANY_FIT, 80),
                                opportunityDimension(OpportunityDimensionType.BUSINESS_VALUE, 70),
                                opportunityDimension(OpportunityDimensionType.FEASIBILITY, 60),
                                opportunityDimension(OpportunityDimensionType.URGENCY, 90)
                        )),
                        List.of("get_document_content"),
                        "gpt-5-mini"
                )),
                List.of()
        );
    }
}
