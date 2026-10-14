package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ParticipationKind;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.Preparation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ParticipationConditionResolverTest {
    @Test
    void 필수와_우대와_제외를_구별하고_수치와_조건_예외를_보존한다() {
        var rows = resolve("""
                □ 신청 자격
                ◦ 공고일 기준 업력 7년 이내 중소기업
                ◦ 직전년도 매출액 10억 원 이상 50억 원 미만
                □ 우대사항
                ◦ 관련 분야 등록 특허 보유 기업은 가점 2점
                * 출원 중인 특허는 인정하지 않음
                □ 지원 제외
                ◦ 국세 또는 지방세 체납 기업
                ※ 단, 접수 마감일까지 완납한 경우 신청할 수 있습니다
                □ 제출서류
                ◦ 사업계획서 1부
                """).conditions();
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(row -> row.kind()).containsExactly(
                ParticipationKind.MANDATORY, ParticipationKind.MANDATORY, ParticipationKind.PREFERENCE, ParticipationKind.EXCLUSION);
        assertThat(rows.get(0).text()).contains("7년 이내");
        assertThat(rows.get(1).text()).contains("10억 원 이상", "50억 원 미만");
        assertThat(rows.get(2).details()).contains("출원 중인 특허는 인정하지 않습니다.");
        assertThat(rows.get(3).details()).anyMatch(value -> value.contains("완납한 경우 신청할 수 있습니다"));
        assertThat(rows).noneMatch(row -> row.text().contains("사업계획서"));
    }

    @Test
    void 선택_또는_동시충족_조건은_하나의_묶음으로_보존한다() {
        var alternative = resolve("□ 신청자격: 다음 중 하나 충족\n① 업력 3년 이내 기업\n② 매출액 10억 원 이상 기업").conditions();
        assertThat(alternative).singleElement().satisfies(row -> {
            assertThat(row.text()).contains("다음 중 하나");
            assertThat(row.details()).hasSize(2);
        });
        var joint = resolve("□ 신청자격: 다음 조건을 모두 충족\n① 업력 3년 이내 기업\n② 매출액 10억 원 이상 기업").conditions();
        assertThat(joint).hasSize(2).allSatisfy(row -> assertThat(row.scope()).contains("모두 충족"));
    }

    @Test
    void 대상과_기준일을_개별_행에_연결한다() {
        var rows = resolve("□ 신청자격: 공고일 기준 국내 제조기업\n① 업력 7년 이내\n② 연 매출 20억 원 이하").conditions();
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.scope()).isEqualTo("공고일 기준 국내 제조기업");
            assertThat(row.source().excerpt()).contains("공고일 기준 국내 제조기업");
        });
    }

    @Test
    void 제외_조건의_번호가_있는_예외를_별도_제외대상으로_만들지_않는다() {
        var rows = resolve("""
                □ 제외대상
                ① 국세 또는 지방세 체납 기업
                < 조건 예외 대상 >
                1. 징수 유예를 받은 기업
                2. 완납 후 증빙이 가능한 기업
                ② 휴업 또는 폐업 중인 기업
                """).conditions();
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().details()).anyMatch(v -> v.contains("유예")).anyMatch(v -> v.contains("완납"));
        assertThat(rows.getLast().text()).contains("휴업");
    }

    @Test
    void 공고_본문과_첨부의_동일한_조건은_중복하지_않는다() {
        String text = "□ 지원대상: 제조 중소기업\n□ 우대사항: 특허 보유 기업";
        var rows = ParticipationConditionResolver.resolve(text, List.of(file("공고.hwpx", text)), null).conditions();
        assertThat(rows).hasSize(2);
    }

    @Test
    void 양식_예시와_실패첨부_및_ZIP_내부_경계를_보호한다() {
        String good = "□ 지원대상: 업력 7년 이내 기업";
        String fake = "□ 우대사항: 매출 999억 원 기업";
        var failed = DocumentAttachment.create(null, "실패.pdf", "https://example.org", "pdf", "application/pdf",
                1L, "hash", fake, AttachmentParseStatus.FAILED, "parse failed");
        var rows = ParticipationConditionResolver.resolve(good + "\n[붙임1] 신청서\n" + fake,
                List.of(file("신청서.hwpx", fake), failed,
                        file("자료.zip", "[파일: 예시.hwpx]\n" + fake + "\n[파일: 공고.hwpx]\n" + good)), null).conditions();
        assertThat(rows).singleElement().satisfies(row -> assertThat(row.text()).contains("7년"));
    }

    @Test
    void 구조화된_표의_구분과_대상_및_공통각주를_유지한다() {
        var rows = resolve("""
                □ 신청자격: 공고일 기준
                [표]
                구분\t내용\t대상
                필수\t업력 7년 이내\t주관기업
                우대\t등록 특허 보유 시 2점\t참여기업
                \t매출 10억 원 이상\t공동기업
                [/표]
                ※ 공동기업은 매출 기준 적용 예외
                □ 신청방법
                온라인 신청
                """).conditions();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).kind()).isEqualTo(ParticipationKind.MANDATORY);
        assertThat(rows.get(1).kind()).isEqualTo(ParticipationKind.PREFERENCE);
        assertThat(rows.get(2).kind()).isEqualTo(ParticipationKind.REVIEW_REQUIRED);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.scope()).isEqualTo("공고일 기준");
            assertThat(row.details()).anyMatch(v -> v.contains("공동기업은 매출 기준 적용 예외"));
            assertThat(row.source().excerpt()).contains("공고일 기준", "\t", "공동기업은 매출 기준 적용 예외");
        });
        assertThat(rows.getFirst().details()).contains("대상: 주관기업.");
    }

    @Test
    void 잘린_표와_분류가_없는_임의의_표를_조건으로_만들지_않는다() {
        assertThat(resolve("□ 신청자격\n[표]\n구분\t내용\n필수\t업력 7년 이내").conditions()).isEmpty();
        assertThat(resolve("[표]\n구분\t내용\n성과\t매출 10억 원\n[/표]").conditions()).isEmpty();
    }

    @Test
    void 우대_없음과_가점_미적용을_우대_조건으로_단정하지_않는다() {
        assertThat(resolve("□ 우대사항: 없음").conditions()).isEmpty();
        assertThat(resolve("□ 가점사항\n특허 보유기업에 가점을 부여하지 않음").conditions())
                .singleElement().satisfies(row -> assertThat(row.kind()).isEqualTo(ParticipationKind.REVIEW_REQUIRED));
    }

    @Test
    void 저장_분석은_현재원문_검증후_구분확인으로만_보완한다() {
        Preparation preparation = new ObjectMapper().readValue("""
                {"eligibilityChecklist":[
                {"title":"지어낸 제목","detail":"매출액 999억","requirementLevel":"MANDATORY",
                 "source":{"origin":"ATTACHMENT","attachmentName":"공고.pdf","sectionTitle":"안내","excerpt":"업력 7년 이내 기업만 신청 가능"}},
                {"title":"가짜 조건","source":{"origin":"NOTICE_BODY","excerpt":"매출액 999억"}},
                {"title":"권장 자격","requirementLevel":"RECOMMENDED","source":{"origin":"NOTICE_BODY","excerpt":"특허 보유 기업"}},
                {"title":"회사 조건","source":{"origin":"COMPANY_PROFILE","excerpt":"제조 중소기업"}}
                ]}
                """, Preparation.class);
        var rows = ParticipationConditionResolver.resolve("특허 보유 기업", List.of(file("공고.pdf", "업력 7년 이내 기업만 신청 가능")), preparation).conditions();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo(ParticipationKind.REVIEW_REQUIRED);
            assertThat(row.text()).isEqualTo("업력 7년 이내 기업만 신청할 수 있습니다.");
        });
        assertThat(ParticipationConditionResolver.resolve(null, List.of(file("공고.pdf", "[붙임1] 신청서\n업력 7년 이내 기업만 신청 가능")), preparation).conditions()).isEmpty();
    }

    @Test
    void 긴_원문의_잘린_마지막_조건은_확정하지_않는다() {
        String text = "머".repeat(199_970) + "\n□ 신청자격\n업력 7년 이내 기업\n※ 예외사항은 다음 기준을 적용하며 상세한 사항을 확인하여야 함";
        assertThat(resolve(text).conditions()).isEmpty();
    }

    @Test
    void CBAM_전체_공고에서_신청자격과_가점_및_제외_예외를_읽는다() throws Exception {
        String original;
        try (var stream = getClass().getResourceAsStream("/submission-documents/cbam-structured.txt")) {
            original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var rows = ParticipationConditionResolver.resolve(null, List.of(file("CBAM 공고.hwpx", original)), null).conditions();
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.kind()).isEqualTo(ParticipationKind.MANDATORY);
            assertThat(row.text()).contains("사업자등록증", "제조");
        }).anySatisfy(row -> {
            assertThat(row.kind()).isEqualTo(ParticipationKind.PREFERENCE);
            assertThat(row.text()).contains("가점 2점을 부여합니다");
            assertThat(String.join(" ", row.details())).contains("수출 계약서", "증빙");
        }).anySatisfy(row -> {
            assertThat(row.kind()).isEqualTo(ParticipationKind.EXCLUSION);
            assertThat(row.text()).contains("체납");
            assertThat(row.scope()).contains("하나 이상");
            assertThat(String.join(" ", row.details())).contains("징수의 유예");
        }).anySatisfy(row -> {
            assertThat(row.kind()).isEqualTo(ParticipationKind.EXCLUSION);
            assertThat(row.text()).contains("유형1 신청할 수 없습니다");
        });
        var output = java.nio.file.Path.of("build/participation-guide-checks/cbam-guide.json");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, new ObjectMapper().writeValueAsString(
                ParticipationConditionResolver.resolve(null, List.of(file("CBAM 공고.hwpx", original)), null)), StandardCharsets.UTF_8);
        assertThat(rows).allSatisfy(row -> {
            assertThat(original).contains(row.source().excerpt());
            assertThat(row.source().excerpt()).doesNotContain("제공받는 자의 목적");
        });
    }


    @Test
    void 일반_문장의_자격_언급과_서류_표는_참여조건으로_추가하지_않는다() {
        assertThat(resolve("5. 신청자격 관련 증빙서류는 원문 참조\n6. 사후관리 의무 준수").conditions()).isEmpty();
        assertThat(resolve("[표]\n구분\t내용\n필수\t사업계획서 제출\n[/표]").conditions()).isEmpty();
    }

    @Test
    void 필수와_우대가_섞인_문장은_전부_필수로_단정하지_않는다() {
        assertThat(resolve("□ 신청자격\n중소기업이며 특허 보유기업 우대").conditions())
                .singleElement().satisfies(row -> assertThat(row.kind()).isEqualTo(ParticipationKind.REVIEW_REQUIRED));
    }

    @Test
    void 표의_빈줄과_원문_각주_줄바꿈까지_인용을_보존한다() {
        String text = "□ 신청자격: 공고일 기준\r\n\r\n[표]\r\n구분\t내용\r\n필수\t업력 7년 이내\r\n[/표]\r\n\r\n※ 예외 기업은 원문 확인\r\n";
        assertThat(resolve(text).conditions()).singleElement()
                .satisfies(row -> assertThat(text).contains(row.source().excerpt()));
    }


    @Test
    void 공통_각주의_기준일은_마지막_항목에만_붙이지_않는다() {
        var rows = resolve("□ 신청자격\n① 업력 7년 이내\n② 매출 10억 원 이상\n※ 모든 기업은 공고일 기준으로 판단합니다.").conditions();
        assertThat(rows).hasSize(2).allSatisfy(row ->
                assertThat(row.details()).anyMatch(value -> value.contains("공고일 기준")));
    }

    private static com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ParticipationGuide resolve(String text) {
        return ParticipationConditionResolver.resolve(text, List.of(), null);
    }

    private static DocumentAttachment file(String name, String text) {
        return DocumentAttachment.create(null, name, "https://example.org/notice", "hwpx", "application/zip",
                1L, "hash", text, AttachmentParseStatus.COMPLETED, null);
    }
}
