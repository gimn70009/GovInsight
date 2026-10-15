package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ParticipationResearchNoticeTest {
    @ParameterizedTest
    @ValueSource(strings = {"pdf", "hwpx"})
    void 공식_연구개발_공고에서_실제_자격과_자부담_조건을_보존한다(String extension) throws Exception {
        String original;
        try (var stream = getClass().getResourceAsStream("/participation-conditions/ax-research-" + extension + ".txt")) {
            original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var file = DocumentAttachment.create(null, "연구개발 사업 공고." + extension, "https://example.org/notice." + extension,
                extension, "application/octet-stream", 1L, "hash", original, AttachmentParseStatus.COMPLETED, null);
        var guide = ParticipationConditionResolver.resolve(null, List.of(file), null);
        Path output = Path.of("build/participation-guide-checks/ax-" + extension + "-guide.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writeValueAsString(guide), StandardCharsets.UTF_8);
        assertThat(guide.rules().stream().filter(row -> row.section().equals("BASIC")).toList()).hasSize(extension.equals("hwpx") ? 5 : 2).allSatisfy(row -> {
            assertThat(row.section()).isEqualTo("BASIC");
            assertThat(row.sources()).allSatisfy(source -> assertThat(original).contains(source.excerpt()));
            assertThat(row.text()).doesNotContain("집행가능", "사업.", "의무매칭");
        }).anySatisfy(row -> {
            assertThat(row.label()).isEqualTo("주관기관 자격");
            assertThat(row.text()).contains("접수 마감일", "기업부설연구소", "법인사업자");
            assertThat(row.scope()).contains("기업, 대학", "연구조합", "접수 마감일 기준");
            assertThat(String.join(" ", row.details())).contains("협약체결 전까지", "본사 또는 기업부설 연구소", "대구 이전");
        }).anySatisfy(row -> {
            assertThat(row.label()).isEqualTo("국외기관 참여");
            assertThat(row.text()).contains("외국법인", "직접 참여할 수 있습니다");
            assertThat(String.join(" ", row.exceptions())).contains("국내 연구개발기관", "공동으로 수행", "한정");
        });
        assertThat(guide.rules().stream().filter(row -> row.section().equals("FUNDING"))).singleElement().satisfies(row -> {
            assertThat(row.label()).isEqualTo("자부담·사업비");
            assertThat(row.text()).contains("민간부담금을 부담해야 합니다", "유형별로 다릅니다");
            assertThat(String.join(" ", row.details())).contains("25% 이상", "30% 이상", "50% 이상", "비영리기관", "부담의무가 없습니다");
            assertThat(String.join(" ", row.exceptions())).contains("최소기준을 초과", "현물, 현금 비율");
            assertThat(row.sources().getFirst().excerpt()).doesNotContain("연구개발기관 구성 요건");
        });
        assertThat(guide.rules().stream().filter(row -> row.section().equals("EXCLUSION"))).isNotEmpty();
        if (extension.equals("hwpx")) assertThat(guide.rules().stream().filter(row -> row.label().equals("참여기관 구성")))
                .anySatisfy(row -> assertThat(row.text()).contains("1개 이상", "참여해야 합니다"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"□ 지원대상 사업", "■ 지원 대상 과제", "ㅇ (지원대상 사업)", "■ (지원대상 사업)", "□ 지원대상: 사업", "□ 지원대상\n사업", "지원대상\t사업"})
    void 지원받는_사업_목록을_신청자_자격으로_해석하지_않는다(String heading) {
        String original = heading + "\n○ 지역 산업전환 기술개발 사업\n□ 사업목적\n기술개발 지원\n"
                + "□ 신청자격: 기업부설연구소를 보유한 법인사업자";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> {
            assertThat(row.text()).contains("기업부설연구소", "법인사업자");
            assertThat(row.scope()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "지원대상 사업을 수행할 역량을 보유한 기업",
            "지원대상 과제에 적합한 기술을 보유한 기업",
            "지원비율을 충족할 자부담 능력이 있는 기업",
            "해외 소재 기업도 국내 기관과 공동으로 참여할 수 있음",
            "연구비로 집행할 수 있으나 신청기업은 연구소를 보유해야 함",
            "사업비로 집행할 수 없으면 참여 불가",
            "연구비로 집행하는 사업을 수행한 기업"})
    void 사업명이나_집행_단어가_포함된_진짜_자격은_버리지_않는다(String criterion) {
        var guide = ParticipationConditionResolver.resolve("□ 신청자격\n- " + criterion, List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> assertThat(row.section()).isEqualTo("BASIC"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"국제 공동연구개발비로 집행가능함", "외부전문기술활용비로 집행가능함", "연구비에서 지급 가능"})
    void 집행_설명을_소재지_자격으로_만들지_않고_원문에는_보존한다(String spending) {
        String original = "□ 신청자격\n- 접수 마감일 기준 국내 소재 기업\n"
                + "- 외국에 소재한 기관의 기술을 활용하는 경우 " + spending + ". 계획서에 기관 외 참여로 표기함";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> {
            assertThat(row.text()).contains("국내 소재 기업");
            assertThat(row.sources().getFirst().excerpt()).isEqualTo(original);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"* 참고자료 ‘부담 기준’ 참고", "※ 참고자료 2 참조"})
    void 참조_각주_뒤의_필수_조건과_제외_예외가_누락되지_않는다(String footnote) {
        String original = "□ 신청자격\n- 연구소 보유 기업\n" + footnote
                + "\n- 자기자본 3억 원 이상 기업\n□ 지원제외\n- 국세 체납 기업\n* 단, 완납한 경우 신청 가능";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).hasSize(3).anySatisfy(row -> {
            assertThat(row.section()).isEqualTo("EXCLUSION");
            assertThat(String.join(" ", row.exceptions())).contains("완납한 경우");
        }).anySatisfy(row -> assertThat(row.text()).contains("자기자본 3억 원 이상"));
    }

    @Test
    void 실제_참고자료_절의_예시와_관련없는_표는_계속_제외한다() {
        var guide = ParticipationConditionResolver.resolve("□ 신청자격: 국내 중소기업\n참고자료 1\n□ 신청자격: 매출 999억원 기업", List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> assertThat(row.text()).contains("국내 중소기업"));
    }


    @Test
    void 자격의_부가_설명에_집행_안내가_있어도_자격을_버리지_않는다() {
        var guide = ParticipationConditionResolver.resolve("□ 신청자격\n- 국내 소재 기업\n* 연구비로 집행할 수 있음", List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> {
            assertThat(row.text()).contains("국내 소재 기업");
            assertThat(String.join(" ", row.details())).contains("집행할 수");
        });
    }

    @Test
    void 집행_설명에_붙은_추가_자격이나_금지_조건도_유지한다() {
        var guide = ParticipationConditionResolver.resolve("□ 신청자격\n- 연구비로 집행 가능\n* 국내 소재 기업\n※ 기준일은 접수 마감일", List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> assertThat(String.join(" ", row.details())).contains("국내 소재 기업"));
        var restriction = ParticipationConditionResolver.resolve("□ 신청자격\n- 사업비로 집행 불가", List.of(), null);
        assertThat(restriction.rules()).singleElement().satisfies(row -> assertThat(row.text()).contains("집행 불가"));
    }

}
