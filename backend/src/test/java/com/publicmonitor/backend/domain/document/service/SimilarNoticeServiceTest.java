package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisEligibility;
import com.publicmonitor.backend.domain.analysis.entity.AnalysisFavorability;
import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.document.entity.Document;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
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
class SimilarNoticeServiceTest {

    @Mock DocumentDetectionRepository detectionRepository;
    @Mock DocumentAnalysisRepository analysisRepository;

    @Test
    void 사업_목적과_수행_내용이_모두_유사한_다른_기관_공고를_반환한다() {
        Fixture current = fixture(1L, "국토교통부", "제조 AI 실증 지원사업",
                "제조 현장 데이터를 활용한 AI 공정 최적화 실증과 제조기업 사업화를 지원합니다.",
                List.of(1.0, 0.0, 0.0));
        Fixture similar = fixture(2L, "산업통상부", "제조 AI 실증 사업 공고",
                "제조 현장 데이터를 활용한 AI 공정 최적화 실증과 제조기업 사업화를 지원합니다.",
                List.of(0.98, 0.1, 0.0));
        similar.analysis().updateComparisonSummary("""
                {"purpose":"제조기업의 AI 공정 최적화 실증과 사업화를 지원합니다.",
                "supportScale":"과제당 최대 5억 원을 지원합니다.",
                "applicationDeadline":"2026년 9월 30일까지 신청해야 합니다.",
                "eligibility":"제조 분야 중소·중견기업이 신청할 수 있습니다.",
                "requiredPartner":"해외 연구기관과 컨소시엄을 구성해야 합니다.",
                "legalRisks":[
                  {"type":"DUPLICATE_SUPPORT","status":"RESTRICTION_FOUND","summary":"동일 과제의 중복 신청은 제한됩니다.","evidenceExcerpt":"동일 과제 중복 신청 불가"},
                  {"type":"COST_DOUBLE_COUNTING","status":"NOT_FOUND","summary":"원문에서 관련 제한을 확인하지 못했습니다."},
                  {"type":"RESULT_IP_REUSE","status":"NOT_FOUND","summary":"원문에서 관련 제한을 확인하지 못했습니다."},
                  {"type":"CONFIDENTIALITY","status":"NOT_FOUND","summary":"원문에서 관련 제한을 확인하지 못했습니다."},
                  {"type":"PROPOSAL_TEXT_REUSE","status":"NOT_FOUND","summary":"원문에서 관련 제한을 확인하지 못했습니다."}
                ]}
                """);
        given(detectionRepository.findById(current.detection().getId())).willReturn(Optional.of(current.detection()));
        given(analysisRepository.findByDocumentVersionId(current.version().getId())).willReturn(Optional.of(current.analysis()));
        given(analysisRepository.findAll()).willReturn(List.of(current.analysis(), similar.analysis()));
        given(detectionRepository.findTopByDocumentIdOrderByDetectedAtDescIdDesc(similar.document().getId()))
                .willReturn(Optional.of(similar.detection()));

        var result = service().find(current.detection().getId());

        assertThat(result.similarNotices()).singleElement().satisfies(notice -> {
            assertThat(notice.title()).isEqualTo("제조 AI 실증 사업 공고");
            assertThat(notice.comparison().organizationName()).isEqualTo("산업통상부");
            assertThat(notice.similarityScore()).isGreaterThanOrEqualTo(78);
            assertThat(notice.commonPoints()).contains("제조", "ai", "실증");
            assertThat(notice.commonPoints()).doesNotContain("같은 유형의 지원사업");
            assertThat(notice.comparison().purpose())
                    .isEqualTo("제조기업의 AI 공정 최적화 실증과 사업화를 지원합니다.");
            assertThat(notice.comparison().requiredPartner())
                    .isEqualTo("해외 연구기관과 컨소시엄을 구성해야 합니다.");
            assertThat(notice.legalReview().overallStatus()).isEqualTo("HIGH");
            assertThat(notice.legalReview().checks()).hasSize(5);
            assertThat(notice.legalReview().checks().getFirst().evidence())
                    .contains("동일 과제 중복 신청 불가");
            assertThat(notice.legalReview().summary()).contains("제조", "중복지원");
            assertThat(notice.legalReview().checks().getFirst().action())
                    .contains("제조", "동일 과제 중복 신청 불가", "목적·수행 범위·산출물");
        });
        assertThat(result.currentNotice().purpose())
                .isEqualTo("제조 현장 데이터를 활용한 AI 공정 최적화 실증과 제조기업 사업화를 지원합니다.");
    }

