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

class ParticipationReadabilityTest {
    @Test
    void 전체_공고의_가점과_감점을_분리하고_모든_조건과_증빙을_보존한다() throws Exception {
        var guide = ParticipationConditionResolver.resolve(null, List.of(official("hwpx")), null);
        var bonus = guide.rules().stream().filter(row -> row.section().equals("PREFERENCE")).toList();
        var penalty = guide.rules().stream().filter(row -> row.section().equals("DEDUCTION")).toList();
        assertThat(bonus).singleElement().satisfies(row -> {
            assertThat(row.scope()).isNull();
            assertThat(row.text()).contains("항목별로 1점", "최대 3점").hasSizeLessThan(180);
            assertThat(row.details().stream().filter(value -> value.split("\n")[0].endsWith("가점"))).hasSize(22);
            String details = String.join("\n", row.details());
            assertThat(details).contains("90점 이상", "2천만원 이상", "2건 이상", "10% 이상", "신규채용예정인력 제외",
                    "보건복지부", "식품의약품안전처", "하나만 인정합니다", "제출하지 않으면 인정되지 않습니다", "의료기기소프트웨어", "법적 자격 기준:");
            assertThat(details).doesNotContain("감점 사항", "14 기타사항", "등를", "증빙를", "인정함.", "必");
        });
        assertThat(penalty).singleElement().satisfies(row -> {
            assertThat(row.text()).contains("미납", "1점을 감점", "최대 3점");
            assertThat(row.details()).anyMatch(value -> value.contains("정당한 사유 없이") && value.contains("최근 3년"));
            assertThat(row.details()).anyMatch(value -> value.contains("납부기한까지 납부하지 않은") && value.contains("제출 마감일"));
        });
        assertThat(guide.rules()).allSatisfy(row -> {
            assertThat(row.text().length()).isLessThanOrEqualTo(230);
            assertThat(row.scope() == null ? "" : row.scope()).doesNotContain("구분 가점", "감점", "기술료");
            assertThat(officialText("hwpx")).contains(row.sources().getFirst().excerpt());
        });
    }

    @Test
    void 깨진_PDF_표는_조건을_지어내거나_거대한_적용범위로_표시하지_않는다() throws Exception {
        var guide = ParticipationConditionResolver.resolve(null, List.of(official("pdf")), null);
        assertThat(guide.rules()).noneMatch(row -> row.section().equals("PREFERENCE") || row.section().equals("DEDUCTION"));
        assertThat(guide.rules().stream().filter(row -> row.section().equals("REVIEW") && row.label().matches("평가 가점|평가 감점"))).hasSize(2).allSatisfy(row -> {
            assertThat(row.reviewReason()).isEqualTo(ParticipationReviewReason.TABLE_MAPPING_UNCLEAR);
            assertThat(row.scope()).isNull();
            assertThat(row.details()).isEmpty();
            assertThat(row.sources().getFirst().excerpt()).contains("구분");
        });
    }

