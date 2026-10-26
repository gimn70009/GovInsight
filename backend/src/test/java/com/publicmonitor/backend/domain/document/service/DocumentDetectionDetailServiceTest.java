package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisEligibility;
import com.publicmonitor.backend.domain.analysis.repository.AnalysisTaskRepository;
import com.publicmonitor.backend.domain.analysis.entity.AnalysisFavorability;
import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.Document;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.repository.DocumentAttachmentRepository;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DocumentDetectionDetailServiceTest {

    @Mock DocumentDetectionRepository detectionRepository;
    @Mock DocumentAnalysisRepository analysisRepository;
    @Mock AnalysisTaskRepository analysisTaskRepository;
    @Mock DocumentAttachmentRepository attachmentRepository;

    @Test
    void 데모_사용_표시는_저장_JSON을_거쳐_유지되고_기존_결과는_미사용으로_표시한다() {
        ObjectMapper mapper = new ObjectMapper();
        var incoming = mapper.readValue(
                "{\"sections\":[],\"usesDemoProfile\":true}",
                com.publicmonitor.backend.domain.analysis.web.dto.AnalysisResultRequest.Proposal.class
        );
        String stored = mapper.writeValueAsString(incoming);
        com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.Proposal parsed =
                ReflectionTestUtils.invokeMethod(service(), "parseProposal", stored);
        assertThat(parsed.usesDemoProfile()).isTrue();
        com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.Proposal legacy =
                ReflectionTestUtils.invokeMethod(service(), "parseProposal", "{\"sections\":[]}");
        assertThat(legacy.usesDemoProfile()).isFalse();
        var legacyInput = mapper.readValue(
                "{\"sections\":[]}",
                com.publicmonitor.backend.domain.analysis.web.dto.AnalysisResultRequest.Proposal.class
        );
        assertThat(legacyInput.usesDemoProfile()).isFalse();
        var pair = mapper.readValue(
                "{\"status\":\"COMPLETED\",\"insights\":[],\"message\":null,\"usesDemoProfile\":true}",
                com.publicmonitor.backend.domain.document.web.dto.LegalPairResponse.class
        );
        assertThat(pair.usesDemoProfile()).isTrue();
    }

    @Test
    void 프론트가_바로_사용할_수_있는_구조화된_상세를_반환한다() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 9, 0);
        MonitoringSource source = MonitoringSource.create(
                "과학기술정보통신부", "사업공고", null, "https://example.com", null, 2, true
        );
        ReflectionTestUtils.setField(source, "id", 1L);
        MonitoringRun run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, now);
        MonitoringRunSource runSource = MonitoringRunSource.create(run, source);
        Document document = Document.create(source, "https://example.com/notice/1", "1", now);
        ReflectionTestUtils.setField(document, "id", 2L);
        DocumentVersion version = DocumentVersion.create(
                document, 1, "AI 지원사업", "운영기관 : 한국산업진흥원", "a".repeat(64), now, 1, now
        );
        ReflectionTestUtils.setField(version, "id", 3L);
        DocumentDetection detection = DocumentDetection.create(
                runSource, document, version, DocumentChangeType.NEW_DOCUMENT, now
        );
        ReflectionTestUtils.setField(detection, "id", 4L);
        DocumentAnalysis analysis = DocumentAnalysis.create(
                version,
                "제조 AI 사업화를 지원하는 공고입니다.",
                "[\"신청 자격 확인\",\"제출 기한 확인\"]",
                DocumentImportance.HIGH,
                "신청 기한이 있어 빠른 검토가 필요합니다.",
                AnalysisEligibility.REVIEW_REQUIRED,
                AnalysisFavorability.NOT_APPLICABLE,
                "{\"sections\":[{\"title\":\"핵심 판단\",\"body\":\"제조 데이터 분석 역량을 활용한 참여 방향을 검토합니다.\"}]}",
                74,
                OpportunityPriority.HIGH,
                opportunityJson(),
                "[\"get_document_content\",\"get_company_profile\"]",
                "mock-model",
                now
        );
        analysis.updateComparisonSummary("""
                {"applicationDeadline":"2026-10-01 18:00",
                 "purpose":"제조 AI 실증 지원",
                 "supportScale":"과제당 연간 최대 2억 원, 2년 이내 / 자부담 25% 이상"}
                """);
        DocumentAttachment attachment = DocumentAttachment.create(
                version, "공고문.hwpx", "https://example.com/file/1", "hwpx",
                "application/zip", 100L, "b".repeat(64), "주관기관 : 한국기술재단\n□ 신청자격: 업력 7년 이내 기업\n신청서류\n① 사업계획서",
                AttachmentParseStatus.COMPLETED, null
        );
        ReflectionTestUtils.setField(attachment, "id", 5L);
        given(detectionRepository.findById(4L)).willReturn(Optional.of(detection));
        given(analysisRepository.findByDocumentVersionId(3L)).willReturn(Optional.of(analysis));
        given(attachmentRepository.findAllByDocumentVersionId(3L)).willReturn(List.of(attachment));

        var response = service().findById(4L);

        assertThat(response.businessType().status()).isEqualTo("REVIEW_REQUIRED");

        assertThat(response.organizationName()).isEqualTo("과학기술정보통신부");
        assertThat(response.title()).isEqualTo("AI 지원사업");
        assertThat(response.attachments()).hasSize(1);
        assertThat(response.analysis().keyPoints()).containsExactly("신청 자격 확인", "제출 기한 확인");
        assertThat(response.analysis().applicationDeadline()).isEqualTo("2026-10-01 18:00");
        assertThat(response.analysis().businessSummary().purpose()).isEqualTo("제조 AI 실증 지원");
        assertThat(response.analysis().businessSummary().supportScale())
                .isEqualTo("과제당 연간 최대 2억 원, 2년 이내 / 자부담 25% 이상");
        assertThat(response.responsibleOrganizations()).extracting(item -> item.name())
                .containsExactly("한국산업진흥원", "한국기술재단");
        assertThat(response.responsibleOrganizations().getLast().attachmentName()).isEqualTo("공고문.hwpx");
        assertThat(response.analysis().eligibility()).isEqualTo(AnalysisEligibility.REVIEW_REQUIRED);
        assertThat(response.analysis().opportunity().totalScore()).isEqualTo(75);
        assertThat(response.analysis().opportunity().priority().name()).isEqualTo("HIGH");
        assertThat(response.analysis().opportunity().dimensions()).hasSize(4);
        assertThat(response.analysis().proposal().sections())
                .singleElement()
                .satisfies(section -> {
                    assertThat(section.title()).isEqualTo("핵심 판단");
                    assertThat(section.body()).contains("제조 데이터 분석 역량");
                });
        assertThat(response.attachments()).singleElement().satisfies(item ->
                assertThat(item.parseStatus()).isEqualTo(AttachmentParseStatus.COMPLETED)
        );

        given(analysisRepository.findByDocumentVersionId(3L)).willReturn(Optional.empty());
        var withoutAnalysis = service().findById(4L);
        assertThat(withoutAnalysis.analysis()).isNull();
        assertThat(withoutAnalysis.submissionGuide().documents()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("사업계획서");
            assertThat(item.source().attachmentName()).isEqualTo("공고문.hwpx");
        });
        assertThat(withoutAnalysis.responsibleOrganizations()).isEqualTo(response.responsibleOrganizations());
        assertThat(withoutAnalysis.participationGuide()).isEqualTo(response.participationGuide());
        assertThat(withoutAnalysis.participationGuide().conditions()).singleElement().satisfies(item -> {
            assertThat(item.text()).isEqualTo("업력이 7년 이내인 기업이 대상입니다.");
            assertThat(item.kind().name()).isEqualTo("MANDATORY");
            assertThat(item.source().attachmentName()).isEqualTo("공고문.hwpx");
        });
    }

    @Test
    void 기존_상세_조회에서_일반공지_분류와_미확인_유형을_구분한다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 16, 9, 0);
        MonitoringSource source = MonitoringSource.create("기관", "공지사항", null, "https://example.com", null, 2, true);
        MonitoringRun run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, now);
        MonitoringRunSource runSource = MonitoringRunSource.create(run, source);
        Document document = Document.create(source, "https://example.com/notice/1", "1", now);
        DocumentVersion version = DocumentVersion.create(
                document, 1, "기관 현황 안내", "현재 기관 현황을 알려 드립니다.", "a".repeat(64), now, 0, now);
        ReflectionTestUtils.setField(version, "id", 3L);
        DocumentDetection detection = DocumentDetection.create(
                runSource, document, version, DocumentChangeType.NEW_DOCUMENT, now);
        given(detectionRepository.findById(4L)).willReturn(Optional.of(detection));
        given(attachmentRepository.findAllByDocumentVersionId(3L)).willReturn(List.of());

        for (String type : List.of("GENERAL_NOTICE", "REVIEW_REQUIRED", "BUSINESS_NOTICE")) {
            DocumentAnalysis analysis = DocumentAnalysis.create(
                    version, "기관 현황을 안내하는 공고입니다.", "[]", DocumentImportance.LOW,
                    "공고의 성격을 확인합니다.", AnalysisEligibility.REVIEW_REQUIRED,
                    AnalysisFavorability.NOT_APPLICABLE,
                    "{\"sections\":[],\"documentType\":\"" + type + "\"}", 0, OpportunityPriority.LOW,
                    opportunityJson(), "[]", "mock-model", now);
            analysis.updateComparisonSummary("{\"businessType\":null}");
            given(analysisRepository.findByDocumentVersionId(3L)).willReturn(Optional.of(analysis));
            var response = service().findById(4L);
            assertThat(response.businessType().status())
                    .isEqualTo(type.equals("GENERAL_NOTICE") ? "NOT_APPLICABLE" : "REVIEW_REQUIRED");
        }
        given(analysisRepository.findByDocumentVersionId(3L)).willReturn(Optional.empty());
        assertThat(service().findById(4L).businessType().status()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void 존재하지_않는_감지_문서는_예외를_반환한다() {
        given(detectionRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().findById(999L))
                .isInstanceOf(DocumentDetectionException.class);
    }

    @Test
    void 마감일이_없는_기존_결과나_손상된_JSON도_조회한다() {
        for (String json : new String[] {null, "", "{}", "null", "{broken", "{\"applicationDeadline\":12}"}) {
            String deadline = ReflectionTestUtils.invokeMethod(service(), "parseApplicationDeadline", json);
            assertThat(deadline).isNull();
        }
    }

    @Test
    void 기존_상세와_메일용_마감일에서_날짜와_접수조건을_보존하고_종료단정을_제거한다() {
        String stored = "{\"applicationDeadline\":\"2026-10-29 (목) 23:59:00, 시스템 접수 기준으로 마감되었습니다.\"}";
        String deadline = ReflectionTestUtils.invokeMethod(service(), "parseApplicationDeadline", stored);
        assertThat(deadline).isEqualTo("2026-10-29 (목) 23:59:00, 시스템 접수 기준 마감입니다.");
        assertThat(stored).contains("마감되었습니다");
    }

    @Test
    void 사업요약이_없거나_손상되어도_기존_상세를_조회한다() {
        for (String json : new String[] {null, "", "{broken"}) {
            Object summary = ReflectionTestUtils.invokeMethod(service(), "parseBusinessSummary", json);
            assertThat(summary).isNull();
        }
        for (String json : new String[] {"{}", "null", "[]", "12"}) {
            com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.BusinessSummary summary =
                    ReflectionTestUtils.invokeMethod(service(), "parseBusinessSummary", json);
            assertThat(summary.purpose()).isNull();
            assertThat(summary.supportScale()).isNull();
        }
    }

    @Test
    void 사업요약의_잘못된_자료형은_제외하고_정상_필드는_보존한다() {
        com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.BusinessSummary summary =
                ReflectionTestUtils.invokeMethod(service(), "parseBusinessSummary",
                        """
                        {"purpose":42,"supportScale":"  총 9.03억 원 내외 (전체 연구기간 기준)  "}
                        """);
        assertThat(summary.purpose()).isNull();
        assertThat(summary.supportScale()).isEqualTo("총 9.03억 원 내외 (전체 연구기간 기준)");
    }

    @Test
    void 저장된_사업유형은_공고문_표제_뒤에서도_상세로_전달되고_로그는_원문을_노출하지_않는다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 16, 10, 0);
        MonitoringSource source = MonitoringSource.create("테스트기관", "사업공고", null, "https://example.com", null, 2, true);
        MonitoringRun run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, now);
        MonitoringRunSource runSource = MonitoringRunSource.create(run, source);
        Document document = Document.create(source, "https://example.com/notice/1", "1", now);
        String quote = "기업의 생산 시스템 구축을 지원합니다.";
        String body = "붙임 1 공고문\n□ 지원내용: " + quote;
        DocumentVersion version = DocumentVersion.create(document, 1, "로그에남기지않을제목", body, "a".repeat(64), now, 0, now);
        ReflectionTestUtils.setField(version, "id", 3L);
        DocumentDetection detection = DocumentDetection.create(runSource, document, version, DocumentChangeType.NEW_DOCUMENT, now);
        ReflectionTestUtils.setField(detection, "id", 4L);
        DocumentAnalysis analysis = DocumentAnalysis.create(version, "시스템 구축을 지원합니다.", "[]", DocumentImportance.LOW,
                "현재 지원 내용을 확인했습니다.", AnalysisEligibility.REVIEW_REQUIRED, AnalysisFavorability.NOT_APPLICABLE,
                "{\"sections\":[],\"documentType\":\"BUSINESS_NOTICE\"}", 0, OpportunityPriority.LOW,
                opportunityJson(), "[]", "mock-model", now);
        analysis.updateComparisonSummary(new ObjectMapper().writeValueAsString(java.util.Map.of("businessType", java.util.Map.of(
                "primaryType", "ADOPTION", "explanation", quote, "details", List.of(), "sources",
                List.of(java.util.Map.of("origin", "NOTICE_BODY", "excerpt", quote))))));
        given(detectionRepository.findById(4L)).willReturn(Optional.of(detection));
        given(analysisRepository.findByDocumentVersionId(3L)).willReturn(Optional.of(analysis));
        given(attachmentRepository.findAllByDocumentVersionId(3L)).willReturn(List.of());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DocumentDetectionDetailService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = service().findById(4L);
            assertThat(response.businessType().code()).isEqualTo("ADOPTION");
            assertThat(response.businessType().sources().getFirst().excerpt()).isEqualTo(quote);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("detection_id=4 version_id=3", "decision_reason=SAVED_CLASSIFICATION", "stored_reason=VALIDATED")
                    .doesNotContain(quote, body, "로그에남기지않을제목", "https://example.com");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }


    @Test
    void 기존_일본_공고를_조회할_때_원문을_변경하지_않고_접수기관만_반환한다() throws Exception {
        String text;
        try (var stream = getClass().getResourceAsStream("/organizations/japan-globalstars.txt")) {
            text = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        LocalDateTime now = LocalDateTime.of(2026, 10, 12, 9, 0);
        MonitoringSource source = MonitoringSource.create("한국산업기술진흥원", "사업공고", null,
                "https://www.kiat.or.kr", null, 2, true);
        MonitoringRun run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, now);
        MonitoringRunSource runSource = MonitoringRunSource.create(run, source);
        Document document = Document.create(source, "https://www.kiat.or.kr/notice", "japan", now);
        String body = "2026년도 일본 다자간 국제공동기술개발사업 공고";
        DocumentVersion version = DocumentVersion.create(document, 1, body, body, "a".repeat(64), now, 1, now);
        ReflectionTestUtils.setField(version, "id", 3L);
        DocumentDetection detection = DocumentDetection.create(runSource, document, version,
                DocumentChangeType.NEW_DOCUMENT, now);
        ReflectionTestUtils.setField(detection, "id", 4L);
        DocumentAttachment attachment = DocumentAttachment.create(version, "첨부 일체.zip",
                "https://example.org/attachments.zip", "zip", "application/zip", 1L, "b".repeat(64),
                text, AttachmentParseStatus.COMPLETED, null);
        given(detectionRepository.findById(4L)).willReturn(Optional.of(detection));
        given(analysisRepository.findByDocumentVersionId(3L)).willReturn(Optional.empty());
        given(attachmentRepository.findAllByDocumentVersionId(3L)).willReturn(List.of(attachment));

        var response = service().findById(4L);

        assertThat(response.responsibleOrganizations()).extracting(item -> item.name())
                .containsExactly("유레카 사무국", "한국산업기술진흥원");
        assertThat(response.responsibleOrganizations()).allSatisfy(item -> {
            assertThat(item.role()).isEqualTo("접수처");
            assertThat(text).contains(item.excerpt());
        });
        assertThat(response.analysis()).isNull();
        assertThat(version.getContentText()).isEqualTo(body);
        assertThat(attachment.getExtractedText()).isEqualTo(text);
        org.mockito.Mockito.verify(analysisRepository, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(attachmentRepository, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any());
    }

    private String opportunityJson() {
        return """
                {"dimensions":[
                  {"type":"COMPANY_FIT","score":80,"reason":"회사 기술과 관련성이 높습니다."},
                  {"type":"BUSINESS_VALUE","score":70,"reason":"사업 실적 확보에 도움이 됩니다."},
                  {"type":"FEASIBILITY","score":60,"reason":"지원 자격 확인이 필요합니다."},
                  {"type":"URGENCY","score":90,"reason":"신청 기한이 임박했습니다."},
                  {"type":"EVIDENCE_CONFIDENCE","score":65,"reason":"회사 정보의 추가 확인이 필요합니다."}
                ]}
                """;
    }

    private DocumentDetectionDetailService service() {
        return new DocumentDetectionDetailService(
                detectionRepository,
                analysisRepository,
                analysisTaskRepository,
                attachmentRepository,
                new ObjectMapper(), rewriteService()
        );
    }
    private com.publicmonitor.backend.domain.document.service.ParticipationRewriteService rewriteService() {
        var service = org.mockito.Mockito.mock(com.publicmonitor.backend.domain.document.service.ParticipationRewriteService.class);
        org.mockito.Mockito.lenient().when(service.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return service;
    }
}