    @Test
    void 제목의_일반_단어만_겹치고_사업_내용이_다르면_없음으로_처리한다() {
        Fixture current = fixture(1L, "국토교통부", "AI 지원사업 공고",
                "제조 설비의 공정 데이터를 분석하는 인공지능 기술 실증을 지원합니다.",
                List.of(1.0, 0.0, 0.0));
        Fixture unrelated = fixture(2L, "문화체육관광부", "AI 지원사업 공고",
                "지역 예술인의 공연 창작과 해외 문화 교류 활동을 지원합니다.",
                List.of(0.0, 1.0, 0.0));
        given(detectionRepository.findById(current.detection().getId())).willReturn(Optional.of(current.detection()));
        given(analysisRepository.findByDocumentVersionId(current.version().getId())).willReturn(Optional.of(current.analysis()));
        given(analysisRepository.findAll()).willReturn(List.of(current.analysis(), unrelated.analysis()));

        var result = service().find(current.detection().getId());

        assertThat(result.similarNotices()).isEmpty();
    }

    @Test
    void 임베딩_점수가_높아도_핵심_주제가_다르면_없음으로_처리한다() {
        Fixture current = fixture(1L, "한국산업기술진흥원", "자동차부품 순환경제 인프라 구축",
                "자동차부품 재사용과 순환경제 기반 시설 및 장비를 조성합니다.",
                List.of(1.0, 0.0, 0.0));
        Fixture unrelated = fixture(2L, "한국산업기술진흥원", "규제샌드박스 사업화 프로그램",
                "규제특례 승인제품의 시장진입과 판로 및 투자유치를 돕습니다.",
                List.of(0.99, 0.05, 0.0));
        given(detectionRepository.findById(current.detection().getId()))
                .willReturn(Optional.of(current.detection()));
        given(analysisRepository.findByDocumentVersionId(current.version().getId()))
                .willReturn(Optional.of(current.analysis()));
        given(analysisRepository.findAll()).willReturn(List.of(current.analysis(), unrelated.analysis()));

        var result = service().find(current.detection().getId());

        assertThat(result.similarNotices()).isEmpty();
    }

    @Test
    void 같은_연도만_겹치는_서로_다른_사업은_없음으로_처리한다() {
        Fixture current = fixture(1L, "한국산업기술진흥원",
                "2026년도 독일 등 유럽 진출 희망 중견기업 지원사업 선정 공고",
                "유럽 기술협력센터를 활용해 중견기업의 기술협력과 공동연구를 지원합니다.",
                List.of(1.0, 0.0, 0.0));
        Fixture unrelated = fixture(2L, "한국산업기술진흥원",
                "2026년도 자동차부품 순환경제 혁신 인프라 구축 사업 시행계획 공고",
                "자동차부품 재제조 전주기 인프라와 품질인증 기반을 조성합니다.",
                List.of(0.99, 0.05, 0.0));
        given(detectionRepository.findById(current.detection().getId()))
                .willReturn(Optional.of(current.detection()));
        given(analysisRepository.findByDocumentVersionId(current.version().getId()))
                .willReturn(Optional.of(current.analysis()));
        given(analysisRepository.findAll()).willReturn(List.of(current.analysis(), unrelated.analysis()));

        var result = service().find(current.detection().getId());

        assertThat(result.similarNotices()).isEmpty();
    }

