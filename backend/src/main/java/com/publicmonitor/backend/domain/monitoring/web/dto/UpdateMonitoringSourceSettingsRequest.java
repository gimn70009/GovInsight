package com.publicmonitor.backend.domain.monitoring.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Objects;

@Schema(description = "변경한 기관의 수집 건수와 활성 상태를 한 번에 저장하는 요청")
public record UpdateMonitoringSourceSettingsRequest(
        @NotEmpty(message = "저장할 소스 설정이 필요합니다.")
        List<@NotNull @Valid SourceSetting> sources
) {
    @Schema(hidden = true)
    @AssertTrue(message = "같은 소스 설정을 중복해서 전달할 수 없습니다.")
    public boolean isSourceIdsUnique() {
        if (sources == null) return true;
        var ids = sources.stream().filter(Objects::nonNull).map(SourceSetting::sourceId).toList();
        return ids.stream().distinct().count() == ids.size();
    }

    public record SourceSetting(
            @NotNull @Positive Long sourceId,
            @NotNull(message = "수집 건수는 필수입니다.")
            @Min(value = 1, message = "수집 건수는 1 이상이어야 합니다.") Integer detailFetchCount,
            @NotNull(message = "활성 여부는 필수입니다.") Boolean enabled
    ) {
    }
}
