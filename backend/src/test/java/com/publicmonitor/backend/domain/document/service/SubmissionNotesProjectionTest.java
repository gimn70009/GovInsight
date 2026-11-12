package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static com.publicmonitor.backend.domain.document.client.SubmissionNotesContract.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SubmissionNotesProjectionTest {
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
}
