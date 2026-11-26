package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.Preparation;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionDocument;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NumberedSubmissionListTest {
    private static final String ITEMS = "1)사업신청서(온라인), 2)공문(광역자치단체), 3)지방정부 추진전략(HWP), "
            + "4)사업계획서(HWP), 5)증빙자료(HWP), 6)평가계획서(HWP)";
    private static final List<String> NAMES = List.of("사업신청서(온라인)", "공문(광역자치단체)", "지방정부 추진전략(HWP)",
            "사업계획서(HWP)", "증빙자료(HWP)", "평가계획서(HWP)");

    @Test
    void 창업도시_저장_첨부의_여섯_서류를_분리하고_각_형식과_원문을_보존한다() throws Exception {
        String original;
        try (var stream = getClass().getResourceAsStream("/submission-documents/startup-city.txt")) {
            // Stored parser output uses LF; Windows Git checkouts may convert the fixture to CRLF.
            original = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        var attachment = DocumentAttachment.create(null, "창업도시_모집_공고.hwpx", "https://example.org/notice",
                "hwpx", "application/zip", 1L, "hash", original, AttachmentParseStatus.COMPLETED, null);
        var guide = SubmissionDocumentResolver.resolve(null, List.of(attachment), null);
        assertThat(guide.documents()).extracting(SubmissionDocument::title).containsExactlyElementsOf(NAMES);
        assertThat(guide.documents().subList(0, 2)).allSatisfy(item -> assertThat(item.formats()).isEmpty());
        assertThat(guide.documents().subList(2, 6)).allSatisfy(item -> assertThat(item.formats()).containsExactly("HWP"));
        assertThat(guide.documents()).allSatisfy(item -> {
            assertThat(item.documentNames()).containsExactly(item.title());
            assertThat(item.requirementLevel()).isEqualTo("MANDATORY");
            assertThat(item.stage()).isEqualTo("APPLICATION");
            assertThat(item.source().origin()).isEqualTo("ATTACHMENT");
            assertThat(original).contains(item.source().excerpt());
            assertThat(item.source().excerpt()).contains(ITEMS);
        });
    }

    @Test
    void 줄글_번호목록의_괄호안_쉼표와_개별_조건_수량을_보존한다() {
        String original = "신청서류\n1) 신청서(국문, 영문), 2) 확인서(해당 기업만 제출), 3) 재무제표 2부";
        var guide = SubmissionDocumentResolver.resolve(original, List.of(), null);
        assertThat(guide.documents()).extracting(SubmissionDocument::title)
                .containsExactly("신청서(국문, 영문)", "확인서(해당 기업만 제출)", "재무제표 2부");
        assertThat(guide.documents()).extracting(SubmissionDocument::requirementLevel)
                .containsExactly("MANDATORY", "CONDITIONAL", "MANDATORY");
        assertThat(guide.documents().getFirst().documentNames()).containsExactly("신청서(국문, 영문)");
        assertThat(guide.documents().getLast().detail()).contains("2부");
        assertThat(guide.documents()).allSatisfy(item -> assertThat(original).contains(item.source().excerpt()));
    }

    @Test
    void 구분한_원문_목록에_기존_저장된_합쳐진_목록을_다시_추가하지_않는다() {
        var mapper = new ObjectMapper();
        var preparation = mapper.readValue("""
                {"submissionDocuments":[{"title":%s,"requirementLevel":"MANDATORY","stage":"APPLICATION",
                "source":{"origin":"NOTICE_BODY","excerpt":%s}}]}
                """.formatted(mapper.writeValueAsString(ITEMS.substring(2)), mapper.writeValueAsString(ITEMS)), Preparation.class);
        var guide = SubmissionDocumentResolver.resolve("신청서류\n" + ITEMS, List.of(), preparation);
        assertThat(guide.documents()).extracting(SubmissionDocument::title).containsExactlyElementsOf(NAMES);
    }

    @Test
    void 마지막_항목이_형식으로_확인되는_서류여도_절_제목으로_오인하지_않는다() {
        for (String separator : List.of(", ", "; ", ",")) {
            String original = "신청서류\n1) 신청서" + separator + "2) 지역 발전전략(HWP)";
            var documents = SubmissionDocumentResolver.resolve(original, List.of(), null).documents();
            assertThat(documents).extracting(SubmissionDocument::title).containsExactly("신청서", "지역 발전전략(HWP)");
            assertThat(documents.getLast().formats()).containsExactly("HWP");
        }
    }

    @Test
    void 긴_번호목록과_서류별_조건을_공통_안내로_오인하지_않는다() {
        String longName = "공공기관 공동 연구개발 성과 활용을 위한 지역별 사업 추진 현황 및 참여기관별 협력 실적 증빙서류";
        String original = "신청서류\n1) " + longName + "(해당 기업만 제출), 2) " + longName + "(2025년), 3) " + longName + "(2026년), 4) 지역 발전전략(HWP)";
        var documents = SubmissionDocumentResolver.resolve(original, List.of(), null).documents();
        assertThat(documents).hasSize(4);
        assertThat(documents.getFirst().requirementLevel()).isEqualTo("CONDITIONAL");
        assertThat(documents.subList(1, 4)).allSatisfy(item -> assertThat(item.requirementLevel()).isEqualTo("MANDATORY"));
        assertThat(documents).allSatisfy(item -> assertThat(original).contains(item.source().excerpt()));
    }

    @Test
    void 번호_누락이나_절차_문장을_임의의_서류목록으로_확정하지_않는다() {
        for (String list : List.of("1) 신청서, 3) 확인서", "1) 신청서, 2) 접수 완료 후 확인", "1) 기본전략, 2) 실행전략")) {
            assertThat(NumberedSubmissionList.items(list)).isEmpty();
        }
        assertThat(SubmissionDocumentResolver.resolve("첨부 목록\n" + ITEMS, List.of(), null).documents()).isEmpty();
        assertThat(SubmissionDocumentResolver.resolve("작성 예시\n신청서류\n" + ITEMS, List.of(), null).documents()).isEmpty();
    }
}
