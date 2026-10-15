package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ParticipationReviewReasonTest {
    @Test
    void 표_분류_혼합_우대미적용의_발생_사유를_원문과_함께_전달한다() {
        var table = ParticipationConditionResolver.resolve("□ 신청자격\n[표]\n분류\t조건\n\t매출 10억 이상\n[/표]", List.of(), null);
        assertThat(table.conditions()).singleElement().satisfies(row ->
                assertThat(row.reviewReason()).isEqualTo(ParticipationReviewReason.CLASSIFICATION_UNCLEAR));
        assertThat(table.rules()).singleElement().satisfies(row ->
                assertThat(row.reviewReason()).isEqualTo(ParticipationReviewReason.CLASSIFICATION_UNCLEAR));
        var mixed = ParticipationConditionResolver.resolve("□ 신청자격\n중소기업이며 특허 보유기업 우대", List.of(), null);
        assertThat(mixed.rules()).singleElement().satisfies(row ->
                assertThat(row.reviewReason()).isEqualTo(ParticipationReviewReason.MIXED_REQUIREMENT));
        var negated = ParticipationConditionResolver.resolve("□ 가점사항\n특허 보유기업에 가점을 부여하지 않음", List.of(), null);
        assertThat(negated.rules()).singleElement().satisfies(row ->
                assertThat(row.reviewReason()).isEqualTo(ParticipationReviewReason.PREFERENCE_NEGATED));
        var basic = ParticipationConditionResolver.resolve("□ 신청자격\n업력 7년 이내 기업", List.of(), null);
        assertThat(basic.rules()).allSatisfy(row -> assertThat(row.reviewReason()).isNull());
    }

    @Test
    void 문장미완성과_유형상충은_서로_다른_사유로_남긴다() {
        var source = new RequirementSource("NOTICE_BODY", null, "신청자격", null, "사업 지원을");
        var incomplete = new ParticipationCondition(ParticipationKind.MANDATORY, "참여 자격", "사업 지원을", null, List.of(), source);
        assertThat(ParticipationGuidePresentation.rules(List.of(incomplete))).singleElement().satisfies(row -> {
            assertThat(row.reviewReason()).isEqualTo(ParticipationReviewReason.INCOMPLETE_TEXT);
            assertThat(row.details()).contains("사업 지원을");
            assertThat(row.sources()).containsExactly(source);
        });
        var allow = new ParticipationCondition(ParticipationKind.MANDATORY, "참여 자격", "기존 기업은 유형1 신청할 수 있습니다.", null, List.of(), source);
        var deny = new ParticipationCondition(ParticipationKind.EXCLUSION, "참여 자격", "기존 기업은 유형1 신청할 수 없습니다.", null, List.of(), source);
        assertThat(ParticipationGuidePresentation.rules(List.of(allow, deny))).singleElement().satisfies(row -> {
            assertThat(row.reviewReason()).isEqualTo(ParticipationReviewReason.CONFLICTING_CRITERIA);
            assertThat(row.outcomes()).hasSize(2);
        });
    }

    @Test
    void 같은_문장도_서로_다른_확인사유를_덮어쓰지_않는다() {
        var source = new RequirementSource("NOTICE_BODY", null, "신청자격", null, "업력 7년 이내 기업");
        var rows = List.of(ParticipationReviewReason.CLASSIFICATION_UNCLEAR, ParticipationReviewReason.SOURCE_CONTEXT_UNCONFIRMED).stream()
                .map(reason -> new ParticipationCondition(ParticipationKind.REVIEW_REQUIRED, "업력·창업", source.excerpt(), null, List.of(), source, reason)).toList();
        assertThat(ParticipationGuidePresentation.rules(rows)).hasSize(2).extracting(ParticipationRule::reviewReason)
                .containsExactly(ParticipationReviewReason.CLASSIFICATION_UNCLEAR, ParticipationReviewReason.SOURCE_CONTEXT_UNCONFIRMED);
    }

    @Test
    void 주관기관_원문에서_표연결과_전문가단_전담인력의_문맥확인_사유를_구별한다() throws Exception {
        String original;
        try (var stream = getClass().getResourceAsStream("/participation-conditions/startup-legacy.txt")) {
            original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Reproduce the screenshot's split role cells without changing the actual criteria.
        original = original.replace("\r\n", "\n").replace("\n공공기관\n", "\n공공·\n기관\n").replace("\n민간기관\n", "\n민간·\n기관\n");
        String expert = original.lines().filter(line -> line.contains("(전문가단)")).findFirst().orElseThrow();
        int staffStart = original.indexOf("\n전담인력\n") + 1;
        int staffEnd = original.indexOf("\n* 예시", staffStart);
        String staff = original.substring(staffStart, staffEnd);
        var mapper = new ObjectMapper();
        var entries = List.of(expert, staff).stream().map(quote -> Map.of(
                "title", "확인 항목", "requirementLevel", "MANDATORY", "source", Map.of(
                "origin", "ATTACHMENT", "attachmentName", "주관기관 공고.pdf", "sectionTitle", "주관기관 요건", "excerpt", quote))).toList();
        var preparation = mapper.readValue(mapper.writeValueAsString(Map.of("eligibilityChecklist", entries)), Preparation.class);
        var attachment = DocumentAttachment.create(null, "주관기관 공고.pdf", "https://example.org/notice.pdf", "pdf",
                "application/pdf", 1L, "hash", original, AttachmentParseStatus.COMPLETED, null);
        var guide = ParticipationConditionResolver.resolve(null, List.of(attachment), preparation);
        var reviews = guide.rules().stream().filter(row -> row.section().equals("REVIEW")).toList();
        assertThat(reviews).hasSize(3).extracting(ParticipationRule::reviewReason).containsExactly(
                ParticipationReviewReason.TABLE_MAPPING_UNCLEAR,
                ParticipationReviewReason.SOURCE_CONTEXT_UNCONFIRMED,
                ParticipationReviewReason.SOURCE_CONTEXT_UNCONFIRMED);
        assertThat(reviews.get(1).sources().getFirst().excerpt()).isEqualTo(expert).contains("30명 이상");
        assertThat(reviews.get(2).sources().getFirst().excerpt()).isEqualTo(staff).contains("3년 이상");
        String json = mapper.writeValueAsString(guide);
        assertThat(json).contains("\"reviewReason\":\"TABLE_MAPPING_UNCLEAR\"", "SOURCE_CONTEXT_UNCONFIRMED");
        Path output = Path.of("build/participation-guide-checks/startup-review-guide.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, json, StandardCharsets.UTF_8);
    }
}
