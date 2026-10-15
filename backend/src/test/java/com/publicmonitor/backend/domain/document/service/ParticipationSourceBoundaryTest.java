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

class ParticipationSourceBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"pdf", "hwpx"})
    void 공식_공고의_대상과_예외를_보존하고_지원비용을_자격에서_분리한다(String extension) throws Exception {
        String original;
        try (var stream = getClass().getResourceAsStream("/participation-conditions/certification-" + extension + ".txt")) {
            original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var file = DocumentAttachment.create(null, "인증 지원 공고." + extension, "https://example.org/certification." + extension,
                extension, "application/octet-stream", 1L, "hash", original, AttachmentParseStatus.COMPLETED, null);
        var guide = ParticipationConditionResolver.resolve(null, List.of(file), null);
        assertThat(guide.rules().stream().filter(row -> row.section().equals("BASIC")).toList()).singleElement().satisfies(row -> {
            assertThat(row.text()).isEqualTo("전년도 직접수출액 5,000만불 미만의 중소기업이 대상입니다.");
            assertThat(row.scope()).isNull();
            assertThat(String.join(" ", row.exceptions())).contains("글로벌 강소기업 1000+", "5,000만불 이상", "지원 가능");
            assertThat(row.sources().getFirst().excerpt()).doesNotContain("지원기간", "지원한도", "시험비", "컨설팅비");
        });
        assertThat(guide.rules()).noneMatch(row -> row.text().startsWith("인증비") || row.text().startsWith("시험비") || row.text().startsWith("컨설팅비"));
        assertThat(guide.rules()).anySatisfy(row -> {
            assertThat(row.text()).contains("국세", "지방세", "체납");
            assertThat(row.section()).isEqualTo("EXCLUSION");
            assertThat(String.join(" ", row.exceptions())).contains("변제 완료", "신청할 수 있습니다");
        });
        assertThat(guide.conditions()).allSatisfy(row -> assertThat(original).contains(row.source().excerpt()));
        Path output = Path.of("build/participation-guide-checks/certification-" + extension + "-guide.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writeValueAsString(guide), StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"지원내용", "지원기간", "지원한도", "지원비율", "지원금액", "지원항목", "모집규모", "모집일정"})
    void 공고명과_금액에_의존하지_않고_이어붙은_제목을_구별한다(String heading) {
        String original = "□신청자격:매출액12억원미만의중소기업 * 단, 재도전기업은 12억원 이상도 신청 가능"
                + "□" + heading + ":기업당 7천만원 지원 - 시험비 : 제품 시험에 소요되는 비용"
                + "□신청제외:국세 체납 기업\r\n* 단, 납부 완료 기업은 신청 가능";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).hasSize(2).anySatisfy(row -> {
            assertThat(row.text()).isEqualTo("매출액 12억원 미만의 중소기업이 대상입니다.");
            assertThat(String.join(" ", row.exceptions())).contains("12억원 이상", "신청할 수 있습니다");
            assertThat(row.scope()).isNull();
        });
        assertThat(guide.conditions()).allSatisfy(row -> assertThat(original).contains(row.source().excerpt()));
        assertThat(guide.rules()).noneMatch(row -> row.text().contains("시험비"));
    }

    @Test
    void 실제_인증_요건과_부정_예외는_비용_설명으로_버리지_않는다() {
        String original = "□ 신청자격: 다음 조건을 모두 충족하는 기업\n"
                + "◦ 유효한 ISO 9001 인증을 보유한 기업\n"
                + "◦ 인증비를 체납하지 않은 기업\n* 단, 신청 마감일까지 납부한 기업은 신청 가능\n"
                + "□ 지원내용: 인증 시험비 일부 지원";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).hasSize(2).anySatisfy(row -> {
            assertThat(row.text()).contains("ISO 9001", "보유");
            assertThat(row.scope()).contains("모두 충족");
        }).anySatisfy(row -> {
            assertThat(row.text()).contains("인증비", "체납하지 않은");
            assertThat(String.join(" ", row.exceptions())).contains("납부한 기업");
        });
    }

    @Test
    void 한줄_표제_복원에도_대안과_개행을_포함한_원문을_그대로_인용한다() {
        String original = "□ 신청대상: 다음 중 하나에 해당하는 기업\r\n① 특허 보유 기업\r\n② 인증 보유 기업□지원기간: 6개월";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> {
            assertThat(row.text()).contains("하나");
            assertThat(String.join(" ", row.details())).contains("특허", "인증");
            assertThat(row.sources().getFirst().excerpt()).isEqualTo(original.substring(0, original.indexOf("□지원기간")));
        });
    }
    @ParameterizedTest
    @ValueSource(strings = {"지원비율을 충족할 자부담 능력이 있는 기업", "지원한도 이상 자기자본을 보유한 기업", "지원항목 중 하나에 해당하는 기업"})
    void 제목_단어로_시작해도_실제_자격_문장은_보존한다(String criterion) {
        var guide = ParticipationConditionResolver.resolve("□ 신청자격\n◦ " + criterion, List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> assertThat(row.text()).contains(criterion));
    }

    @Test
    void 여러_첨부에서_같은_대상은_한번_표시하고_각_원문은_보존한다() throws Exception {
        var attachments = new java.util.ArrayList<DocumentAttachment>();
        for (String extension : List.of("pdf", "hwpx")) {
            try (var stream = getClass().getResourceAsStream("/participation-conditions/certification-" + extension + ".txt")) {
                String original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                attachments.add(DocumentAttachment.create(null, "인증 지원 공고." + extension, "https://example.org/certification." + extension,
                        extension, "application/octet-stream", 1L, "hash", original, AttachmentParseStatus.COMPLETED, null));
            }
        }
        var guide = ParticipationConditionResolver.resolve(null, attachments, null);
        assertThat(guide.rules().stream().filter(row -> row.section().equals("BASIC")).toList()).singleElement().satisfies(row -> {
            assertThat(row.text()).isEqualTo("전년도 직접수출액 5,000만불 미만의 중소기업이 대상입니다.");
            assertThat(row.sources()).hasSize(2);
        });
        assertThat(guide.rules()).allSatisfy(row -> assertThat(row.sources()).allSatisfy(source -> {
            var original = attachments.stream().filter(file -> file.getFileName().equals(source.attachmentName())).findFirst().orElseThrow();
            assertThat(original.getExtractedText()).contains(source.excerpt());
        }));
        Files.createDirectories(Path.of("build/participation-guide-checks"));
        Files.writeString(Path.of("build/participation-guide-checks/certification-combined-guide.json"), new ObjectMapper().writeValueAsString(guide), StandardCharsets.UTF_8);
    }

}
