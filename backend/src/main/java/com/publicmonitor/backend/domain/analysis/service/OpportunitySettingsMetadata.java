package com.publicmonitor.backend.domain.analysis.service;
import com.publicmonitor.backend.domain.settings.web.dto.EvaluationSettingsData.Preset;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
public final class OpportunitySettingsMetadata {
    private OpportunitySettingsMetadata() {}
    public static long revision(String assessment, ObjectMapper mapper) {
        if (assessment == null || assessment.isBlank()) return 0;
        try { return mapper.readTree(assessment).path("settingsRevision").asLong(0); }
        catch (RuntimeException e) { return 0; }
    }
    public static Preset preset(JsonNode node) {
        try { return Preset.valueOf(node.path("preset").asString("FIT")); }
        catch (IllegalArgumentException e) { return Preset.FIT; }
    }
}
