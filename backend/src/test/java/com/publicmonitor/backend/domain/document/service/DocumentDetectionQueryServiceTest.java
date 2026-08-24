package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryResponse;
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
    void 감지_문서를_최근_확인순으로_조회한다() {
        LocalDateTime checkedAt = LocalDateTime.of(2026, 8, 21, 13, 31, 55);
        DocumentDetectionSummaryResponse item = new DocumentDetectionSummaryResponse(
                15L, 8L, 10L, "기후에너지환경부", "공지/공고", "기업 지원사업 모집 공고",
                DocumentChangeType.NEW_DOCUMENT, 2, DocumentImportance.HIGH, checkedAt
        );
        given(detectionRepository.findSummaries(null, null, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        DocumentDetectionQueryService service = new DocumentDetectionQueryService(detectionRepository);
        PageResponse<DocumentDetectionSummaryResponse> response = service.findAll(0, 20, null, null);

        assertThat(response.content()).containsExactly(item);
        assertThat(response.content().getFirst().importance()).isEqualTo(DocumentImportance.HIGH);
        assertThat(response.content().getFirst().attachmentCount()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.last()).isTrue();
    }
    @Test
    void 시작_일시가_종료_일시보다_늦으면_예외를_반환한다() {
        DocumentDetectionQueryService service = new DocumentDetectionQueryService(detectionRepository);
        LocalDateTime from = LocalDateTime.of(2026, 8, 24, 12, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 24, 10, 0);

        assertThatThrownBy(() -> service.findAll(0, 20, from, to))
                .isInstanceOf(DocumentDetectionException.class);
    }
}
