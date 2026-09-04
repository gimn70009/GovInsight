package com.publicmonitor.backend.domain.monitoring.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "모니터링 소스 등록 요청")
public record CreateMonitoringSourceRequest(
        @Schema(description = "공공기관 이름", example = "국토교통부", maxLength = 100, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "기관명은 필수입니다.")
        @Size(max = 100, message = "기관명은 100자 이하여야 합니다.")
        String organizationName,

        @Schema(description = "수집 대상 게시판 이름", example = "공지사항", maxLength = 100, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "게시판명은 필수입니다.")
        @Size(max = 100, message = "게시판명은 100자 이하여야 합니다.")
        String boardName,

        @Schema(description = "모니터링 소스 설명", example = "국토·교통 분야 주요 공지사항", maxLength = 1000, nullable = true)
        @Size(max = 1000, message = "설명은 1000자 이하여야 합니다.")
        String description,

        @Schema(description = "게시글 목록 페이지 URL", example = "https://www.molit.go.kr/USR/BORD0201/m_69/LST.jsp?id=N01_B", maxLength = 1000, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "목록 URL은 필수입니다.")
        @Size(max = 1000, message = "목록 URL은 1000자 이하여야 합니다.")
        @Pattern(regexp = "^https?://.+", message = "목록 URL은 http 또는 https 주소여야 합니다.")
        String listUrl,

        @Schema(description = "목록에서 상세 게시글 링크만 선별하는 URL 포함 패턴", example = "/USR/BORD0201/m_69/DTL.jsp", maxLength = 500, nullable = true)
        @Size(max = 500, message = "URL 포함 패턴은 500자 이하여야 합니다.")
        String urlIncludePattern,

        @Schema(description = "실행 한 번에 수집할 상세 게시글 수. 생략하면 서버 기본값 3을 사용합니다.", example = "3", minimum = "1", nullable = true)
        @Min(value = 1, message = "상세 수집 수는 1 이상이어야 합니다.")
        Integer detailFetchCount,

        @Schema(description = "소스 활성 여부. 생략하면 활성 상태로 등록됩니다.", example = "true", nullable = true)
        Boolean enabled
) {
}
