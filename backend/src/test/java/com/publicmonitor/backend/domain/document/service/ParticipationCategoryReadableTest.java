package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import com.publicmonitor.backend.domain.document.entity.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ParticipationCategoryReadableTest {
    private static final String FLAT = "첨부 파일 및 유레카 웹사이트 참조 - 영문 공고문 : https://example.org/globalstars/ "
            + "II 사업 절차 및 추진체계 1. 사업 절차 및 일정 사업 공고 < 2026.10.12 > "
            + "국문 연구개발계획서 접수 마감 < 2027.1.21 16:00(KST) > (접수처) 한국산업기술진흥원 https://k-pass.kr/ "
            + "2. 사업 추진체계 영문 공고 영문 연구개발계획서 제출 국내 평가 국외 평가 "
            + "[ 영문연구개발계획서 관련 유의사항 ] "
            + "① 영문 연구개발계획서의 국내 기관 연구개발비는 국문 연구개발계획서의 총 연구개발비와 동일하여야 함 "
            + "② 유레카 사무국 접수는 국내외 각 기관이 과제접수 플랫폼(https://eureka.smartsimple.ie/) 가입 후, 1개의 영문연구개발계획서를 공동으로 작성하고 각 기관이 전자 서명하여 제출 "
            + "③ 유레카 사무국에 제출한 영문 연구개발계획서를 파일(PDF 형식)로 내려받아 국문 연구개발계획서 접수 시 첨부하여 제출 (필수) "
            + "[ 과제 승인 확정 관련 유의사항 ] 국내 선정평가 결과의 확정 및 결과 통보도 순연됨. "
            + "III 공통 사항 1. 신청자격 기업, 대학, 연구기관";

    private ParticipationRule tier(int amount, String type) {
        String text = "업력 7년 이상의 매출액 " + amount + "억원 달성 중소기업이 대상입니다.";
        return new ParticipationRule("BASIC", "업력·창업", text, type, null,
                List.of("최근 3년간 매출액 " + amount + "억원을 1회 이상 달성해야 합니다."), List.of(), List.of(), List.of(),
                List.of(new RequirementSource("NOTICE_BODY", null, type, null, text)));
    }
    private List<ParticipationCriterionEntry> entries(List<ParticipationCategory> categories, String code) {
        return categories.stream().flatMap(category -> category.rows().stream()).filter(row -> row.code().equals(code))
                .flatMap(row -> row.entries().stream()).toList();
    }

    @Test void 기업규모의_첫문장에_매출과_업력을_남겨_유형별_대상을_구분한다() throws Exception {
        var rows = List.of(tier(100, "유형1"), tier(500, "유형2"), tier(1000, "유형3"));
        var categories = ParticipationCategoryProjector.project(rows, false);
        var sizes = entries(categories, "COMPANY_SIZE");
        assertThat(sizes).hasSize(3);
        assertThat(sizes).allSatisfy(entry -> assertThat(entry.rule().text()).contains("업력 7년", "매출액", "중소기업"));
        assertThat(sizes.stream().map(entry -> entry.rule().scope())).containsExactly("유형1", "유형2", "유형3");
        var exclusion = new ParticipationRule("EXCLUSION", "중복 수혜", "도약프로그램 지원사업을 수행 중인 기업은 신청이 제한됩니다.",
                "아래 지원제외 대상에 해당하는 기업\n구분 제외 대상", null, List.of(), List.of(), List.of(), List.of(),
                List.of(new RequirementSource("NOTICE_BODY", null, "신청 제외", null, "도약프로그램 지원사업을 수행 중인 기업은 신청이 제한됩니다.")));
        var all = new ArrayList<>(rows);
        all.add(exclusion);
        all.addAll(ParticipationCategorySources.supplement(FLAT, List.of(), List.of()));
        var guide = new ParticipationGuide(List.of(), all, List.of(), ParticipationCategoryProjector.project(all, false));
        var path = Path.of("build/participation-categories/readable-tiers.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, new ObjectMapper().writeValueAsString(guide));
    }

    @Test void 줄바꿈_없는_게시글에서도_계획서_의무만_완결된_문장으로_읽는다() {
        var rules = ParticipationCategorySources.supplement(FLAT, List.of(), List.of());
        var entries = entries(ParticipationCategoryProjector.project(rules, false), "PLAN");
        assertThat(entries).hasSize(3);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.rule().text()).endsWith("니다.").doesNotContain("접수 마감", "사업 절차", "k-pass.kr", "결과 통보", "III 공통", "영문 공고문");
            assertThat(entry.rule().sources().getFirst().excerpt()).isEqualTo(FLAT);
        });
        assertThat(entries.stream().map(entry -> entry.rule().text())).anySatisfy(text -> assertThat(text).contains("PDF", "국문", "첨부하여 제출"));
    }

    @Test void 자부담_문장_뒤의_표제와_띄어진_종결어미를_정리한다() {
        String text = "과제에 참여하는 자는 정부지원연구개발비를 지원받아 과제를 수행하여야 하며, 영리기관의 경우 기관부담연구개발비 중 현금을 개별 부담하여야 합 니다. 정부지원연구개발비 지원비율 및 기관부담연구개발비 중 현금 부담비율";
        var rules = ParticipationCategorySources.supplement(text, List.of(), List.of());
        var entries = entries(ParticipationCategoryProjector.project(rules, false), "MATCHING");
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().rule().text()).endsWith("부담하여야 합니다.")
                .doesNotContain("지원비율 및", "합 니다");
        assertThat(entries.getFirst().rule().sources().getFirst().excerpt()).isEqualTo(text);
    }

    @Test void 본문과_첨부의_동일한_수행조건은_원문을_모아_한번만_표시한다() {
        String clause = "영문 연구개발계획서를 국문 접수 시 첨부하여 제출 (필수)";
        var file = DocumentAttachment.create(null, "공고.hwp", "https://example.org/notice.hwp", "hwp",
                "application/octet-stream", 1L, "hash", clause, AttachmentParseStatus.COMPLETED, null);
        var rules = ParticipationCategorySources.supplement(clause, List.of(file), List.of());
        var entries = entries(ParticipationCategoryProjector.project(rules, false), "PLAN");
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().rule().sources()).hasSize(2);
    }

    @Test void 나뉜_문장에서도_선택과_예외_수치_괄호_조건을_보존한다() {
        String clause = "① 영문 연구개발계획서(기관별 1개 또는 공동 1개)는 필수 제출해야 함 ② 중소기업은 현금 10% 이상을 부담해야 함";
        var parts = ParticipationExecutionClauses.split(clause);
        assertThat(parts).hasSize(2);
        assertThat(parts.getFirst()).contains("(기관별 1개 또는 공동 1개)");
        assertThat(parts.getLast()).contains("10% 이상");
    }

    @Test void 공백없는_글머리표와_한글_절제목_평탄화된_표의_경계를_보존한다() {
        String original = "지원조건 가. 연구개발비 구성 ○과제 연구개발비는 현금 및 현물로 구성 "
                + "○영리기관은 기관부담연구개발비 중 현금을 개별 부담하여야 함 "
                + "나. 정부지원연구개발비 지원비율 [표] 유형 기관부담연구개발비 현금 10% 이상 [/표] "
                + "III 기타 사항 ① 영문 연구개발계획서를 필수 제출해야 함";
        var categories = ParticipationCategoryProjector.project(ParticipationCategorySources.supplement(original, List.of(), List.of()), false);
        assertThat(entries(categories, "MATCHING")).hasSize(1);
        assertThat(entries(categories, "MATCHING").getFirst().rule().text()).isEqualTo("영리기관은 기관부담연구개발비 중 현금을 개별 부담하여야 합니다.");
        assertThat(entries(categories, "PLAN")).hasSize(1);
        assertThat(entries(categories, "PLAN").getFirst().rule().text()).isEqualTo("영문 연구개발계획서를 필수 제출해야 합니다.");
    }

    @Test void 자부담_예외와_앞_문장의_대상을_분리하지_않는다() {
        String original = "수요기업은 기관부담연구개발비 중 현금 10% 이상을 부담해야 합니다. 다만, 정부지원연구개발비를 받지 않으면 현금을 면제할 수 있습니다.";
        assertThat(ParticipationExecutionClauses.split(original)).containsExactly(original);
    }

    @Test void 구조가_있는_자부담표의_기관별_비율은_세부조건에_유지한다() {
        var rules = ParticipationCategorySources.supplement("""
                □ 자부담
                [표]
                기관 유형\t기관부담연구개발비 중 현금 부담비율
                중소기업\t10% 이상
                중견기업\t13% 이상
                [/표]
                """, List.of(), List.of());
        var rows = entries(ParticipationCategoryProjector.project(rules, false), "MATCHING");
        assertThat(rows).hasSize(2);
        assertThat(String.join("\n", rows.stream().map(row -> row.rule().text()).toList())).contains("중소기업", "10% 이상", "중견기업", "13% 이상");
        assertThat(rows).allSatisfy(row -> assertThat(row.rule().sources().getFirst().excerpt()).contains("중소기업", "10% 이상", "중견기업", "13% 이상"));
        assertThat(rows.getFirst().rule().text()).doesNotContain("[표]");
    }
}
