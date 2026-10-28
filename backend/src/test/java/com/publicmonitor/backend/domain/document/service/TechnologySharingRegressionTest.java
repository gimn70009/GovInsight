package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;

class TechnologySharingRegressionTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private tools.jackson.databind.JsonNode fixture() throws Exception {
        return mapper.readTree(Files.readString(Path.of("../test-fixtures/document-tables/technology-sharing.json")));
    }

    @Test void namesWithCellNotesAndRepeatedHeadersYieldThreeRequiredRows() throws Exception {
        String text = fixture().get("submission").asString();
        var guide = SubmissionDocumentResolver.resolve(text, List.of(), null);
        assertThat(guide.documents()).extracting(SubmissionDocument::title).containsExactly(
                "특허활용계획서(한글파일)", "개인정보 조회 및 제3자 제공 동의서", "사업자등록증");
        assertThat(guide.documents()).allSatisfy(item -> {
            assertThat(item.requirementLevel()).isEqualTo("MANDATORY");
            assertThat(item.stage()).isEqualTo("APPLICATION");
            assertThat(text).contains(item.source().excerpt());
        });
        assertThat(guide.documents().getFirst().conditions()).anyMatch(s -> s.contains("ZIP"));
        assertThat(guide.documents().get(1).conditions()).anyMatch(s -> s.contains("PDF"));
        assertThat(guide.documents().get(2).conditions()).anyMatch(s -> s.contains("사본"));
        assertThat(guide.notes()).anyMatch(note -> note.text().contains("접수 마감일 기준"));
    }

    @Test void ambiguousSecondDocumentAndExemptionAreReportedWithoutPromotingAnObligation() throws Exception {
        for (String replacement : List.of("• 별도 협약서", "※ 일부 기업은 제출 면제")) {
            String text = fixture().get("submission").asString().replace("* 신청 특허별로 작성하되 전체 ZIP 형태로 업로드", replacement);
            var guide = SubmissionDocumentResolver.resolve(text, List.of(), null);
            assertThat(guide.documents()).noneMatch(item -> item.title().contains("특허활용계획서"));
            assertThat(guide.notes()).anyMatch(note -> note.text().contains("자동 검증하지 못했습니다"));
        }
    }

    @Test void repeatedPdfFormDescriptionsCannotDuplicateOfficialSubmissionRows() throws Exception {
        var pdf = DocumentAttachment.create(null, "사업안내.pdf", "https://example.test/notice.pdf", "pdf",
                "application/pdf", null, null, "신청서류 목록\n양식 2. 개인정보 조회 및 제 3자 제공 동의서\n서류 3. 사업자등록증(사본)",
                AttachmentParseStatus.COMPLETED, null);
        var guide = SubmissionDocumentResolver.resolve(fixture().get("submission").asString(), List.of(pdf), null);
        assertThat(guide.documents()).hasSize(3);
        assertThat(guide.documents()).allMatch(item -> item.source().origin().equals("NOTICE_BODY"));
    }
}