    @Test
    void 핵심_주제가_긴_합성어에_포함되면_같은_주제로_처리한다() {
        Fixture current = fixture(1L, "한국산업기술진흥원",
                "2026년 산업기술국제협력 양자 싱가포르 접수 안내",
                "싱가포르 기관과 산업기술 국제공동연구를 수행할 국내기업을 지원합니다.",
                List.of(1.0, 0.0, 0.0));
        Fixture similar = fixture(2L, "한국산업기술진흥원",
                "2026년 산업기술국제협력사업 통합 시행계획 공고",
                "해외 기관과 산업기술 국제공동연구개발을 수행할 기업을 지원합니다.",
                List.of(0.2, 0.98, 0.0));
        ReflectionTestUtils.setField(current.analysis(), "proposalDirection",
                "{\"documentType\":\"PROPOSAL_REQUEST\"}");
        ReflectionTestUtils.setField(similar.analysis(), "proposalDirection",
                "{\"documentType\":\"GENERAL_NOTICE\"}");
        given(detectionRepository.findById(current.detection().getId()))
                .willReturn(Optional.of(current.detection()));
        given(analysisRepository.findByDocumentVersionId(current.version().getId()))
                .willReturn(Optional.of(current.analysis()));
        given(analysisRepository.findAll()).willReturn(List.of(current.analysis(), similar.analysis()));
        given(detectionRepository.findTopByDocumentIdOrderByDetectedAtDescIdDesc(similar.document().getId()))
                .willReturn(Optional.of(similar.detection()));

        var result = service().find(current.detection().getId());

        assertThat(result.similarNotices()).singleElement().satisfies(notice -> {
            assertThat(notice.commonPoints()).contains("산업기술국제협력");
            assertThat(notice.legalReview().overallStatus()).isEqualTo("REVIEW_REQUIRED");
            assertThat(notice.legalReview().checks()).allSatisfy(check ->
                    assertThat(check.finding()).contains("원문에서 관련 제한을 확인하지 못했습니다.")
            );
        });
    }

    private SimilarNoticeService service() {
        return new SimilarNoticeService(detectionRepository, analysisRepository, new ObjectMapper());
    }

    private Fixture fixture(
            Long id,
            String organization,
            String title,
            String content,
            List<Double> embedding
    ) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 9, 0);
        MonitoringSource source = MonitoringSource.create(
                organization, "사업공고", null, "https://example.com/" + id, null, 3, true
        );
        ReflectionTestUtils.setField(source, "id", id);
        Document document = Document.create(source, "https://example.com/notice/" + id, id.toString(), now);
        ReflectionTestUtils.setField(document, "id", id);
        DocumentVersion version = DocumentVersion.create(
                document, 1, title,
                "1. 사업 목적 ○ " + content
                        + " 2. 사업 구분 ① 양자간 공동펀딩형 국제공동R&D"
                        + " 3. 사업내용 해외 연구기관과 컨소시엄을 구성해야 합니다."
                        + " 총 최대 5억 원을 지원합니다.",
                String.valueOf(id).repeat(64), now, 0, now
        );
        ReflectionTestUtils.setField(version, "id", id);
        DocumentAnalysis analysis = DocumentAnalysis.create(
                version, content, "[\"" + content + "\"]", DocumentImportance.HIGH,
                "사업 목적을 확인했습니다.", AnalysisEligibility.REVIEW_REQUIRED,
                AnalysisFavorability.NOT_APPLICABLE,
                "{\"documentType\":\"BUSINESS_NOTICE\",\"preparation\":{\"applicationDeadline\":\"2026-09-30\",\"eligibilityChecklist\":[{\"title\":\"중소기업 자격\"}]}}",
                70, null, "[]", "test", now
        );
        analysis.updateSimilarity(content, new ObjectMapper().writeValueAsString(embedding),
                "text-embedding-3-small");
        MonitoringRun run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, now);
        MonitoringRunSource runSource = MonitoringRunSource.create(run, source);
        DocumentDetection detection = DocumentDetection.create(
                runSource, document, version, DocumentChangeType.NEW_DOCUMENT, now
        );
        ReflectionTestUtils.setField(detection, "id", id);
        return new Fixture(document, version, analysis, detection);
    }

    private record Fixture(
            Document document,
            DocumentVersion version,
            DocumentAnalysis analysis,
            DocumentDetection detection
    ) {
    }
}
