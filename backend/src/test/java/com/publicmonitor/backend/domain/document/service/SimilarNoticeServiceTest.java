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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SimilarNoticeServiceTest {

    @Mock DocumentDetectionRepository detectionRepository;
    @Mock DocumentAnalysisRepository analysisRepository;

    @Test
    void 자료부족과_미발견과_분석누락을_구분한다() {
        Fixture current = fixture(1L, "기관", "제조 AI", "제조 AI 실증 지원", List.of(1.0));
        for (String status : List.of("DATA_INSUFFICIENT", "NOT_FOUND", "ASSESSMENT_INCOMPLETE")) {
            current.analysis().updateComparisonSummary("{\"legalRisks\":[{\"type\":\"DUPLICATE_SUPPORT\",\"status\":\""
                    + status + "\",\"summary\":\"확인 상태 설명입니다.\"}]}");
            com.publicmonitor.backend.domain.document.web.dto.SimilarNoticeResponse.LegalRiskCheck check =
                    ReflectionTestUtils.invokeMethod(service(), "legalRiskCheck", "DUPLICATE_SUPPORT", "중복지원",
                            current.analysis(), current.analysis());
            assertThat(check.status()).isEqualTo(status);
        }
        current.analysis().updateComparisonSummary("{}");
        com.publicmonitor.backend.domain.document.web.dto.SimilarNoticeResponse.LegalRiskCheck missing =
                ReflectionTestUtils.invokeMethod(service(), "legalRiskCheck", "DUPLICATE_SUPPORT", "중복지원",
                        current.analysis(), current.analysis());
        assertThat(missing.status()).isEqualTo("ASSESSMENT_INCOMPLETE");
    }

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
        stubCandidates(List.of(current.analysis(), similar.analysis()));
        given(detectionRepository.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(similar.version().getId()))
                .willReturn(Optional.of(similar.detection()));

        var result = service().find(current.detection().getId());

        assertThat(result.similarNotices()).singleElement().satisfies(notice -> {
            assertThat(notice.title()).isEqualTo("제조 AI 실증 사업 공고");
            assertThat(notice.comparison().organizationName()).isEqualTo("산업통상부");
            assertThat(notice.similarityScore()).isGreaterThanOrEqualTo(78);
            assertThat(notice.comparison().purpose())
                    .isEqualTo("제조기업의 AI 공정 최적화 실증과 사업화를 지원합니다.");
            assertThat(notice.comparison().requiredPartner())
                    .isEqualTo("해외 연구기관과 컨소시엄을 구성해야 합니다.");
            assertThat(notice.legalReview().overallStatus()).isEqualTo("RESTRICTION_FOUND");
            assertThat(notice.legalReview().checks()).hasSize(5);
            assertThat(notice.legalReview().checks().getFirst().evidence())
                    .contains("동일 과제 중복 신청 불가");
            assertThat(notice.legalReview().summary()).contains("제조", "중복지원", "충돌을 확정할 수 없습니다");
            assertThat(notice.legalReview().checks().getFirst().status()).isEqualTo("RESTRICTION_FOUND");
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
        stubCandidates(List.of(current.analysis(), unrelated.analysis()));

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
        stubCandidates(List.of(current.analysis(), unrelated.analysis()));

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
        stubCandidates(List.of(current.analysis(), unrelated.analysis()));

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
                List.of(0.9, 0.3, 0.0));
        ReflectionTestUtils.setField(current.analysis(), "proposalDirection",
                "{\"documentType\":\"PROPOSAL_REQUEST\"}");
        ReflectionTestUtils.setField(similar.analysis(), "proposalDirection",
                "{\"documentType\":\"GENERAL_NOTICE\"}");
        given(detectionRepository.findById(current.detection().getId()))
                .willReturn(Optional.of(current.detection()));
        given(analysisRepository.findByDocumentVersionId(current.version().getId()))
                .willReturn(Optional.of(current.analysis()));
        stubCandidates(List.of(current.analysis(), similar.analysis()));
        given(detectionRepository.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(similar.version().getId()))
                .willReturn(Optional.of(similar.detection()));

        var result = service().find(current.detection().getId());

        assertThat(result.similarNotices()).singleElement().satisfies(notice -> {
            assertThat(notice.legalReview().overallStatus()).isEqualTo("REVIEW_REQUIRED");
            assertThat(notice.legalReview().checks()).allSatisfy(check ->
                    assertThat(check.finding()).contains("판정이 미완료입니다.")
            );
        });
    }

    @Test
    void 긴_제목이_같아도_의미_유사도가_낮으면_선택하지_않는다() {
        Fixture current = fixture(1L, "기관A", "산업기술국제협력 공고", "국제공동연구 지원", List.of(1.0, 0.0));
        Fixture other = fixture(2L, "기관B", "산업기술국제협력 안내", "국제공동연구 안내", List.of(0.2, 0.98));
        prepareSearch(current, List.of(other));
        assertThat(service().find(1L).similarNotices()).isEmpty();
    }

    @Test
    void 동의어와_띄어쓰기와_조사가_달라도_핵심_주제를_연결한다() {
        Fixture current = fixture(1L, "기관A", "예측 정비 지원", "설비의 예측 정비를 지원합니다.", List.of(1.0, 0.0));
        Fixture other = fixture(2L, "기관B", "예지보전 공고", "기계 예지보전은 고장을 줄입니다.", List.of(0.9, 0.3));
        prepareSearch(current, List.of(other));
        given(detectionRepository.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(2L))
                .willReturn(Optional.of(other.detection()));
        assertThat(service().find(1L).similarNotices()).hasSize(1);
    }

    @Test
    void 조사만_다른_한국어_명사를_연결한다() {
        Fixture current = fixture(1L, "기관A", "신규 공고", "반도체의 품질을 높입니다.", List.of(1.0, 0.0));
        Fixture other = fixture(2L, "기관B", "지원 안내", "반도체를 검사합니다.", List.of(0.9, 0.3));
        prepareSearch(current, List.of(other));
        given(detectionRepository.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(2L))
                .willReturn(Optional.of(other.detection()));
        assertThat(service().find(1L).similarNotices()).hasSize(1);
    }

    @Test
    void 인공지능만_같고_분야와_목적이_다르면_제외한다() {
        Fixture current = fixture(1L, "기관A", "AI 지원", "반도체 결함탐지를 지원합니다.", List.of(1.0, 0.0));
        Fixture other = fixture(2L, "기관B", "인공 지능 공고", "공연 창작을 지원합니다.", List.of(0.99, 0.01));
        prepareSearch(current, List.of(other));
        assertThat(service().find(1L).similarNotices()).isEmpty();
    }

    @Test
    void 비교_요약의_다른_필드가_없어도_사업_목적은_사용한다() {
        Fixture current = fixture(1L, "기관A", "신규 공고", "기업을 지원합니다.", List.of(1.0, 0.0));
        Fixture other = fixture(2L, "기관B", "선정 안내", "기관을 지원합니다.", List.of(0.9, 0.3));
        current.analysis().updateComparisonSummary("{\"purpose\":\"반도체 결함탐지 연구\"}");
        other.analysis().updateComparisonSummary("{\"purpose\":\"반도체 불량검출 연구\"}");
        prepareSearch(current, List.of(other));
        given(detectionRepository.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(2L))
                .willReturn(Optional.of(other.detection()));
        assertThat(service().find(1L).similarNotices()).singleElement()
                .satisfies(item -> assertThat(item.comparison().purpose()).contains("반도체 불량검출"));
    }

    @Test
    void 잘못된_벡터와_영벡터는_제목이_같아도_제외한다() {
        Fixture current = fixture(1L, "기관A", "산업기술국제협력", "국제공동연구", List.of(1.0, 0.0));
        Fixture other = fixture(2L, "기관B", "산업기술국제협력", "국제공동연구", List.of(0.9, 0.3));
        prepareSearch(current, List.of(other));
        for (String vector : List.of("[0,0]", "[1,null]", "[1,\"0\"]", "[1,1e999]", "[]", "{}", "[1]")) {
            other.analysis().updateSimilarity("국제공동연구", vector, "text-embedding-3-small");
            assertThat(service().find(1L).similarNotices()).as(vector).isEmpty();
        }
        other.analysis().updateSimilarity("국제공동연구", "[1,0]", "other-model");
        assertThat(service().find(1L).similarNotices()).isEmpty();
    }

    @Test
    void 반올림_전_점수로_상위_세건을_정렬한다() {
        Fixture current = fixture(1L, "기관A", "반도체", "반도체 연구", List.of(1.0, 0.0));
        var candidates = new java.util.ArrayList<Fixture>();
        for (long id = 2; id <= 5; id++) {
            double similarity = 0.780 + id * 0.001;
            Fixture item = fixture(id, "기관B", "반도체 연구 " + id, "반도체 연구",
                    List.of(similarity, Math.sqrt(1 - similarity * similarity)));
            candidates.add(item);
            if (id >= 3) {
                given(detectionRepository.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(id))
                        .willReturn(Optional.of(item.detection()));
            }
        }
        prepareSearch(current, candidates);
        assertThat(service().find(1L).similarNotices()).extracting(item -> item.title())
                .containsExactly("반도체 연구 5", "반도체 연구 4", "반도체 연구 3");
    }

    @Test
    void 감지_이력이_없는_후보는_건너뛰고_다음_후보를_반환한다() {
        Fixture current = fixture(1L, "기관A", "반도체", "반도체 연구", List.of(1.0, 0.0));
        Fixture missing = fixture(2L, "기관B", "반도체", "반도체 연구", List.of(1.0, 0.0));
        Fixture valid = fixture(3L, "기관C", "반도체", "반도체 연구", List.of(0.9, 0.3));
        // Version IDs are independent of document IDs.
        ReflectionTestUtils.setField(valid.version(), "id", 30L);
        prepareSearch(current, List.of(missing, valid));
        given(detectionRepository.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(2L))
                .willReturn(Optional.empty());
        given(detectionRepository.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(30L))
                .willReturn(Optional.of(valid.detection()));
        assertThat(service().find(1L).similarNotices()).hasSize(1);
        org.mockito.Mockito.verify(detectionRepository).findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(30L);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.76, 0.79, 0.99, Double.NaN})
    void missingPurposeMessagesCannotLinkUnrelatedNoticesThroughAnySearchLane(double similarity) {
        String missing = "원문에서 확인하지 못했습니다.";
        Fixture current = fixture(1L, "산업통상부", "2026년 제5차 산업융합 규제샌드박스 규제특례 승인 공고",
                missing, List.of(1.0, 0.0));
        Fixture other = fixture(2L, "산업통상부", "2026년 에너지공기업 기술나눔 공고",
                missing, List.of(1.0, 0.0));
        other.analysis().updateSimilarity("test", Double.isNaN(similarity) ? null
                : new ObjectMapper().writeValueAsString(List.of(similarity, Math.sqrt(1 - similarity * similarity))),
                "text-embedding-3-small");
        prepareSearch(current, List.of(other));
        given(detectionRepository.findById(2L)).willReturn(Optional.of(other.detection()));
        org.mockito.Mockito.lenient().when(detectionRepository.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(1L))
                .thenReturn(Optional.of(current.detection()));
        org.mockito.Mockito.lenient().when(detectionRepository.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(2L))
                .thenReturn(Optional.of(other.detection()));

        // Check stored comparison fields, legacy source extraction, and summary fallback.
        for (String origin : List.of("comparison", "source", "summary")) {
            for (Fixture item : List.of(current, other)) {
                item.analysis().updateComparisonSummary(origin.equals("comparison")
                        ? "{\"purpose\":\"" + missing + "\"}" : null);
                ReflectionTestUtils.setField(item.version(), "contentText", origin.equals("source")
                        ? "1. 사업 목적 ○ " + missing + " 2. 사업 내용" : "");
            }
            SimilarNoticeService instance = service();
            assertThat(instance.find(1L).similarNotices()).as(origin + " forward").isEmpty();
            assertThat(instance.find(2L).similarNotices()).as(origin + " reverse").isEmpty();
            assertThat(instance.find(1L).currentNotice().purpose()).isEqualTo(missing);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"원문에서 확인하지 못했습니다.", "  원문에서  확인하지 못했습니다。  ",
            "사업 목적을 확인하지 못했습니다", "원문에서 사업 목적을 확인하지 못했습니다."})
    void missingMessageDoesNotAddTopicsToOtherwiseValidPurpose(String missing) {
        Fixture current = fixture(1L, "기관A", "반도체", "결함탐지 공정", List.of(1.0, 0.0));
        Fixture other = fixture(2L, "기관B", "반도체", "결함탐지 공정", List.of(0.7, Math.sqrt(0.51)));
        ObjectMapper mapper = new ObjectMapper();
        for (Fixture item : List.of(current, other)) {
            item.analysis().updateComparisonSummary(mapper.writeValueAsString(
                    java.util.Map.of("purpose", missing + "! 결함탐지 공정")));
        }
        prepareSearch(current, List.of(other));
        given(detectionRepository.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(2L))
                .willReturn(Optional.of(other.detection()));
        assertThat(service().find(1L).similarNotices()).singleElement().satisfies(item -> {
            assertThat(item.matchBasis()).isEqualTo("LEXICAL");
            assertThat(item.legalReview().summary()).contains("반도체", "결함탐지", "공정")
                    .doesNotContain("원문·", "확인하지", "못했습니다");
            assertThat(item.comparison().purpose()).isEqualTo("결함탐지 공정");
        });
    }

    private void prepareSearch(Fixture current, List<Fixture> candidates) {
        given(detectionRepository.findById(current.detection().getId())).willReturn(Optional.of(current.detection()));
        given(analysisRepository.findByDocumentVersionId(current.version().getId())).willReturn(Optional.of(current.analysis()));
        var analyses = new java.util.ArrayList<DocumentAnalysis>();
        analyses.add(current.analysis());
        candidates.forEach(item -> analyses.add(item.analysis()));
        stubCandidates(analyses);
    }

    @Test
    void unchangedCorpusReusesVectorsAndChangedRevisionRebuilds() {
        Fixture current = fixture(1L, "기관A", "반도체 공정", "결함탐지 예지보전 공정", List.of(1.0, 0.0));
        Fixture other = fixture(2L, "기관B", "반도체 공정", "결함탐지 예지보전 공정", List.of(1.0, 0.0));
        prepareSearch(current, List.of(other));
        given(detectionRepository.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(2L)).willReturn(Optional.of(other.detection()));
        var revision = org.mockito.Mockito.mock(DocumentAnalysisRepository.SimilarityRevision.class);
        given(revision.getTotal()).willReturn(2L);
        given(revision.getLastId()).willReturn(2L);
        given(revision.getChangedAt()).willReturn(LocalDateTime.of(2026, 9, 8, 0, 0));
        given(analysisRepository.similarityRevision()).willReturn(revision);
        SimilarNoticeService instance = service();
        instance.find(1L);
        instance.find(1L);
        org.mockito.Mockito.verify(analysisRepository, org.mockito.Mockito.times(1)).findLatestSimilarityCandidates(0L);
        given(revision.getChangedAt()).willReturn(LocalDateTime.of(2026, 9, 8, 0, 1));
        instance.find(1L);
        org.mockito.Mockito.verify(analysisRepository, org.mockito.Mockito.times(2)).findLatestSimilarityCandidates(0L);
    }

    @Test
    void noEmbeddingProducesLexicalCandidateWithoutInventingSimilarityPercentage() {
        Fixture current = fixture(1L, "기관A", "반도체 공정", "결함탐지 예지보전 공정", List.of(1.0, 0.0));
        Fixture other = fixture(2L, "기관B", "반도체 공정", "결함탐지 예지보전 공정", List.of(1.0, 0.0));
        ReflectionTestUtils.setField(current.analysis(), "similarityEmbedding", null);
        prepareSearch(current, List.of(other));
        given(detectionRepository.findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(2L)).willReturn(Optional.of(other.detection()));
        assertThat(service().find(1L).similarNotices()).singleElement().satisfies(item -> {
            assertThat(item.matchBasis()).isEqualTo("LEXICAL_ONLY");
            assertThat(item.similarityScore()).isNull();
        });
    }

    private void stubCandidates(List<DocumentAnalysis> analyses) {
        given(analysisRepository.findLatestSimilarityCandidates(0L)).willReturn(analyses);
        for (DocumentAnalysis analysis : analyses) {
            org.mockito.Mockito.lenient().when(analysisRepository.findByDocumentVersionId(
                    analysis.getDocumentVersion().getId())).thenReturn(Optional.of(analysis));
        }
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
