package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ParticipationDuplicateTest {
    private ParticipationCondition row(String text, List<String> details, String file) {
        return new ParticipationCondition(ParticipationKind.MANDATORY, "업종·분야", text, null, details,
                new RequirementSource("ATTACHMENT", file, "신청 대상", null, text + "\n" + String.join("\n", details)));
    }

    private String fixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/participation-conditions/" + name + ".txt")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private DocumentAttachment file(String name, String text) {
        return DocumentAttachment.create(null, name, "https://example.org/notice.hwpx", "hwpx",
                "application/zip", 1L, "hash", text, AttachmentParseStatus.COMPLETED, null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 클린제조_복수_추출본의_같은_자격을_한번_표시하고_모든_세부조건과_근거를_보존한다(boolean reversed) throws Exception {
        String structured = fixture("clean-structured"), legacy = fixture("clean-legacy");
        var a = file("클린제조 구조화 공고.hwpx", structured);
        var b = file("클린제조 평문 공고.hwpx", legacy);
        var guide = ParticipationConditionResolver.resolve(null, reversed ? List.of(b, a) : List.of(a, b), null);
        Path output = Path.of("build/participation-guide-checks/clean-combined-guide.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writeValueAsString(guide), StandardCharsets.UTF_8);
        var originalFull = ParticipationConditionResolver.resolve(null, List.of(b), null).rules().stream()
                .filter(rule -> rule.text().contains("도시형소공인 지원에 관한 특별법")).findFirst().orElseThrow();
        assertThat(guide.rules().stream().filter(rule -> rule.text().contains("도시형소공인 지원에 관한 특별법")).toList())
                .singleElement().satisfies(rule -> {
                    assertThat(rule.section()).isEqualTo("BASIC");
                    assertThat(rule.details()).isEqualTo(originalFull.details());
                    assertThat(String.join("\n", rule.details())).contains("C10~C34", "10인 미만", "고위험업종");
                    assertThat(rule.details().stream().filter(detail -> detail.contains("신청조건")).toList()).hasSize(1);
                    assertThat(rule.sources()).hasSize(2).allSatisfy(source ->
                            assertThat(source.attachmentName().contains("구조화") ? structured : legacy).contains(source.excerpt()));
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void CBAM_예외_제목의_괄호_공백만_다른_첨부는_조건과_예외를_한번씩_표시한다(boolean reversed) throws Exception {
        String original;
        try (var stream = getClass().getResourceAsStream("/submission-documents/cbam-structured.txt")) {
            original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String spaced = original.replace("조건 예외 대상(", "조건 예외 대상 (");
        var a = file("CBAM 공고.hwpx", original);
        var b = file("CBAM 공고.pdf", spaced);
        var guide = ParticipationConditionResolver.resolve(null, reversed ? List.of(b, a) : List.of(a, b), null);
        var single = ParticipationConditionResolver.resolve(null, List.of(a), null);
        for (String label : List.of("세금 체납", "채무불이행")) {
            var expected = single.rules().stream().filter(rule -> rule.label().equals(label)).findFirst().orElseThrow();
            assertThat(guide.rules().stream().filter(rule -> rule.label().equals(label)).toList())
                    .singleElement().satisfies(rule -> {
                        assertThat(rule.text()).isEqualTo(expected.text());
                        assertThat(rule.exceptions()).isEqualTo(expected.exceptions());
                        assertThat(rule.sources()).hasSize(2).allSatisfy(source ->
                                assertThat(source.attachmentName().endsWith("hwpx") ? original : spaced).contains(source.excerpt()));
                    });
        }
        Path output = Path.of("build/participation-guide-checks/cbam-combined-guide.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writeValueAsString(guide), StandardCharsets.UTF_8);
    }

    @Test
    void 최종_안내체로_같아지는_세부조건은_정리한_뒤_중복을_합친다() {
        var a = row("제조업 소상공인", List.of("인증 유효기간은 공고 기준에 따름"), "a");
        var b = row("제조업 소상공인", List.of("인증 유효기간은 공고 기준에 따릅니다."), "b");
        for (var inputs : List.of(List.of(a, b), List.of(b, a))) {
            assertThat(ParticipationGuidePresentation.rules(inputs)).singleElement().satisfies(rule -> {
                assertThat(rule.details()).containsExactly("인증 유효기간은 공고 기준에 따릅니다.");
                assertThat(rule.sources()).containsExactlyInAnyOrder(a.source(), b.source());
            });
        }
    }

    @Test
    void 같은_제외_문장도_예외_기준일이_다르면_합치지_않는다() {
        var a = new ParticipationCondition(ParticipationKind.EXCLUSION, "세금 체납", "국세 또는 지방세 체납 중인 자(기업)",
                null, List.of("< 조건 예외 대상(신청 마감일 기준) >", "완납한 기업"), row("원문 A", List.of(), "a").source());
        var b = new ParticipationCondition(a.kind(), a.category(), a.text(), null,
                List.of("< 조건 예외 대상 (협약일 기준) >", "완납한 기업"), row("원문 B", List.of(), "b").source());
        assertThat(ParticipationGuidePresentation.rules(List.of(a, b))).hasSize(2).allSatisfy(rule -> {
            assertThat(rule.exceptions()).hasSize(2);
            assertThat(rule.sources()).hasSize(1);
        });
    }

    @Test
    void 문자가_손상된_서로_다른_원문을_공통_안내문으로_합치지_않는다() {
        var a = row("제조업 소상공인", List.of("매출액 1.5억원 이\uFFFD상"), "a");
        var b = row("제조업 소상공인", List.of("매출액 15억원 이\uFFFD상"), "b");
        assertThat(ParticipationGuidePresentation.rules(List.of(a, b))).hasSize(2).allSatisfy(rule -> {
            assertThat(rule.section()).isEqualTo("REVIEW");
            assertThat(rule.sources()).hasSize(1);
        });
    }

    @Test
    void 번호_조건_앞의_분리된_표_라벨만_표시문에서_제외한다() {
        assertThat(ParticipationConditionText.paragraphs(List.of("- 신청조건 ①, ②를 모두 충족하여야 함",
                "신청", "조건", "① 제조업", "② 10인 미만"))).containsExactly(
                "- 신청조건 ①, ②를 모두 충족하여야 함", "① 제조업", "② 10인 미만");
        assertThat(ParticipationConditionText.paragraphs(List.of("신청", "조건을 충족하는 기업")))
                .containsExactly("신청 조건을 충족하는 기업");
    }

    @Test
    void 부분_세부조건이_연속해서_보완되어도_입력순서와_무관하게_가장_완전한_조건을_보존한다() {
        var shortRow = row("제조업 소상공인", List.of("신청조건 모두 충족"), "a");
        var middle = row("제조업 소상공인", List.of("신청조건 모두 충족", "A 또는 B"), "b");
        var full = row("제조업 소상공인", List.of("신청조건 모두 충족", "A 또는 B", "매출 1.5억원 이상"), "c");
        var expected = ParticipationGuidePresentation.rules(List.of(full)).getFirst();
        for (var inputs : List.of(List.of(shortRow, middle, full), List.of(full, shortRow, middle),
                List.of(middle, full, shortRow), List.of(shortRow, full, middle),
                List.of(full, middle, shortRow), List.of(middle, shortRow, full))) {
            assertThat(ParticipationGuidePresentation.rules(inputs)).singleElement().satisfies(rule -> {
                assertThat(rule.details()).isEqualTo(expected.details());
                assertThat(rule.sources()).containsExactlyInAnyOrder(shortRow.source(), middle.source(), full.source());
            });
        }
    }

    @Test
    void 같은_요약이어도_세부_수치와_논리_순서가_다르면_합치지_않는다() {
        var a = row("제조업 소상공인", List.of("매출 1.5억원 이상", "A 또는 B"), "a");
        var b = row("제조업 소상공인", List.of("매출 15억원 이상", "A 또는 B"), "b");
        var c = row("제조업 소상공인", List.of("매출 1.5억원 이상", "A 및 B"), "c");
        var d = row("제조업 소상공인", List.of("A 또는 B", "매출 1.5억원 이상", "추가 인증 필요"), "d");
        assertThat(ParticipationGuidePresentation.rules(List.of(a, b, c, d))).hasSize(4);
    }

    @Test
    void 공통_부분이_서로_다른_여러_조건에_포함되면_임의의_조건에_붙이지_않는다() {
        var common = row("제조업 소상공인", List.of(), "a");
        var first = row("제조업 소상공인", List.of("매출 1.5억원 이상"), "b");
        var second = row("제조업 소상공인", List.of("매출 15억원 이상"), "c");
        for (var inputs : List.of(List.of(common, first, second), List.of(second, common, first), List.of(first, second, common))) {
            assertThat(ParticipationGuidePresentation.rules(inputs)).hasSize(3)
                    .allSatisfy(rule -> assertThat(rule.sources()).hasSize(1));
        }
    }

    @Test
    void 동일한_범위와_예외에서만_세부조건_포함관계로_합친다() {
        var base = row("제조업 소상공인", List.of("공통 요건", "단, 휴업 기업은 제외."), "a");
        var full = row("제조업 소상공인", List.of("공통 요건", "추가 인증 필요", "단, 휴업 기업은 제외."), "b");
        var differentException = row("제조업 소상공인", List.of("공통 요건", "추가 인증 필요", "단, 폐업 기업은 제외."), "c");
        var otherScope = new ParticipationCondition(full.kind(), full.category(), full.text(), "다른 자금",
                full.details(), full.source());
        var rules = ParticipationGuidePresentation.rules(List.of(base, full, differentException, otherScope));
        assertThat(rules).hasSize(3);
        assertThat(rules.getFirst().sources()).containsExactlyInAnyOrder(base.source(), full.source());
        assertThat(rules.getFirst().exceptions()).hasSize(1);
    }

    @Test
    void 확인_항목의_공통_진단_문구만으로_원문을_합치지_않는다() {
        var first = row("자격 조건의 문장이 끝까지 확인되지 않아 원문 확인이 필요합니다.", List.of(), "a");
        var second = row(first.text(), List.of("상시근로자 10인 미만"), "b");
        var reviews = List.of(first, second).stream().map(item -> new ParticipationCondition(
                ParticipationKind.REVIEW_REQUIRED, item.category(), item.text(), item.scope(), item.details(),
                item.source(), ParticipationReviewReason.INCOMPLETE_TEXT)).toList();
        assertThat(ParticipationGuidePresentation.rules(reviews)).hasSize(2);
    }
}
