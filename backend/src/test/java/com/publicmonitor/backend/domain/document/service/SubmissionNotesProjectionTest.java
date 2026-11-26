package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static com.publicmonitor.backend.domain.document.client.SubmissionNotesContract.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SubmissionNotesProjectionTest {
    @Test void repeatedAttachmentExportsKeepExactDocumentCatalogEvidence() {
        String section = "제출서류(별지 참조)\n(1) 신청서\n(2) 운영계획서";
        var first = com.publicmonitor.backend.domain.document.entity.DocumentAttachment.create(null, "공고.hwpx", null, "hwpx", null, null, null, "앞부분\n" + section,
                com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus.COMPLETED, null);
        var second = com.publicmonitor.backend.domain.document.entity.DocumentAttachment.create(null, "공고.hwpx", null, "hwpx", null, null, null, section + "\n뒷부분",
                com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus.COMPLETED, null);
        var raw = SubmissionDocumentResolver.resolve(null, List.of(first, second), null);
        var input = SubmissionNotesProjection.input(null, List.of(first, second), raw);
        assertThat(raw.documents()).hasSize(2);
        assertThat(input.request().documents()).hasSize(2).allSatisfy(d -> {
            assertThat(d.sourceId()).isEqualTo("s0");
            assertThat(input.origins().get(d.sourceId()).text()).contains(d.excerpt());
        });
    }

    @Test void submissionMethodDoesNotGainExclusivityEvenFromStoredResults() {
        for (String method : List.of("기관장 명의의 공문", "온라인 신청 시스템", "등기우편")) {
            String quote = method + " 제출";
            var raw = new SubmissionGuide(List.of(), List.of());
            var input = SubmissionNotesProjection.input("제출 방식\n" + quote, List.of(), raw);
            var safe = new Note("s0", quote, method + " 방식으로 제출합니다.", "COMMON", null, "제출 방식", null);
            assertThat(SubmissionNotesProjection.guide(raw, input, new Response(List.of(safe), "READY")).notes()).hasSize(1);
            var bad = new Note("s0", quote, method + " 방식으로만 접수합니다.", "COMMON", null, "제출 방식", null);
            var result = SubmissionNotesProjection.guide(raw, input, new Response(List.of(bad), "READY"));
            assertThat(result.notes()).isEmpty();
            assertThat(result.notesStatus()).isEqualTo("PARTIAL");
        }
        String quote = "온라인으로만 접수";
        var raw = new SubmissionGuide(List.of(), List.of());
        var input = SubmissionNotesProjection.input(quote, List.of(), raw);
        var exclusive = new Note("s0", quote, "온라인으로만 접수합니다.", "COMMON", null, quote, null);
        assertThat(SubmissionNotesProjection.guide(raw, input, new Response(List.of(exclusive), "READY")).notes()).hasSize(1);
    }

    @Test void structuredPythonResponsePreservesCommonAndDocumentEvidence() throws Exception {
        var mapper = new ObjectMapper();
        var fixture = mapper.readTree(Files.readString(Path.of("src/test/resources/submission-documents/semantic-notes.json")));
        var request = mapper.treeToValue(fixture.path("request"), Request.class);
        var response = mapper.treeToValue(fixture.path("result"), Response.class);
        var row = request.documents().getFirst();
        var document = new SubmissionDocument(row.title(), null, "CONDITIONAL", row.stage(), null,
                new RequirementSource("ATTACHMENT", "공고문.pdf", "제출서류", "행 1", row.excerpt()),
                List.of(row.title()), List.of(), List.of());
        var origins = new LinkedHashMap<String, SubmissionNotesProjection.Origin>();
        for (var source : request.sources()) origins.put(source.id(), new SubmissionNotesProjection.Origin(
                source.id(), source.text(), "s0".equals(source.id()) ? null : "공고문.pdf"));
        var input = new SubmissionNotesProjection.Input(request, origins, Map.of("d0", document));
        var result = SubmissionNotesProjection.guide(new SubmissionGuide(List.of(document), List.of()), input, response);
        assertThat(result.notesStatus()).isEqualTo("READY");
        assertThat(result.notes()).hasSize(2);
        assertThat(result.notes().stream().filter(n -> n.appliesTo() == null)).singleElement()
                .satisfies(n -> assertThat(n.text()).contains("이후", "불가").doesNotContain("1357", "문의", "\uF06F"));
        assertThat(result.notes().stream().filter(n -> n.appliesTo() != null)).singleElement().satisfies(n -> {
            assertThat(n.appliesTo()).isEqualTo("신청서");
            assertThat(n.text()).contains("3개월", "이전", "개인", "면제");
            assertThat(n.source().excerpt()).isEqualTo(row.excerpt());
        });
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/submission-notes-projected.json"), mapper.writeValueAsString(result));
    }
    @Test void equalTitlesInDifferentStagesNeedTheMatchingRow() {
        String row1 = "신청서: 신청 시 대표자 서명 필수", row2 = "신청서: 협약 시 기관장 날인 필수";
        var first = new SubmissionDocument("신청서", null, "MANDATORY", "APPLICATION", null,
                new RequirementSource("NOTICE_BODY", null, "제출서류", "행 1", row1));
        var second = new SubmissionDocument("신청서", null, "MANDATORY", "AGREEMENT", null,
                new RequirementSource("NOTICE_BODY", null, "제출서류", "행 2", row2));
        var raw = new SubmissionGuide(List.of(first, second), List.of());
        var input = SubmissionNotesProjection.input(row1 + "\n" + row2, List.of(), raw);
        var good = new Note("s0", row2, "신청서는 협약 시 기관장 날인이 필수입니다.", "DOCUMENT", "d1", row2, null);
        assertThat(SubmissionNotesProjection.guide(raw, input, new Response(List.of(good), "READY")).notes()).hasSize(1);
        var wrong = new Note("s0", row2, good.text(), "DOCUMENT", "d0", row2, null);
        assertThat(SubmissionNotesProjection.guide(raw, input, new Response(List.of(wrong), "READY")).notes()).isEmpty();
    }

    private static final String GROUPED_TITLE = "법인등기부 등본(법인해당시), 사업자등록증";
    private static final String GROUPED_EXPLANATION =
            "법인등기부 등본은 법인에 해당하는 경우 제출하며, 사업자등록증도 제출합니다.";

    private SubmissionDocument groupedDocument(String stage, String location) {
        String row = "| " + GROUPED_TITLE + " | " + stage + " | 각 1부 | 기관별 1부 |";
        return new SubmissionDocument(GROUPED_TITLE, "기관별 1부", "CONDITIONAL", stage, null,
                new RequirementSource("NOTICE_BODY", null, "구비서류", location, row),
                List.of("법인등기부 등본(법인해당시)", "사업자등록증"),
                List.of(GROUPED_TITLE + ".", "발급일 확인이 필요한 경우 담당자에게 문의합니다."), List.of("PDF"));
    }

    @Test void explainedGroupedTitleKeepsOneRowAndReplacesOnlyItsNameEcho() throws Exception {
        var document = groupedDocument("APPLICATION", "행 5");
        var raw = new SubmissionGuide(List.of(document), List.of());
        var input = SubmissionNotesProjection.input(document.source().excerpt(), List.of(), raw);
        var note = new Note("s0", GROUPED_TITLE, GROUPED_EXPLANATION, "DOCUMENT", "d0",
                document.source().excerpt(), null);
        var result = SubmissionNotesProjection.guide(raw, input, new Response(List.of(note), "READY"));
        assertThat(result.notesStatus()).isEqualTo("READY");
        assertThat(result.documents()).singleElement().satisfies(doc -> {
            assertThat(doc).usingRecursiveComparison().ignoringFields("conditions").isEqualTo(document);
            assertThat(doc.conditions()).containsExactly("발급일 확인이 필요한 경우 담당자에게 문의합니다.");
        });
        assertThat(result.notes()).singleElement().satisfies(n -> {
            assertThat(n.text()).isEqualTo(GROUPED_EXPLANATION);
            assertThat(n.appliesTo()).isEqualTo(GROUPED_TITLE);
            assertThat(n.source().excerpt()).isEqualTo(document.source().excerpt());
        });
        assertThat(raw.documents().getFirst().conditions()).contains(GROUPED_TITLE + ".");
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/submission-grouped-condition-projected.json"),
                new ObjectMapper().writeValueAsString(result));
    }

    @Test void missingInvalidOrUnrelatedNotesKeepEvidenceAndUseExplicitTitleCondition() {
        var document = groupedDocument("APPLICATION", "행 5");
        String other = "사업계획서는 해당 기관만 제출";
        var raw = new SubmissionGuide(List.of(document), List.of());
        var input = SubmissionNotesProjection.input(document.source().excerpt() + "\n" + other, List.of(), raw);
        var invalid = new Note("s0", GROUPED_TITLE, GROUPED_EXPLANATION + " 2부를 제출합니다.",
                "DOCUMENT", "d0", document.source().excerpt(), null);
        var partialQuote = new Note("s0", "법인등기부 등본(법인해당시)",
                "법인등기부 등본은 법인에 해당하는 경우 제출합니다.",
                "DOCUMENT", "d0", document.source().excerpt(), null);
        var unassigned = new Note("s0", other, "사업계획서는 해당 기관만 제출합니다.",
                "UNRESOLVED", null, other, "사업계획서");
        for (var response : List.of(new Response(List.of(), "READY"),
                new Response(List.of(), "PENDING"), Response.unavailable(),
                new Response(List.of(invalid), "READY"), new Response(List.of(partialQuote), "READY"),
                new Response(List.of(unassigned), "READY"))) {
            var projected = SubmissionNotesProjection.guide(raw, input, response).documents().getFirst();
            assertThat(projected).usingRecursiveComparison().ignoringFields("conditions").isEqualTo(document);
            assertThat(projected.conditions()).containsExactly(
                    "법인등기부 등본은 법인에 해당하는 경우 제출해야 합니다.",
                    "발급일 확인이 필요한 경우 담당자에게 문의합니다.");
        }
    }

    @Test void groupedTitleExplanationOnlyReplacesTheMatchingStage() {
        var first = groupedDocument("APPLICATION", "행 5");
        var second = groupedDocument("AGREEMENT", "행 9");
        var raw = new SubmissionGuide(List.of(first, second), List.of());
        var input = SubmissionNotesProjection.input(first.source().excerpt() + "\n" + second.source().excerpt(),
                List.of(), raw);
        var note = new Note("s0", GROUPED_TITLE, GROUPED_EXPLANATION, "DOCUMENT", "d1",
                second.source().excerpt(), null);
        var result = SubmissionNotesProjection.guide(raw, input, new Response(List.of(note), "READY"));
        assertThat(result.documents().getFirst()).usingRecursiveComparison().ignoringFields("conditions").isEqualTo(first);
        assertThat(result.documents().getFirst().conditions()).contains("법인등기부 등본은 법인에 해당하는 경우 제출해야 합니다.");
        assertThat(result.documents().get(1).conditions()).doesNotContain(GROUPED_TITLE + ".");
        assertThat(result.notes()).singleElement().satisfies(n ->
                assertThat(n.source().location()).isEqualTo("행 9"));
    }

    @Test void repeatedTitleUsesTheQuoteInsideItsOwnTableRow() {
        var document = groupedDocument("APPLICATION", "행 5");
        String body = GROUPED_TITLE + "\n" + "다른 본문 ".repeat(2000)
                + "\n" + document.source().excerpt();
        var raw = new SubmissionGuide(List.of(document), List.of());
        var input = SubmissionNotesProjection.input(body, List.of(), raw);
        var note = new Note("s0", GROUPED_TITLE, GROUPED_EXPLANATION, "DOCUMENT", "d0",
                document.source().excerpt(), null);
        var result = SubmissionNotesProjection.guide(raw, input, new Response(List.of(note), "READY"));
        assertThat(result.notesStatus()).isEqualTo("READY");
        assertThat(result.notes()).singleElement().satisfies(n -> {
            assertThat(n.text()).isEqualTo(GROUPED_EXPLANATION);
            assertThat(n.source().excerpt()).isEqualTo(document.source().excerpt());
        });
        assertThat(result.documents().getFirst().conditions()).doesNotContain(GROUPED_TITLE + ".");
    }

    @Test void repeatedScopeHeadingUsesTheOccurrenceNearTheCondition() {
        String heading = "공통 제출 유의사항";
        String quote = "제출서류는 마감일 이후 수정 불가";
        String body = heading + "\n" + "다른 본문 ".repeat(2000) + "\n" + heading + "\n" + quote;
        var raw = new SubmissionGuide(List.of(), List.of());
        var input = SubmissionNotesProjection.input(body, List.of(), raw);
        var note = new Note("s0", quote, "제출서류는 마감일 이후 수정이 불가합니다.",
                "COMMON", null, heading, null);
        var result = SubmissionNotesProjection.guide(raw, input, new Response(List.of(note), "READY"));
        assertThat(result.notesStatus()).isEqualTo("READY");
        assertThat(result.notes()).singleElement().satisfies(n ->
                assertThat(n.source().excerpt()).isEqualTo(heading + "\n" + quote));
    }

    @Test void distantUnrelatedSpansStillDoNotFormAnEvidenceBlock() {
        var document = groupedDocument("APPLICATION", "행 5");
        String quote = "법인인 경우 제출";
        String body = quote + "\n" + "다른 본문 ".repeat(2000) + "\n" + document.source().excerpt();
        var raw = new SubmissionGuide(List.of(document), List.of());
        var input = SubmissionNotesProjection.input(body, List.of(), raw);
        var note = new Note("s0", quote, "법인인 경우 제출합니다.", "DOCUMENT", "d0",
                document.source().excerpt(), null);
        var result = SubmissionNotesProjection.guide(raw, input, new Response(List.of(note), "READY"));
        assertThat(result.notes()).isEmpty();
        assertThat(result.notesStatus()).isEqualTo("PARTIAL");
        assertThat(result.documents().getFirst().conditions()).contains("법인등기부 등본은 법인에 해당하는 경우 제출해야 합니다.");
    }
}
