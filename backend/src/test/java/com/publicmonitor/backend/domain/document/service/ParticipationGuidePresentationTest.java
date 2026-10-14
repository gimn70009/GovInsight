package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParticipationGuidePresentationTest {
    private ParticipationCondition row(ParticipationKind kind, String text, String scope, List<String> details, String file) {
        return new ParticipationCondition(kind, "참여 자격", text, scope, details,
                new RequirementSource("ATTACHMENT", file, "신청 기준", null, text + "\n" + String.join("\n", details)));
    }

    @Test
    void 같은_대상의_명시된_유형별_허용과_금지를_합치고_두_근거를_보존한다() {
        var allowed = row(ParticipationKind.MANDATORY, "2024년 A사업 수요기업은 유형2에 한해 신청할 수 있습니다.", null, List.of(), "공고.hwpx");
        var denied = row(ParticipationKind.EXCLUSION, "2024년 A사업 수요기업은 유형1 신청할 수 없습니다.",
                "아래의 조건 중 하나 이상 해당하는 경우 신청 불가", List.of(), "공고.pdf");
        assertThat(ParticipationGuidePresentation.rules(List.of(allowed, denied))).singleElement().satisfies(rule -> {
            assertThat(rule.section()).isEqualTo("TARGETED");
            assertThat(rule.audience()).isEqualTo("2024년 A사업 수요기업");
            assertThat(rule.outcomes()).containsExactly(new ParticipationOutcome("유형1", "INELIGIBLE"), new ParticipationOutcome("유형2", "ELIGIBLE"));
            assertThat(rule.sources()).containsExactly(allowed.source(), denied.source());
        });
        assertThat(ParticipationGuidePresentation.rules(List.of(allowed)).getFirst().outcomes())
                .containsExactly(new ParticipationOutcome("유형2", "ELIGIBLE"));
    }

    @Test
    void 같은_대상도_자금과_대상범위_연도_예외가_다르면_합치지_않는다() {
        var text = "2024년 A사업 수요기업은 유형2에 한해 신청할 수 있습니다.";
        var rules = ParticipationGuidePresentation.rules(List.of(
                row(ParticipationKind.MANDATORY, text, "성장자금", List.of(), "a"),
                row(ParticipationKind.MANDATORY, text, "혁신자금", List.of(), "a"),
                row(ParticipationKind.MANDATORY, text, "수도권 기업", List.of(), "a"),
                row(ParticipationKind.MANDATORY, text.replace("2024", "2025"), "성장자금", List.of(), "a"),
                row(ParticipationKind.MANDATORY, text, "성장자금", List.of("단, 휴업 기업은 제외."), "a")));
        assertThat(rules).hasSize(5);
        assertThat(rules).extracting(ParticipationRule::scope).contains("수도권 기업");
    }

    @Test
    void 서식만_다른_중복은_합치지만_수치와_논리는_보존한다() {
        var first = row(ParticipationKind.MANDATORY, "매출 1.5억 원 이상", null, List.of("A 또는 B"), "a");
        var duplicate = row(ParticipationKind.MANDATORY, "매출 1.5억원 이상.", null, List.of("A 또는 B."), "b");
        var rules = ParticipationGuidePresentation.rules(List.of(first, duplicate,
                row(ParticipationKind.MANDATORY, "매출 15억 원 이상", null, List.of("A 또는 B"), "a"),
                row(ParticipationKind.MANDATORY, "매출 1.5억 원 이상", null, List.of("A 및 B"), "a"),
                row(ParticipationKind.MANDATORY, "매출 1.5억 원 미만", null, List.of("A 또는 B"), "a")));
        assertThat(rules).hasSize(4);
        assertThat(rules.getFirst().sources()).hasSize(2);
        assertThat(ParticipationGuidePresentation.compact("A/B")).isNotEqualTo(ParticipationGuidePresentation.compact("AB"));
    }

    @Test
    void 같은_유형의_서로_다른_판정은_확인_항목으로_남긴다() {
        var rules = ParticipationGuidePresentation.rules(List.of(
                row(ParticipationKind.MANDATORY, "기존 참여기업은 유형1 신청할 수 있습니다.", null, List.of(), "a"),
                row(ParticipationKind.EXCLUSION, "기존 참여기업은 유형1 신청할 수 없습니다.", null, List.of(), "b")));
        assertThat(rules).singleElement().satisfies(rule -> {
            assertThat(rule.section()).isEqualTo("REVIEW");
            assertThat(rule.sources()).hasSize(2);
            assertThat(rule.text()).contains("서로 다르게");
        });
    }

    @Test
    void 확인_방법은_별도_안내로_옮기고_예외와_기준일은_유지한다() {
        var first = row(ParticipationKind.MANDATORY, "중소기업이 대상입니다.", null,
                List.of("유효한 확인서 제출", "CN코드 확인방법 \uf09e 헬프데스크 문의", "\uf09e 수입기업에 코드 확인", "업력 7년 이내",
                        "< 조건 예외 대상(신청 마감일 기준) >", "단, 완납 기업은 예외."), "a");
        var rule = ParticipationGuidePresentation.rules(List.of(first)).getFirst();
        assertThat(rule.details()).containsExactly("유효한 확인서 제출이 필요합니다.", "업력 기준은 7년 이내입니다.");
        assertThat(rule.guidance()).containsExactly("CN코드 확인방법", "헬프데스크 문의", "수입기업에 코드 확인");
        assertThat(rule.exceptions()).containsExactly("예외 여부는 신청 마감일 기준으로 확인합니다.", "단, 완납 기업은 예외입니다.");
        assertThat(rule.sources()).containsExactly(first.source());
    }

    @Test
    void 확인방법의_서식_차이는_자격을_중복시키지_않는다() {
        var a = row(ParticipationKind.MANDATORY, "중소기업이 대상입니다.", null,
                List.of("유효한 확인서 제출", "CN코드 확인방법 \uf09e 헬프데스크 문의"), "a");
        var b = row(ParticipationKind.MANDATORY, "중소기업이 대상입니다.", null,
                List.of("유효한 확인서 제출", "CN 코드 확인방법 ▸ 헬프데스크 문의."), "b");
        assertThat(ParticipationGuidePresentation.rules(List.of(a, b))).singleElement().satisfies(rule -> {
            assertThat(rule.sources()).hasSize(2);
            assertThat(rule.guidance()).hasSize(2);
        });
    }

    @Test
    void 유형의_정의가_명시된_경우만_설명을_표시한다() {
        assertThat(ParticipationGuidePresentation.variants(List.of("(유형1) 인프라 구축, (유형2) 제3자 검증\n"))).containsExactly(
                new ParticipationVariant("유형1", "인프라 구축"), new ParticipationVariant("유형2", "제3자 검증"));
        assertThat(ParticipationGuidePresentation.variants(List.of("(유형1) 최대 42백만원\n(유형2) 신청 가능"))).isEmpty();
        assertThat(ParticipationGuidePresentation.variants(List.of("(유형1) 인프라 구축"))).isEmpty();
    }

    @Test
    void CBAM_전체_공고에서_제한을_합치고_공통자격과_조회_안내를_분리한다() throws Exception {
        String original;
        try (var stream = getClass().getResourceAsStream("/submission-documents/cbam-structured.txt")) {
            original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var attachment = DocumentAttachment.create(null, "CBAM 공고.hwpx", "https://example.org/notice", "hwpx",
                "application/zip", 1L, "hash", original, AttachmentParseStatus.COMPLETED, null);
        var guide = ParticipationConditionResolver.resolve(null, List.of(attachment), null);
        assertThat(guide.rules().stream().filter(row -> row.section().equals("TARGETED")).toList()).singleElement().satisfies(rule -> {
            assertThat(rule.audience()).contains("2025년", "MRV");
            assertThat(rule.outcomes()).hasSize(2);
            assertThat(rule.sources()).hasSize(2);
        });
        assertThat(guide.rules().stream().filter(row -> row.section().equals("BASIC")).toList()).hasSize(3).allSatisfy(row ->
                assertThat(row.scope()).isNull());
        assertThat(guide.rules()).anySatisfy(rule -> {
            assertThat(rule.label()).isEqualTo("기업 규모");
            assertThat(rule.text()).isEqualTo("중소기업에 해당해야 합니다.");
            assertThat(rule.details()).hasSize(1);
            assertThat(rule.guidance()).isNotEmpty();
        }).anySatisfy(rule -> {
            assertThat(rule.label()).isEqualTo("세금 체납");
            assertThat(rule.exceptions()).isNotEmpty();
        });
        assertThat(guide.variants()).hasSize(2);
        guide.rules().forEach(rule -> rule.sources().forEach(source -> assertThat(original).contains(source.excerpt())));
    }
    @Test
    void 같은_자격의_여러_첨부_원문은_해석_이전에도_보존한다() {
        String original = "□ 신청자격\n업력 7년 이내 기업";
        var a = DocumentAttachment.create(null, "공고.hwpx", "https://example.org/a", "hwpx",
                "application/zip", 1L, "a", original, AttachmentParseStatus.COMPLETED, null);
        var b = DocumentAttachment.create(null, "공고.pdf", "https://example.org/b", "pdf",
                "application/pdf", 1L, "b", original, AttachmentParseStatus.COMPLETED, null);
        var guide = ParticipationConditionResolver.resolve(null, List.of(a, b), null);
        assertThat(guide.rules()).singleElement().satisfies(rule ->
                assertThat(rule.sources()).extracting(RequirementSource::attachmentName).containsExactly("공고.hwpx", "공고.pdf"));
    }

    @Test
    void 허용_유형이_하나만_명시되어도_한해라는_한정어를_보존한다() {
        var only = row(ParticipationKind.MANDATORY, "기존 수요기업은 유형2에 한해 신청할 수 있습니다.", null, List.of(), "a");
        var denied = row(ParticipationKind.EXCLUSION, "기존 수요기업은 유형1 신청할 수 없습니다.", null, List.of(), "b");
        assertThat(ParticipationGuidePresentation.rules(List.of(only)).getFirst().text()).contains("유형에 한해");
        assertThat(ParticipationGuidePresentation.rules(List.of(denied, only)).getFirst().text()).contains("유형에 한해");
    }

    @Test
    void 한_유형만_허용과_다른_유형_허용이_충돌하면_확인으로_표시한다() {
        var only = row(ParticipationKind.MANDATORY, "기존 수요기업은 유형2에 한해 신청할 수 있습니다.", null, List.of(), "a");
        var other = row(ParticipationKind.MANDATORY, "기존 수요기업은 유형1 신청할 수 있습니다.", null, List.of(), "b");
        assertThat(ParticipationGuidePresentation.rules(List.of(only, other))).singleElement().satisfies(rule -> {
            assertThat(rule.section()).isEqualTo("REVIEW");
            assertThat(rule.sources()).hasSize(2);
        });
    }

}
