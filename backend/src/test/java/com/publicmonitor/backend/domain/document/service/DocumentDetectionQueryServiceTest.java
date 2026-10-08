package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

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

@ExtendWith(MockitoExtension.class)
class DocumentDetectionQueryServiceTest {

    @Mock
    private DocumentDetectionRepository detectionRepository;

    @Test
    void 감지_문서를_회사_대응_우선순위와_함께_조회한다() {
        LocalDateTime checkedAt = LocalDateTime.of(2026, 8, 21, 13, 31, 55);
        DocumentDetectionSummaryRow item = new DocumentDetectionSummaryRow(
                3L, 15L, 8L, 10L, "기후에너지환경부", "공지/공고", "기업 지원사업 모집 공고",
                DocumentChangeType.NEW_DOCUMENT, 2, DocumentImportance.HIGH, 60,
                OpportunityPriority.NORMAL, checkedAt
        );
        given(detectionRepository.findFilteredSummaries(3L, null, null, null, null, false, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        DocumentDetectionQueryService service = service();
        PageResponse<DocumentDetectionSummaryResponse> response = service.findAll(0, 20, null, null, 3L);

        assertThat(response.content()).singleElement().satisfies(summary -> {
            assertThat(summary.runId()).isEqualTo(3L);
            assertThat(summary.importance()).isEqualTo(DocumentImportance.HIGH);
            assertThat(summary.opportunityScore()).isEqualTo(60);
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
        verifyNoInteractions(detectionRepository);
    }

    @Test
    void 기회_점수순을_선택하면_점수순_조회_쿼리를_사용한다() {
        PageRequest pageRequest = PageRequest.of(0, 20);
        given(detectionRepository.findFilteredSummaries(null, null, null, null, null, true, pageRequest))
                .willReturn(new PageImpl<>(List.of(), pageRequest, 0));

        PageResponse<DocumentDetectionSummaryResponse> response = service().findAll(
                0, 20, null, null, null, DocumentDetectionSort.OPPORTUNITY_SCORE
        );

        assertThat(response.content()).isEmpty();
    }

    @Test
    void 우선순위와_검색_조건으로_요청한_페이지만_한번_조회한다() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = from.plusDays(1);
        PageRequest pageRequest = PageRequest.of(3, 2);
        var item = new DocumentDetectionSummaryRow(3L, 15L, 8L, 10L,
                "기관", "게시판", "할인 50%_AI!", DocumentChangeType.NEW_DOCUMENT,
                0, DocumentImportance.NORMAL, 80, OpportunityPriority.HIGH, from);
        given(detectionRepository.findFilteredSummaries(3L, from, to, "%50!%!_ai!!%",
                OpportunityPriority.HIGH, true, pageRequest))
                .willReturn(new PageImpl<>(List.of(item, item), pageRequest, 17));

        var response = service().findAll(3, 2, from, to, 3L,
                DocumentDetectionSort.OPPORTUNITY_SCORE, "  50%_AI!  ", OpportunityPriority.HIGH);

        assertThat(response.content()).hasSize(2)
                .allMatch(row -> row.opportunityPriority() == OpportunityPriority.HIGH);
        assertThat(response.totalElements()).isEqualTo(17);
        assertThat(response.totalPages()).isEqualTo(9);
        verify(detectionRepository).findFilteredSummaries(3L, from, to, "%50!%!_ai!!%",
                OpportunityPriority.HIGH, true, pageRequest);
        verifyNoMoreInteractions(detectionRepository);
    }

    @Test
    void 우선순위_필터가_없으면_미분석_문서도_그대로_반환한다() {
        PageRequest pageRequest = PageRequest.of(0, 20);
        var item = new DocumentDetectionSummaryRow(3L, 15L, 8L, 10L,
                "기관", "게시판", "미분석", DocumentChangeType.NEW_DOCUMENT,
                0, null, null, null, LocalDateTime.of(2026, 10, 1, 0, 0));
        given(detectionRepository.findFilteredSummaries(null, null, null, null, null, false, pageRequest))
                .willReturn(new PageImpl<>(List.of(item), pageRequest, 1));

        assertThat(service().findAll(0, 20, null, null).content()).singleElement().satisfies(row -> {
            assertThat(row.opportunityScore()).isNull();
            assertThat(row.opportunityPriority()).isNull();
        });
    }

    private DocumentDetectionQueryService service() {
        return new DocumentDetectionQueryService(detectionRepository);
    }
}
