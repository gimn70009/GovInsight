package com.publicmonitor.backend.domain.monitoring.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateMonitoringSourceRequest(
        @NotBlank(message = "기관명은 필수입니다.")
        @Size(max = 100, message = "기관명은 100자 이하여야 합니다.")
        String organizationName,

        @NotBlank(message = "게시판명은 필수입니다.")
        @Size(max = 100, message = "게시판명은 100자 이하여야 합니다.")
        String boardName,

        @Size(max = 1000, message = "설명은 1000자 이하여야 합니다.")
        String description,

        @NotBlank(message = "목록 URL은 필수입니다.")
        @Size(max = 1000, message = "목록 URL은 1000자 이하여야 합니다.")
        @Pattern(regexp = "^https?://.+", message = "목록 URL은 http 또는 https 주소여야 합니다.")
        String listUrl,

        @Size(max = 500, message = "URL 포함 패턴은 500자 이하여야 합니다.")
        String urlIncludePattern,

        @Min(value = 1, message = "상세 수집 수는 1 이상이어야 합니다.")
        Integer detailFetchCount,

        Boolean enabled
) {
}
