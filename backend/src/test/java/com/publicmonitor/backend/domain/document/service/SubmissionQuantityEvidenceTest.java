package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static com.publicmonitor.backend.domain.document.client.SubmissionNotesContract.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SubmissionQuantityEvidenceTest {
    @Test void sharesStructuralComparisonCasesWithPython() throws Exception {
        var cases = JsonMapper.builder().build().readTree(Files.readString(Path.of("../test-fixtures/submission-quantity-cases.json")));
        for (var item : cases) {
            String row = item.path("row").asText(), title = item.path("title").asText();
            assertThat(SubmissionQuantityEvidence.comparisonQuote(row, title, row)).isEqualTo(item.path("expected").asText());
            assertThat(SubmissionQuantityEvidence.comparisonQuote("다른 인용 5", title, row)).isEqualTo("다른 인용 5");
        }
    }

    @Test void displayAcceptsReadableQuantityButRejectsAlteredAmountsAndUnits() {
        String title = "확인서(개인해당시)", row = "5\t" + title + "\t각 1부\t기관별 1부";
        var doc = new SubmissionDocument(title, null, "CONDITIONAL", "APPLICATION", null,
                new RequirementSource("NOTICE_BODY", null, "구비서류", null, row));
        var raw = new SubmissionGuide(List.of(doc), List.of());
        var input = SubmissionNotesProjection.input(row, List.of(), raw);
        String text = "확인서는 개인에 해당하는 경우 기관별로 각 1부 제출해야 합니다.";
        var note = new Note("s0", row, text, "DOCUMENT", "d0", row, null);
        var result = SubmissionNotesProjection.guide(raw, input, new Response(List.of(note), "READY"));
        assertThat(result.notes()).singleElement().satisfies(n -> {
            assertThat(n.text()).isEqualTo(text);
            assertThat(n.source().excerpt()).isEqualTo(row);
        });
        for (String wrong : List.of(text.replace("1부", "2부"), text.replace("1부", "1매"),
                text.replace("각 1부 ", ""), text + " 5부를 더 제출합니다.")) {
            var invalid = new Note("s0", row, wrong, "DOCUMENT", "d0", row, null);
            assertThat(SubmissionNotesProjection.guide(raw, input, new Response(List.of(invalid), "READY")).notes()).isEmpty();
        }
    }
}