    @Test
    void 두_첨부를_함께_읽어도_정상_표의_설명을_유지한다() throws Exception {
        var guide = ParticipationConditionResolver.resolve(null, List.of(official("hwpx"), official("pdf")), null);
        assertThat(guide.rules().stream().filter(row -> row.section().equals("PREFERENCE"))).hasSize(1);
        assertThat(guide.rules().stream().filter(row -> row.section().equals("DEDUCTION"))).hasSize(1);
        assertThat(guide.rules()).allSatisfy(row -> assertThat(row.text().length()).isLessThanOrEqualTo(230));
        assertThat(guide.rules().stream().filter(row -> row.section().equals("EXCLUSION"))).hasSize(7).allSatisfy(row -> {
            assertThat(row.sources()).hasSize(2);
            assertThat(row.text()).doesNotContain("제조업 여부", "간접비", "코드표준산업");
        });
        assertThat(guide.rules().stream().filter(row -> row.label().equals("경영·재무"))).singleElement().satisfies(row -> {
            assertThat(String.join(" ", row.details())).contains("회생", "자본전액잠식", "2025년도");
            assertThat(String.join(" ", row.exceptions())).contains("비영리기관", "공기업/공사", "비적용");
        });
        Path output = Path.of("build/participation-guide-checks/ax-combined-guide.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writeValueAsString(guide), StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"구분\t가점 사항\t가점\t증빙", "번호\t우대조건\t배점\t확인서류", "순번\t평가내용\t점수\t비고"})
    void 다른_기관의_열_이름과_점수에도_같은_구조로_설명한다(String headers) {
        String original = "□ 우대사항\n[표]\n" + headers
                + "\n1\t국내 소재 기업\t2.5\t사업자등록증\n2\t특허 보유 기업\t0.5점\t특허등록증\n[/표]\n※ 가점 합계 최대 4점 한도\n□ 제출서류\n신청서";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> {
            assertThat(row.section()).isEqualTo("PREFERENCE");
            assertThat(row.text()).contains("최대 4점").doesNotContain("항목별로 2.5점");
            assertThat(row.details()).anyMatch(value -> value.contains("2.5점") && value.contains("국내 소재 기업") && value.contains("사업자등록증"));
            assertThat(row.details()).anyMatch(value -> value.contains("0.5점") && value.contains("특허등록증"));
            assertThat(original).contains(row.sources().getFirst().excerpt());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-1점", "5%"})
    void 가점_점수가_누락되거나_부호가_상충하면_확정하지_않는다(String point) {
        var guide = ParticipationConditionResolver.resolve("□ 가점사항\n[표]\n구분\t가점사항\t가점\n1\t특허 보유 기업\t" + point + "\n[/표]", List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> assertThat(row.section()).isEqualTo("REVIEW"));
    }

    @Test
    void 가점_뒤에_붙은_감점_제목과_다음_절을_분리한다() {
        String original = "□ 가점사항\n구분 가점 사항 가점 증빙\n1 특허보유 2점 등록증"
                + "※ 총 2점 이내□ 공통감점사항\n구분 감점 사항 감점\n1 제재 1점\n14 기타사항\n보안계획 작성";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).hasSize(2).allSatisfy(row -> {
            assertThat(row.section()).isEqualTo("REVIEW");
            assertThat(row.sources().getFirst().excerpt()).doesNotContain("기타사항", "보안계획");
        });
        assertThat(guide.rules().getFirst().sources().getFirst().excerpt()).doesNotContain("감점사항");
    }

    @Test
    void 긴_조건은_요약과_세부_기준으로_나누고_숫자와_원문은_보존한다() {
        String criterion = "주관기업은 연구소를 보유해야 합니다. " + "공동기관은 지역 제한 없이 참여할 수 있으며, 접수 마감일 기준 등록된 기관이어야 합니다. ".repeat(5)
                + "단, 매출액 12.5억원 이하인 기업은 제외합니다.";
        String original = "□ 신청자격\n" + criterion;
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> {
            assertThat(row.text()).contains("주관기업은 연구소");
            assertThat(row.text().length()).isLessThan(230);
            String all = row.text() + String.join(" ", row.details()) + String.join(" ", row.exceptions());
            assertThat(all).contains("12.5억원 이하", "지역 제한 없이", "접수 마감일");
            assertThat(row.sources().getFirst().excerpt()).isEqualTo(original);
        });
    }

    @Test
    void 표시_문자의_잡음을_정리해도_비교_부호와_원문은_유지한다() {
        String original = "□ 신청자격\n- \ue001매출액 ≤ 12.5억원인 기업\n* 단, 지분율 ≥ 30%인 기업은 제외함";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> {
            assertThat(row.text()).contains("≤ 12.5억원").doesNotContain("\ue001");
            assertThat(String.join(" ", row.exceptions())).contains("≥ 30%", "제외합니다");
            assertThat(row.sources().getFirst().excerpt()).isEqualTo(original);
        });
    }

    @Test
    void 사후_기술료_감면의_신청대상을_사업_자격으로_섞지_않는다() {
        String original = "□ 신청자격: 국내 중소기업\n▶ 기술료 감면 및 납부 유예\n- (신청대상) 과제종료 후 인력을 채용한 기업\n- (감면규모) 급여의 40%\n□ 우대사항\n- 특허 보유 기업에 가점 2점";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).hasSize(2).noneMatch(row -> row.text().contains("40%") || row.text().contains("과제종료"));
    }

