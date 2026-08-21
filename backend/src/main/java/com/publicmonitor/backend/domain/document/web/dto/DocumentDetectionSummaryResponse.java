package com.publicmonitor.backend.domain.document.web.dto;

import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "감지 문서 목록 항목")
public record DocumentDetectionSummaryResponse(
        @Schema(description = "감지 식별자", example = "15") Long detectionId,
        @Schema(description = "문서 식별자", example = "8") Long documentId,
        @Schema(description = "문서 버전 식별자", example = "10") Long versionId,
        @Schema(description = "기관명", example = "기후에너지환경부") String organizationName,
        @Schema(description = "게시판명", example = "공지/공고") String boardName,
        @Schema(description = "문서 제목") String title,
        @Schema(description = "변경 유형", example = "NEW_DOCUMENT") DocumentChangeType changeType,
        @Schema(description = "첨부파일 수", example = "2") int attachmentCount,
        @Schema(description = "중요도", example = "HIGH", nullable = true) DocumentImportance importance,
        @Schema(description = "마지막 확인 시각") LocalDateTime lastCheckedAt
) {
}
