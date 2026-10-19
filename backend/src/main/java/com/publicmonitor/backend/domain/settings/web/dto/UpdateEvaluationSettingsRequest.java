package com.publicmonitor.backend.domain.settings.web.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
public record UpdateEvaluationSettingsRequest(@NotNull @Min(0) Long revision,
                                              @NotNull @Valid EvaluationSettingsData data) {}
