package com.publicmonitor.backend.domain.document.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import com.publicmonitor.backend.domain.document.entity.*;
import static org.assertj.core.api.Assertions.assertThat;

class ParticipationChecklistSourcesTest {
    private static final String NOTICE = "기관 모집 공고\n□ 신청자격\n비영리기관 신청 가능\n* (동시 과제 수 산정 제외)\n  참여연구원은 동시 과제 수 산정에서 제외\n가. 지원내용\n지원금 안내\n□ 제출서류\n신청 자격 적정성 확인서 1부";
    private static final String FORM = "[파일: 1. 신청 자격 적정성 확인서.hwpx]\n신청 자격 적정성 확인서\n□ 채무불이행 여부\n▸ 기업의 경우 부도상태인가?\n(단, 회생 인가 후 정상 납부 중인 경우 예외)\n□ 참여율 여부\n▸ 참여율 100%를 초과하는가?\n위 내용은 사실입니다.\n대표자 성명\n[파일: 2. 사업계획서.hwpx]\n□ 신청자격\n대기업도 신청가능 작성 예시";
    private static DocumentAttachment file(String name, String text) {
        return DocumentAttachment.create(null, name, "https://example.org/attachment", "hwpx", null, null, null, text, AttachmentParseStatus.COMPLETED, null);
    }

    @Test void linkedZipChecklistAndWrappedExemptionAreExactSpans() {
        var files = List.of(file("공고.hwpx", NOTICE), file("신청서식 및 참고자료.zip", FORM));
        var linked = ParticipationChecklistSources.collect("", files);
        assertThat(linked).hasSize(3);
        assertThat(linked).anySatisfy(l -> {
            assertThat(l.source().sectionTitle()).contains("[확인서 적용 예외]");
            assertThat(l.source().excerpt()).contains("참여연구원", "산정에서 제외").doesNotContain("지원금");
            assertThat(NOTICE).contains(l.source().excerpt());
        });
        assertThat(linked).filteredOn(l -> l.source().sectionTitle().contains("[자격 확인서]")).allSatisfy(l -> {
            assertThat(l.source().attachmentName()).isEqualTo("신청서식 및 참고자료.zip");
            assertThat(FORM).contains(l.source().excerpt());
            assertThat(l.source().excerpt()).doesNotContain("사실입니다", "대표자 성명", "대기업도");
        });
        var data = ParticipationConditionResolver.candidatesWithScopes("", files, null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        assertThat(input.request().sources()).noneMatch(s -> s.groupLabel().contains("[자격 확인서]"));
        assertThat(ParticipationSourceSelection.select(input).outbound(input).sources())
                .anyMatch(s -> s.groupLabel().contains("[확인서 적용 예외]"))
                .anyMatch(s -> s.groupLabel().contains("[자격 확인서]") && s.excerpt().contains("정상 납부"));
    }

    @Test void unreferencedFormAndUnrelatedFormsStayExcluded() {
        assertThat(ParticipationChecklistSources.collect("기관 모집 공고\n□ 제출서류\n사업계획서 1부", List.of(file("서식.zip", FORM)))).isEmpty();
        assertThat(ParticipationChecklistSources.collect(NOTICE, List.of(file("서식.zip", "[파일: 사업계획서.hwpx]\n□ 자격 여부\n신청 가능한가?")))).isEmpty();
    }

    @Test void fullTextIsPreferredOverDuplicatePageExtraction() {
        var form = "[파일: 신청 자격 적정성 확인서.hwpx]\n[PDF 1페이지 시각 판독]\n□ 채무불이행 여부\n▸ 훼손된 문장?\n[문서 전체 텍스트 추출]\n□ 채무불이행 여부\n▸ 체납 상태인가?";
        assertThat(ParticipationChecklistSources.collect(NOTICE, List.of(file("서식.zip", form))))
                .filteredOn(l -> l.source().sectionTitle().contains("[자격 확인서]")).singleElement()
                .satisfies(l -> assertThat(l.source().excerpt()).contains("체납").doesNotContain("훼손"));
    }
}
