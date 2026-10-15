package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ParticipationGuide;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ParticipationKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ParticipationConditionReadabilityTest {
    @ParameterizedTest
    @ValueSource(strings = {"startup-structured", "startup-legacy"})
    void 주관기관과_수혜기업을_구별하고_제외_조건을_누락하지_않는다(String fixture) throws Exception {
        var rows = resolve(fixture).conditions();
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.text()).contains("공공기관의 신청 기준");
            assertThat(row.kind()).isEqualTo(ParticipationKind.MANDATORY);
            assertThat(String.join(" ", row.details())).contains("공공기관", "지방공기업", "출자");
        }).anySatisfy(row -> {
            assertThat(row.text()).contains("민간기관");
            assertThat(row.kind()).isEqualTo(ParticipationKind.MANDATORY);
            assertThat(String.join(" ", row.details())).contains("일반기업", "신용평가등급", "이상");
        }).anySatisfy(row -> {
            assertThat(row.text()).contains("체납");
            assertThat(row.kind()).isEqualTo(ParticipationKind.EXCLUSION);
        }).anySatisfy(row -> {
            assertThat(row.text()).contains("10년 이내");
            assertThat(row.category()).isEqualTo("지원 대상");
            assertThat(row.scope()).contains("주관기관 자격과 별도");
        });
        assertThat(rows).noneMatch(row -> row.category().equals("업력·창업") && row.text().contains("창업기업 기술 실증"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"policy-loan-hwpx", "policy-loan-pdf"})
    void 자금별_문장을_복원하고_대상과_한정어를_보존한다(String fixture) throws Exception {
        var rows = resolve(fixture).conditions();
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.text()).contains("스마트기술", "신사업창업사관학교 수료생");
            assertThat(row.scope()).contains("혁신성장촉진자금");
        }).anySatisfy(row -> {
            assertThat(row.text()).contains("투자금을 지원받고", "선투자 추천서");
            assertThat(row.scope().replace(" ", "")).contains("민간투자연계형매칭융자");
        }).anySatisfy(row -> {
            assertThat(row.text()).contains("장애인복지카드", "소지한 장애 소상공인");
            assertThat(row.scope()).contains("장애인기업지원자금");
        }).anySatisfy(row -> {
            assertThat(row.text()).contains("TOPS", "1단계 선정");
            assertThat(row.scope()).contains("상생성장지원자금");
        }).anySatisfy(row -> {
            assertThat(row.text()).contains("희망형", "완료하거나", "협약");
            assertThat(row.scope()).contains("재도전특별자금");
            assertThat(String.join(" ", row.details())).contains("3회차");
        });
        assertThat(rows).noneMatch(row -> row.text().matches(".*(?:투자금을|신사업|확인서를|완료하거나)[.]?$"));
    }

    @org.junit.jupiter.api.Test
    void 법령은_대상_뒤에_정리하고_예외는_본문과_분리하되_의미를_보존한다() {
        assertThat(ParticipationConditionText.sentence("『지방공기업법』 제2조(적용범위)에 해당하는 법인·단체 또는 기관", null))
                .isEqualTo("법인·단체 또는 기관 (근거: 『지방공기업법』 제2조(적용범위)).");
        var paragraphs = ParticipationConditionText.paragraphs(List.of(
                "세금을 체납한 소상공인. 단, 압류·매각 유예를 받은 소상공인은 직접대출에 한해 포함."));
        assertThat(paragraphs).containsExactly("세금을 체납한 소상공인.",
                "단, 압류·매각 유예를 받은 소상공인은 직접대출에 한해 포함.");
        assertThat(ParticipationConditionText.paragraphs(List.of("◦ 「브랜드 소상공인", "점프업 사업」 선정 소상공인")))
                .containsExactly("◦ 「브랜드 소상공인 점프업 사업」 선정 소상공인");
    }

    @org.junit.jupiter.api.Test
    void 일부_대상의_점수_면제를_모든_신청자의_필수조건으로_바꾸지_않는다() {
        var guide = ParticipationConditionResolver.resolve(
                "□ 신청대상: 중·저신용(NCB 919점 이하) 소상공인\n"
                + "◦ 다음 각 호 중 1개 이상에 해당하는 경우 개인신용평점 미적용\n"
                + "① 연체 없이 3회차 이상 상환한 소상공인\n"
                + "② 1년 후 신용점수가 70점 이상 상승한 소상공인",
                List.of(), null);
        assertThat(guide.conditions()).singleElement().satisfies(row -> {
            assertThat(row.text()).contains("919점 이하");
            assertThat(String.join(" ", row.details())).contains("1개 이상", "미적용", "3회차", "70점");
        });
    }

    private ParticipationGuide resolve(String fixture) throws Exception {
        String original;
        try (var stream = getClass().getResourceAsStream("/participation-conditions/" + fixture + ".txt")) {
            original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var file = DocumentAttachment.create(null, fixture + " 공고.hwpx", "https://example.org/notice", "hwpx",
                "application/zip", 1L, "hash", original, AttachmentParseStatus.COMPLETED, null);
        var guide = ParticipationConditionResolver.resolve(null, List.of(file), null);
        Path output = Path.of("build/participation-guide-checks/" + fixture + "-guide.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writeValueAsString(guide), StandardCharsets.UTF_8);
        assertThat(guide.conditions()).isNotEmpty().allSatisfy(row -> assertThat(original).contains(row.source().excerpt()));
        return guide;
    }
}
