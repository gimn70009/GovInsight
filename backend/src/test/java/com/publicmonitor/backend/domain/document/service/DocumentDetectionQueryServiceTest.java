package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryResponse;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryRow;
import com.publicmonitor.backend.global.response.PageResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DocumentDetectionQueryServiceTest {

    @Mock
    private DocumentDetectionRepository detectionRepository;

    @Test
    void 감지_문서를_회사_대응_우선순위와_함께_조회한다() {
        LocalDateTime checkedAt = LocalDateTime.of(2026, 8, 21, 13, 31, 55);
        DocumentDetectionSummaryRow item = new DocumentDetectionSummaryRow(
                3L, 15L, 8L, 10L, "기후에너지환경부", "공지/공고", "기업 지원사업 모집 공고",
                DocumentChangeType.NEW_DOCUMENT, 2, DocumentImportance.HIGH, 59,
                opportunityJson(), checkedAt
        );
        given(detectionRepository.findSummaries(3L, null, null, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        DocumentDetectionQueryService service = service();
        PageResponse<DocumentDetectionSummaryResponse> response = service.findAll(0, 20, null, null, 3L);

        assertThat(response.content()).singleElement().satisfies(summary -> {
            assertThat(summary.runId()).isEqualTo(3L);
            assertThat(summary.importance()).isEqualTo(DocumentImportance.HIGH);
            assertThat(summary.opportunityScore()).isEqualTo(59);
            assertThat(summary.opportunityPriority()).isEqualTo(OpportunityPriority.NORMAL);
            assertThat(summary.attachmentCount()).isEqualTo(2);
        });
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.last()).isTrue();
    }

    @Test
    void 시작_일시가_종료_일시보다_늦으면_예외를_반환한다() {
        LocalDateTime from = LocalDateTime.of(2026, 8, 24, 12, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 24, 10, 0);

        assertThatThrownBy(() -> service().findAll(0, 20, from, to))
                .isInstanceOf(DocumentDetectionException.class);
    }

    @Test
    void 기회_점수순을_선택하면_점수순_조회_쿼리를_사용한다() {
        PageRequest pageRequest = PageRequest.of(0, 20);
        given(detectionRepository.findSummariesOrderByOpportunityScore(null, null, null, pageRequest))
                .willReturn(new PageImpl<>(List.of(), pageRequest, 0));

        PageResponse<DocumentDetectionSummaryResponse> response = service().findAll(
                0, 20, null, null, null, DocumentDetectionSort.OPPORTUNITY_SCORE
        );

        assertThat(response.content()).isEmpty();
    }

    private DocumentDetectionQueryService service() {
        return new DocumentDetectionQueryService(detectionRepository, new ObjectMapper());
    }

    private String opportunityJson() {
        return """
                {"dimensions":[
                  {"type":"COMPANY_FIT","score":60,"reason":"관련 기술이 있습니다."},
                  {"type":"BUSINESS_VALUE","score":60,"reason":"사업 가치가 있습니다."},
                  {"type":"FEASIBILITY","score":45,"reason":"자격 확인이 필요합니다."},
                  {"type":"URGENCY","score":85,"reason":"기한이 임박했습니다."},
                  {"type":"EVIDENCE_CONFIDENCE","score":45,"reason":"근거가 제한적입니다."}
                ]}
                """;
    }
}
