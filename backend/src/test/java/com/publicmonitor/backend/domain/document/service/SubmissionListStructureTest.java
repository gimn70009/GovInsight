package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionDocument;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SubmissionListStructureTest {
    @ParameterizedTest
    @ValueSource(strings = {"제출서류(별지서식 참조)", "ㅇ 구비 자료 [붙임 2 참고]", "3. 신청 서류 (첨부 양식 참조)"})
    void referenceAnnotationsDoNotHideTheFollowingList(String heading) {
        var guide = SubmissionDocumentResolver.resolve(heading + "\n1) 참여신청서\n2) 운영계획서", List.of(), null);
        assertThat(guide.documents()).extracting(SubmissionDocument::title).containsExactly("참여신청서", "운영계획서");
    }

    @Test
    void nestedListsKeepNamedDeliverablesAndSeparateInlineFormReferences() {
        String source = """
                4. 신청 방법
                ㅇ 제출서류(별지서식 참조)
                - ① 운영기관 지정 신청서 * 관련 시행규칙 별지 제7호서식
                - ② 첨부서류
                (1) 정관 또는 이에 준하는 약정
                (2) 시설 운영계획서
                (3) 인력 및 시설 현황과 증명서류 등
                (4) 확약서
                ㅇ 제출 방식
                - 기관장 명의의 공문 제출
                ㅇ 제출 기간 : 2028. 3. 1. ~ 2028. 3. 15.
                5. 평가 기준
                ① 전문 인력 보유
                """;
        var guide = SubmissionDocumentResolver.resolve(source, List.of(), null);
        assertThat(guide.documents()).extracting(SubmissionDocument::title).containsExactly(
                "운영기관 지정 신청서", "정관 또는 이에 준하는 약정", "시설 운영계획서", "인력 및 시설 현황과 증명서류 등", "확약서");
        assertThat(guide.documents().getFirst().detail()).contains("별지 제7호서식");
        assertThat(guide.documents()).allSatisfy(d -> {
            assertThat(source).contains(d.source().excerpt());
            assertThat(d.source().excerpt()).doesNotContain("제출 방식", "제출 기간", "평가 기준");
        });
        assertThat(SubmissionNotesProjection.input(source, List.of(), guide).request().documents()).hasSize(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"(1)", "1)", "1.", "①", "가."})
    void nominalDeliverablesAreRecognizedOnlyInsideTheSubmissionList(String marker) {
        String items = marker + " 참여자 명부\n" + marker + " 수행실적 목록\n" + marker + " 단체 규약";
        var guide = SubmissionDocumentResolver.resolve("구비서류\n" + items, List.of(), null);
        assertThat(guide.documents()).extracting(SubmissionDocument::title).containsExactly("참여자 명부", "수행실적 목록", "단체 규약");
        assertThat(SubmissionDocumentResolver.resolve("평가 기준\n" + items, List.of(), null).documents()).isEmpty();
    }

    @Test
    void annotationsDoNotEraseConditionalAndAlternativeRequirements() {
        var guide = SubmissionDocumentResolver.resolve("제출서류\n(1) 실적증명서 ※ 해당 기관만 제출\n(2) 정관 또는 규약", List.of(), null);
        assertThat(guide.documents()).hasSize(2);
        assertThat(guide.documents().getFirst().requirementLevel()).isEqualTo("CONDITIONAL");
        assertThat(guide.documents().getFirst().conditions()).anySatisfy(c -> assertThat(c).contains("해당 기관만"));
        assertThat(guide.documents().getLast().title()).isEqualTo("정관 또는 규약");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ㅇ 제출 방법", "□ 접수 기간", "3. 선정 절차", "(2) 지원 자격", "○ 제출 장소"})
    void nextSectionDoesNotBecomeTheLastDocumentDetails(String boundary) {
        var guide = SubmissionDocumentResolver.resolve("제출서류\n(1) 신청서\n(2) 확약서\n" + boundary + "\n1) 별개 확인서", List.of(), null);
        assertThat(guide.documents()).extracting(SubmissionDocument::title).containsExactly("신청서", "확약서");
        assertThat(guide.documents()).allSatisfy(d -> assertThat(d.source().excerpt()).doesNotContain("별개", boundary));
    }

    @Test
    void explanatoryProseAndReferenceOnlySectionsDoNotBecomeDocuments() {
        for (String source : List.of("제출서류는 별도 안내합니다.\n(1) 신청서",
                "참고 자료\n제출서류(양식 참조)\n(1) 신청서", "제출서류(별지 참조, 법인만)\n(1) 신청서", "제출서류(양식 참조)\n(1) 시스템에서 신청합니다.\n(2) 인력을 갖출 것")) {
            assertThat(SubmissionDocumentResolver.resolve(source, List.of(), null).documents()).isEmpty();
        }
    }
    @Test
    void conditionalGroupAppliesOnlyToItsChildren() {
        String source = "제출서류\n① 신청서\n② 첨부서류(법인에 해당하는 경우)\n(1) 정관\n(2) 등기부등본\n③ 확약서";
        var guide = SubmissionDocumentResolver.resolve(source, List.of(), null);
        assertThat(guide.documents()).extracting(SubmissionDocument::title).containsExactly("신청서", "정관", "등기부등본", "확약서");
        assertThat(guide.documents()).extracting(SubmissionDocument::requirementLevel).containsExactly("MANDATORY", "CONDITIONAL", "CONDITIONAL", "MANDATORY");
        for (int i : List.of(1, 2)) {
            assertThat(guide.documents().get(i).conditions()).anySatisfy(c -> assertThat(c).contains("법인"));
            assertThat(source).contains(guide.documents().get(i).source().excerpt());
            assertThat(guide.documents().get(i).source().excerpt()).contains("법인에 해당하는 경우");
        }
    }

}