    @Test
    void 법령을_분리해도_부정과_선택관계_및_판단기준은_유지한다() {
        var explanation = ParticipationReadability.explainLegal("「기업지원법」 제4조에 따른 중소기업 또는 기업부설연구소를 보유한 기업");
        assertThat(explanation.text()).isEqualTo("중소기업 또는 기업부설연구소를 보유한 기업");
        assertThat(explanation.legal()).contains("기업지원법", "제4조");
        assertThat(ParticipationReadability.explainLegal("법 제32조제1항에 따른 사유로 제재처분을 받은 경우").text()).contains("제32조제1항");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "□ 신청자격\n매출액 12\uFFFD억원 이하인 기업",
            "□ 가점사항\n[표]\n구분\t가점사항\t가점\n1\t매출액 12\uFFFD억원 이하인 기업\t1점\n[/표]",
            "□ 신청자격\n국내 중소기업\n* 단, 매출액 12\uFFFD억원 초과 기업은 제외함"})
    void 손상된_숫자나_단어를_이어붙여_정상_조건으로_만들지_않는다(String original) {
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> {
            assertThat(row.section()).isEqualTo("REVIEW");
            assertThat(row.text()).doesNotContain("12억원");
        });
    }

    @Test
    void 기술료_절_뒤의_다음_ZIP_공고를_누락하지_않는다() {
        var file = DocumentAttachment.create(null, "자료.zip", "https://example.org/notice.zip", "zip", "application/zip", 1L,
                "hash", "[파일: 첫 공고.hwpx]\n▶ 기술료 감면 및 납부 유예\n- 신청대상: 종료 기업\n[파일: 둘째 공고.hwpx]\n□ 신청자격: 중소기업", AttachmentParseStatus.COMPLETED, null);
        assertThat(ParticipationConditionResolver.resolve(null, List.of(file), null).rules()).singleElement()
                .satisfies(row -> assertThat(row.text()).contains("중소기업"));
    }

    @Test
    void 조건의_주어와_증빙의_목적어를_구분하고_조사와_종결을_맞춘다() {
        assertThat(ParticipationReadability.clause("표창을 받은 자")).isEqualTo("표창을 받은 자가 대상입니다.");
        assertThat(ParticipationReadability.clause("공동기관을 포함한 연구개발과제")).endsWith("과제가 대상입니다.");
        assertThat(ParticipationReadability.clause("여성기업")).isEqualTo("여성기업이 대상입니다.");
        assertThat(ParticipationReadability.evidenceClause("우수연구개발 혁신제품")).isEqualTo("우수연구개발 혁신제품을 확인해야 합니다.");
        assertThat(ParticipationReadability.evidenceClause("공동대표의 경우 모두 여성인 경우")).endsWith("모두 여성인 경우입니다.");
        assertThat(ParticipationReadability.clause("관련 법률 시행령에 따름")).isEqualTo("관련 법률 시행령에 따릅니다.");
        assertThat(ParticipationReadability.clause("해당 제품을 혁신제품으로 지정")).isEqualTo("해당 제품을 혁신제품으로 지정합니다.");
        assertThat(ParticipationReadability.clause("최대 3점 이내로 가점 부여 가능")).isEqualTo("최대 3점 이내로 가점을 부여할 수 있습니다.");
    }

    private DocumentAttachment official(String extension) throws Exception {
        return DocumentAttachment.create(null, "연구개발 사업 공고." + extension, "https://example.org/notice." + extension,
                extension, "application/octet-stream", 1L, "hash", officialText(extension), AttachmentParseStatus.COMPLETED, null);
    }

    private String officialText(String extension) {
        try (var stream = getClass().getResourceAsStream("/participation-conditions/ax-research-" + extension + ".txt")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
