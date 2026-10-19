package com.publicmonitor.backend.domain.settings.web.dto;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
public record EvaluationSettingsResponse(long revision, EvaluationSettingsData data,
        Map<String, List<SelectionGroup>> catalog, LocalDateTime updatedAt) {
    public record SelectionGroup(String name, List<String> options) {}
}
