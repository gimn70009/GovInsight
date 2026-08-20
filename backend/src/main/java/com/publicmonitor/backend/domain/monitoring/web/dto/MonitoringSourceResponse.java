package com.publicmonitor.backend.domain.monitoring.web.dto;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "모니터링 소스 정보")
public record MonitoringSourceResponse(
        @Schema(description = "모니터링 소스 식별자", example = "1") Long sourceId,
        @Schema(description = "공공기관 이름", example = "국토교통부") String organizationName,
        @Schema(description = "게시판 이름", example = "공지사항") String boardName,
        @Schema(description = "모니터링 소스 설명", nullable = true) String description,
        @Schema(description = "게시글 목록 페이지 URL") String listUrl,
        @Schema(description = "상세 게시글 URL 포함 패턴", nullable = true) String urlIncludePattern,
        @Schema(description = "실행 한 번에 수집할 상세 게시글 수", example = "3") int detailFetchCount,
        @Schema(description = "활성 여부", example = "true") boolean enabled,
        @Schema(description = "등록 시각", example = "2026-08-20T09:00:00") LocalDateTime createdAt,
        @Schema(description = "마지막 수정 시각", example = "2026-08-20T10:00:00") LocalDateTime updatedAt
) {
    public static MonitoringSourceResponse from(MonitoringSource source) {
        return new MonitoringSourceResponse(
                source.getId(),
                source.getOrganizationName(),
                source.getBoardName(),
                source.getDescription(),
                source.getListUrl(),
                source.getUrlIncludePattern(),
                source.getDetailFetchCount(),
                source.isEnabled(),
                source.getCreatedAt(),
                source.getUpdatedAt()
        );
    }
}
