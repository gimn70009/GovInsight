package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import com.publicmonitor.backend.domain.document.entity.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ParticipationCategoryProjectorTest {
    private static final String JAPAN = """
            □ 신청자격
            - 기업, 대학, 연구기관, 의료기관
            - 주관연구개발기관 및 공동연구개발기관 중 국내기업은 접수마감일 현재 사업자등록증 및 법인등기부등본 기준으로 창업 1년 이상 경과하고, 한국산업기술진흥협회가 발급한 기업부설연구소 인정서를 보유하고 있는 국내 사업자
            □ 지원제외
            - 국세 또는 지방세 체납 기업
            □ 우대사항
            - 여성기업 2점 가점
            """;

    private ParticipationRule rule(String text) {
        return new ParticipationRule("BASIC", "참여 자격", text, "접수 마감일 기준 · 성장형", null,
                List.of(), List.of("다만, 해외 기업은 별도 기준 적용"), List.of(), List.of(),
                List.of(new RequirementSource("NOTICE_BODY", null, "신청자격", null, text)));
    }
    private ParticipationCriterionRow row(List<ParticipationCategory> groups, String code) {
        return groups.stream().flatMap(group -> group.rows().stream()).filter(value -> value.code().equals(code)).findFirst().orElseThrow();
    }
    private String texts(List<ParticipationCategory> groups, String code) {
        return String.join("\n", row(groups, code).entries().stream().map(value -> value.rule().text()).toList());
    }

    @Test void 모든_공고에_8개_분류와_40개_항목을_고정적으로_반환한다() {
        var groups = ParticipationCategoryProjector.project(List.of(), false);
        assertThat(groups).hasSize(8);
        assertThat(groups.stream().mapToInt(group -> group.rows().size()).sum()).isEqualTo(40);
        assertThat(groups.stream().flatMap(group -> group.rows().stream())).allSatisfy(row -> {
            assertThat(row.status()).isEqualTo("NOT_STATED");
            assertThat(row.entries()).isEmpty();
        });
    }

    @Test void 업력_매출_기업규모를_분리하고_유형_기준일_예외와_원문을_보존한다() {
        var original = rule("업력 7년 이상의 매출액 1,000억원 달성 중소기업이 대상입니다.");
        var groups = ParticipationCategoryProjector.project(List.of(original), false);
        assertThat(texts(groups, "BUSINESS_AGE")).contains("7년 이상").doesNotContain("매출", "1,000");
        assertThat(texts(groups, "REVENUE")).contains("1,000억원").doesNotContain("7년", "중소기업");
        assertThat(texts(groups, "COMPANY_SIZE")).contains("중소기업", "1,000억원", "7년 이상");
        for (String code : List.of("BUSINESS_AGE", "REVENUE", "COMPANY_SIZE")) {
            assertThat(row(groups, code).entries()).singleElement().satisfies(entry -> {
                assertThat(entry.context()).isEqualTo(original.text());
                assertThat(entry.rule().scope()).isEqualTo(original.scope());
                assertThat(entry.rule().exceptions()).isEqualTo(original.exceptions());
                assertThat(entry.rule().sources()).isEqualTo(original.sources());
                assertThat(entry.relation()).isEqualTo("LINKED");
            });
        }
    }

    @Test void 선택_관계를_독립된_필수조건으로_바꾸지_않는다() {
        var original = rule("매출액 100억원 이상 또는 수출액 50억원 이상인 중소기업");
        var groups = ParticipationCategoryProjector.project(List.of(original), false);
        for (String code : List.of("REVENUE", "EXPORT", "COMPANY_SIZE")) {
            assertThat(row(groups, code).entries()).singleElement().satisfies(entry -> {
                assertThat(entry.relation()).isEqualTo("ALTERNATIVE");
                assertThat(entry.context()).contains("또는");
            });
        }
    }

    @Test void 공고_본문의_업력과_기업부설연구소를_각각_표시한다() throws Exception {
        var guide = ParticipationConditionResolver.resolve(JAPAN, List.of(), null);
        assertThat(texts(guide.categories(), "BUSINESS_AGE")).contains("창업 1년 이상").doesNotContain("인정서");
        assertThat(texts(guide.categories(), "CERTIFICATION")).contains("기업부설연구소 인정서").doesNotContain("1년");
        assertThat(texts(guide.categories(), "PREF_REPRESENTATIVE")).contains("여성기업");
        assertThat(texts(guide.categories(), "REPRESENTATIVE")).isEmpty();
        var path = Path.of("build/participation-categories/japan-example.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, new ObjectMapper().writeValueAsString(guide), StandardCharsets.UTF_8);
    }

    @Test void 손상되거나_실패한_공고첨부를_명시없음으로_단정하지_않는다() {
        var file = DocumentAttachment.create(null, "모집 공고.pdf", "https://example.org/notice.pdf", "pdf",
                "application/pdf", 1L, "hash", null, AttachmentParseStatus.FAILED, "parse failed");
        var guide = ParticipationConditionResolver.resolve("공고문을 확인하세요.", List.of(file), null);
        assertThat(guide.categories().stream().flatMap(group -> group.rows().stream()))
                .allSatisfy(row -> assertThat(row.status()).isEqualTo("REVIEW_REQUIRED"));
        var empty = ParticipationConditionResolver.resolve(null, List.of(), null);
        assertThat(row(empty.categories(), "REVENUE").status()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test void 원문에서_분류한_제외와_우대_효과를_유지한다() {
        var tax = rule("국세 또는 지방세 체납 기업은 제외");
        tax = new ParticipationRule("EXCLUSION", tax.label(), tax.text(), tax.scope(), null, List.of(),
                List.of("회생인가 기업은 예외"), List.of(), List.of(), tax.sources());
        var groups = ParticipationCategoryProjector.project(List.of(tax), false);
        assertThat(row(groups, "TAX").entries()).singleElement().satisfies(entry -> {
            assertThat(entry.rule().section()).isEqualTo("EXCLUSION");
            assertThat(entry.rule().exceptions()).containsExactly("회생인가 기업은 예외");
        });
        assertThat(row(groups, "DEFAULT").entries()).isEmpty();
    }

    @Test void 기관명_증빙과_여성연구자_가점을_회사규모나_여성대표_자격으로_오인하지_않는다() throws Exception {
        String text = fixture("ax-research-hwpx.txt");
        var guide = ParticipationConditionResolver.resolve(text, List.of(), null);
        assertThat(row(guide.categories(), "COMPANY_SIZE").entries()).isEmpty();
        assertThat(texts(guide.categories(), "TAX")).doesNotContain("국세청 발급");
        assertThat(texts(guide.categories(), "PREF_REPRESENTATIVE")).doesNotContain("여성연구자 비율");
        assertThat(texts(guide.categories(), "MATCHING")).contains("민간부담금");
        assertThat(guide.categories().getLast().rows().stream().flatMap(row -> row.entries().stream()))
                .anySatisfy(entry -> assertThat(entry.rule().text()).contains("여성연구자 비율"));
        assertThat(row(guide.categories(), "PLAN").entries()).isNotEmpty();
        assertThat(texts(guide.categories(), "CONSORTIUM")).contains("1개 이상", "50% 이상");
    }

    @Test void 평가지표_안의_가점과_명시된_계획서_제출을_읽는다() {
        String text = """
                □ 신청자격
                - 중소기업
                □ 제출서류
                - 사업계획서를 필수 제출해야 합니다.
                □ 평가방법
                ○ 가점사항
                - 비수도권 소재 기업 2점 가점
                □ 문의처
                담당부서
                """;
        var guide = ParticipationConditionResolver.resolve(text, List.of(), null);
        assertThat(texts(guide.categories(), "PLAN")).contains("사업계획서", "제출");
        assertThat(texts(guide.categories(), "PREF_REGION")).contains("비수도권", "2점");
        assertThat(row(guide.categories(), "LOCATION").entries()).isEmpty();
    }

    @Test void 서식의_예시_내용은_카테고리로_추출하지_않는다() {
        String text = """
                [파일: 사업 공고문.hwp]
                □ 신청자격
                - 중소기업
                [파일: 사업계획서 양식.hwp]
                □ 신청자격
                - 매출액 99억원 이상이며 상시근로자 500명 이상
                """;
        var guide = ParticipationConditionResolver.resolve(text, List.of(), null);
        assertThat(texts(guide.categories(), "REVENUE")).isEmpty();
        assertThat(texts(guide.categories(), "HEADCOUNT")).isEmpty();
    }


    @Test void 불확실한_조건을_해당_세부항목의_명시없음으로_숨기지_않는다() {
        var review = new ParticipationRule("REVIEW", "확인 필요", "표의 조건 연결을 확인해야 합니다.", null, null,
                List.of(), List.of(), List.of(), List.of(),
                List.of(new RequirementSource("NOTICE_BODY", null, "신청조건", null, "매출액 10억원 이상 / 업력 3년 미만")),
                ParticipationReviewReason.TABLE_MAPPING_UNCLEAR);
        var groups = ParticipationCategoryProjector.project(List.of(review), false);
        assertThat(row(groups, "REVENUE").status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(row(groups, "BUSINESS_AGE").status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(row(groups, "HEADCOUNT").status()).isEqualTo("NOT_STATED");
    }

    @Test void 기관명에_포함된_벤처기업을_기업인증으로_판정하지_않는다() {
        var groups = ParticipationCategoryProjector.project(List.of(rule("중소벤처기업부 장관이 인정하는 기업")), false);
        assertThat(row(groups, "CERTIFICATION").entries()).isEmpty();
    }

    @Test void 연구소_이전과_협력기관_지역은_보유인증이나_불완전한_구성요건으로_쪼개지_않는다() {
        var original = rule("역외 기관은 협약체결 전까지 본사 또는 기업부설 연구소의 대구 이전을 완료해야 합니다.");
        var groups = ParticipationCategoryProjector.project(List.of(original), false);
        assertThat(texts(groups, "LOCATION")).contains("본사 또는 기업부설 연구소", "협약체결 전까지", "대구 이전");
        assertThat(row(groups, "CERTIFICATION").entries()).isEmpty();
    }


    @Test void 긴_ZIP_서식_뒤의_공고를_읽고_서식길이를_공고손상으로_오인하지_않는다() {
        String text = "[파일: (양식1) 사업계획서.hwpx]\n" + "작성 예시 자료\n".repeat(30_000)
                + "[파일: (참고1) 가이드북.pdf]\n" + "참고자료\n".repeat(40_000)
                + "[파일: 모집 공고.hwp]\n□ 신청자격\n- 매출액 10억원 이상 중소기업";
        var file = DocumentAttachment.create(null, "공고 첨부.zip", "https://example.org/notice.zip", "zip",
                "application/zip", 1L, "hash", text, AttachmentParseStatus.COMPLETED, null);
        var guide = ParticipationConditionResolver.resolve(null, List.of(file), null);
        assertThat(texts(guide.categories(), "REVENUE")).contains("10억원");
        assertThat(row(guide.categories(), "REPRESENTATIVE").status()).isEqualTo("NOT_STATED");
    }


    @Test void 과거수혜와_무관한_유형별_기업형태_제한을_중복수혜로_분류하지_않는다() {
        var original = rule("법인사업자는 유형1에 신청 가능");
        var targeted = new ParticipationRule("TARGETED", "신청 유형 제한", "유형별 신청 기준", null, "법인사업자",
                List.of(), List.of(), List.of(), List.of(new ParticipationOutcome("유형1", "ELIGIBLE")), original.sources());
        var groups = ParticipationCategoryProjector.project(List.of(targeted), false);
        assertThat(row(groups, "BUSINESS_FORM").entries()).singleElement().satisfies(entry ->
                assertThat(entry.rule().outcomes()).containsExactly(new ParticipationOutcome("유형1", "ELIGIBLE")));
        assertThat(row(groups, "DUPLICATE").entries()).isEmpty();
    }

    @Test void 지역이나_업종이_없는_순수_컨소시엄_구성_조건을_보존한다() {
        var groups = ParticipationCategoryProjector.project(List.of(rule("참여기관은 3개 이상으로 구성해야 합니다.")), false);
        assertThat(texts(groups, "CONSORTIUM")).contains("3개 이상", "구성해야");
    }


    @Test void 가점표의_미확인_기준은_기본자격과_혼동하지_않고_가점_분류에_남긴다() {
        var review = new ParticipationRule("REVIEW", "가점 기준", "가점 표의 조건 연결을 확인해야 합니다.", null, null,
                List.of(), List.of(), List.of(), List.of(),
                List.of(new RequirementSource("NOTICE_BODY", null, "가점", null, "여성기업 수출 실적 매출액 기준 가점")),
                ParticipationReviewReason.TABLE_MAPPING_UNCLEAR);
        var groups = ParticipationCategoryProjector.project(List.of(review), false);
        assertThat(row(groups, "REVENUE").status()).isEqualTo("NOT_STATED");
        assertThat(row(groups, "PREF_REPRESENTATIVE").status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(groups.getLast().rows()).anySatisfy(row -> assertThat(row.code()).isEqualTo("OTHER"));
        assertThat(groups.getFirst().rows()).noneSatisfy(row -> assertThat(row.code()).isEqualTo("OTHER"));
    }

    private String fixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/participation-conditions/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
