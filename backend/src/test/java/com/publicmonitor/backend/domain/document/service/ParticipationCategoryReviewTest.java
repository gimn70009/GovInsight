package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParticipationCategoryReviewTest {
    private ParticipationRule rule(String text) {
        return new ParticipationRule("BASIC", "참여 자격", text, null, null, List.of(), List.of(),
                List.of(), List.of(), List.of(new RequirementSource("NOTICE_BODY", null, "신청자격", null, text)));
    }
    private List<ParticipationCriterionEntry> entries(List<ParticipationCategory> categories, String code) {
        return categories.stream().flatMap(category -> category.rows().stream()).filter(row -> row.code().equals(code))
                .flatMap(row -> row.entries().stream()).toList();
    }
    private String text(List<ParticipationCategory> categories, String code) {
        return String.join("\n", entries(categories, code).stream().map(entry -> entry.rule().text()).toList());
    }

    @Test void 조사의_중간이나_단어_중간을_잘라_독립_조건으로_표시하지_않는다() {
        for (String original : List.of(
                "폐업기업의 업종이 현재 재창업 기업의 업종과 같은 소상공인입니다.",
                "전년 대비 상시근로자 수가 증가한 소상공인입니다.",
                "매출감소로 인해 사업장을 이전한 소상공인입니다.",
                "장애인기업확인서를 소지한 장애 소상공인이 대상입니다.")) {
            var categories = ParticipationCategoryProjector.project(List.of(rule(original)), false);
            categories.stream().flatMap(category -> category.rows().stream()).flatMap(row -> row.entries().stream())
                    .forEach(entry -> {
                        assertThat(entry.rule().text()).doesNotMatch(".*(?:기업의|업종이|증가한|인해|소지한 장애)$");
                        assertThat(entry.rule().sources().getFirst().excerpt()).isEqualTo(original);
                    });
        }
    }

    @Test void 소재지와_인증_조건의_서술어를_중간에_끊지_않는다() {
        var categories = ParticipationCategoryProjector.project(List.of(
                rule("주관기관은 대구광역시 소재 기업·기관·대학·연구소 등에 해당해야 합니다."),
                rule("주관연구개발기관으로 신청하는 기업의 경우는 접수 마감일 현재 기업부설연구소를 보유하고 있는 법인사업자여야 합니다.")), false);
        assertThat(text(categories, "LOCATION")).contains("대구광역시").doesNotMatch(".*소재$");
        assertThat(text(categories, "CERTIFICATION")).contains("기업부설연구소").doesNotMatch(".*보유$");
        assertThat(text(categories, "BUSINESS_FORM")).contains("법인사업자");
    }

    @Test void 제품과_수출의_목적어와_행위를_함께_보존한다() {
        var categories = ParticipationCategoryProjector.project(List.of(
                rule("EU로 CBAM 대상 품목(붙임1) 수출(희망) 중소기업이 대상입니다."),
                rule("사업자등록증 상 업태에 ‘제조’가 포함되어 있으며, CBAM 대상 품목(붙임1)을 제조하는 중소기업이 대상입니다.")), false);
        assertThat(text(categories, "EXPORT")).contains("EU", "CBAM", "수출(희망)");
        assertThat(text(categories, "PRODUCT")).contains("제조").doesNotMatch(".*제조하는$");
        assertThat(text(categories, "SECTOR")).doesNotMatch(".*있으며$");
    }

    @Test void 접수마감과_접수처를_계획서_요건으로_분류하지_않는다() {
        String original = """
                1. 사업 절차 및 일정
                국문 연구개발계획서 접수 마감
                < 2027년 1월 21일(목) 16:00(KST) >
                (접수처) 한국산업기술진흥원 https://k-pass.kr/
                연구개발계획서 평가
                < 2027년 3월까지 >
                □ 신청자격
                - 중소기업
                □ 제출서류
                - 연구개발계획서를 필수 제출해야 합니다.
                """;
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(text(guide.categories(), "PLAN")).contains("연구개발계획서를 필수 제출")
                .doesNotContain("접수 마감", "k-pass.kr", "16:00", "접수처");
    }

    @Test void 연구비_시스템_사용을_자부담_능력으로_분류하지_않는다() {
        var guide = ParticipationConditionResolver.resolve("""
                □ 지원조건
                - 연구개발기관은 연구비통합관리스템(통합Ezbaro, 통합RCMS)을 통하여 연구개발비(정부지원연구개발비 및 기관부담연구개발비)를 사용・관리하여야 함
                - 중소기업은 기관부담연구개발비 중 현금 10% 이상을 부담하여야 함
                """, List.of(), null);
        assertThat(text(guide.categories(), "MATCHING")).contains("10%").doesNotContain("통합RCMS", "사용・관리");
    }

    @Test void 조건부_컨소시엄과_동시수행제한을_기업규모의_필수자격으로_오인하지_않는다() {
        var categories = ParticipationCategoryProjector.project(List.of(
                rule("국내 주관연구개발기관은 기업만 가능하며, 대기업이 주관일 경우 공동연구개발기관 포함이 필수입니다."),
                rule("중소기업 또는 중견기업 중 한계기업이 동시에 수행하는 연구개발과제수가 아래 표의 기준 이상인 경우에는 사전지원제외로 처리합니다.")), false);
        assertThat(entries(categories, "COMPANY_SIZE")).isEmpty();
        assertThat(text(categories, "CONSORTIUM")).contains("대기업이 주관일 경우");
        assertThat(text(categories, "DUPLICATE")).contains("동시에 수행");
    }

    @Test void 괄호_안의_설명을_이어_붙여_중간에_닫히지_않은_조건을_복원한다() {
        var original = rule("부도·체납 등 경영·재무 제외 조건 중 하나에 해당하면 신청이 제한됩니다.");
        var exclusion = new ParticipationRule("EXCLUSION", "경영·재무", original.text(), null, null, List.of(
                "최근 결산 기준 자본전액잠식(K-IFRS에 따른 자본전액잠식은 일반기업회계기준으로 판단 가능.",
                "이 경우, 판단을 위해 자료를 제출하며 회계기준을 혼용할 수 없음)."), List.of(), List.of(), List.of(), original.sources());
        var categories = ParticipationCategoryProjector.project(List.of(exclusion), false);
        assertThat(text(categories, "CAPITAL")).contains("혼용할 수 없음)");
        assertThat(ParticipationClauseText.balance(text(categories, "CAPITAL"))).isZero();
    }

    @Test void 자격표에_명시된_국제_컨소시엄_구성을_놓치지_않는다() {
        var guide = ParticipationConditionResolver.resolve("""
                1. 지원 규모 및 기간, 자격
                [표]
                프로그램\t지원 자격\t국내 조건
                국제사업\t2개 이상 국가, 2개 이상의 독립된 기관으로 구성 (일본 참여 필수)\t국내 컨소시엄 내 영리기업 참여 필수
                [/표]
                □ 신청자격
                - 기업
                """, List.of(), null);
        assertThat(text(guide.categories(), "CONSORTIUM")).contains("2개 이상 국가", "2개 이상의 독립된 기관", "일본 참여 필수", "영리기업 참여가 필수입니다.");
        assertThat(entries(guide.categories(), "CONSORTIUM").getLast().rule().sources().getFirst().excerpt()).contains("영리기업 참여 필수");
    }

    @Test void 졸업후보기업의_기업_접미어를_기업형태로_오인하지_않는다() {
        var categories = ParticipationCategoryProjector.project(List.of(
                rule("소상공인 졸업후보기업, 직접대출 성실상환 소상공인이 대상입니다.")), false);
        assertThat(entries(categories, "BUSINESS_FORM")).isEmpty();
    }

    @Test void 동시수행_상한에서_연구책임자_대상과_조건을_떼어내지_않는다() {
        String original = "참여연구자는 동시에 수행하는 과제가 최대 5개 이내여야 하며, 이 중 연구책임자로서 동시에 수행하는 과제는 최대 3개 이내여야 합니다.";
        var categories = ParticipationCategoryProjector.project(List.of(rule(original)), false);
        assertThat(entries(categories, "EXPERTS")).isEmpty();
        assertThat(entries(categories, "DUPLICATE")).hasSize(1);
        assertThat(text(categories, "DUPLICATE")).isEqualTo(original);
    }

    @Test void 제출서류표와_절차표를_협력구조나_개별조건으로_통째로_넣지_않는다() {
        var guide = ParticipationConditionResolver.resolve("""
                □ 제출서류
                [표]
                번호\t제출 서류명\t제출방법
                1\t[필수] 연구개발계획서\t온라인 업로드
                2\t공동연구개발기관 확인서\t모든 기관 필수 서명
                3\t[필수] 사업자등록증\t연구개발계획서 뒷페이지 첨부
                [/표]
                □ 평가 절차
                [표]
                ① 서류평가\t⇨\t② 발표평가\t⇨\t③ 선정
                담당기관\t\t평가위원회 (발표평가)\t\t담당기관
                [/표]
                """, List.of(), null);
        assertThat(entries(guide.categories(), "CONSORTIUM")).isEmpty();
        assertThat(text(guide.categories(), "PLAN")).contains("연구개발계획서를 필수 제출해야 합니다.")
                .doesNotContain("필수 서명", "사업자등록증", "② 발표평가", "평가위원회", "제출 서류명");
        assertThat(entries(guide.categories(), "PLAN").getFirst().rule().details()).contains("제출 방법: 온라인 업로드.");
    }

    @Test void 병합셀의_반복된_안내에서_계획서_요건만_보존한다() {
        String paragraph = "o 접수 마감에는 접속 지연에 유의 o 연구개발계획서는 전산 등록하여 제출해야 함 o 접수처 https://example.org";
        var guide = ParticipationConditionResolver.resolve("□ 제출서류\n[표]\n" + paragraph + "\t" + paragraph + "\n[/표]", List.of(), null);
        assertThat(entries(guide.categories(), "PLAN")).hasSize(1);
        assertThat(text(guide.categories(), "PLAN")).contains("연구개발계획서는 전산 등록하여 제출해야")
                .doesNotContain("접속 지연", "접수처", "example.org");
    }

    @Test void 계산_예시를_모든_기업의_매출이나_인력_조건으로_표시하지_않는다() {
        var categories = ParticipationCategoryProjector.project(List.of(
                rule("(예시) 음식점업은 매출액 15억원 이하, 상시근로자 5명 미만입니다.")), false);
        assertThat(entries(categories, "REVENUE")).isEmpty();
        assertThat(entries(categories, "HEADCOUNT")).isEmpty();
    }
}
