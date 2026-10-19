package com.publicmonitor.backend.domain.settings.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

public record EvaluationSettingsData(@NotNull @Valid Company company, @NotNull @Valid Criteria criteria) {
    public record Company(
            @NotBlank @Size(max = 100) String name,
            @NotNull CompanyType type,
            @NotNull @Size(max = 200) String location,
            @PastOrPresent LocalDate establishedOn,
            @NotNull @Size(max = 2000) String description,
            @NotNull @Size(min = 1, max = 16) List<@NotBlank @Size(max = 80) String> industries,
            @NotNull @Size(min = 1, max = 46) List<@NotBlank @Size(max = 80) String> technologies,
            @NotNull @Size(max = 20) List<@NotNull @Valid Project> projects,
            @NotNull @Size(max = 20) List<@NotNull @Valid Certification> certifications
    ) {}
    public record Project(@NotBlank @Size(max = 150) String title,
                          @NotNull @Size(max = 1000) String description,
                          @NotNull ProjectStatus status) {}
    public record Certification(@NotBlank @Size(max = 150) String name,
                                LocalDate expiresOn, @NotNull CertificationStatus status) {}
    public record Criteria(
            @NotNull @Size(min = 1, max = 16) List<@NotBlank @Size(max = 80) String> industries,
            @NotNull @Size(min = 1, max = 46) List<@NotBlank @Size(max = 80) String> tasks,
            @NotNull @Size(min = 1, max = 6) List<@NotBlank @Size(max = 80) String> roles,
            @NotNull @Size(min = 1, max = 12) List<@NotBlank @Size(max = 80) String> businessTypes,
            @NotNull @Size(min = 1, max = 3) List<@NotBlank @Size(max = 80) String> goals,
            @JsonDeserialize(using = SettingsDaysDeserializer.class)
            @Min(1) @Max(365) Integer preparationDays, @NotNull ParticipationMode participationMode,
            @JsonDeserialize(using = SettingsDaysDeserializer.class)
            @NotNull @Min(1) @Max(365) Integer urgencyDays, @NotNull Preset preset
    ) {}
    public enum CompanyType { UNKNOWN, SME, MID_SIZED, LARGE, SOLE_PROPRIETOR, RESEARCH, NONPROFIT }
    public enum ProjectStatus { COMPLETED, IN_PROGRESS, PLANNED }
    public enum CertificationStatus { HELD, NEEDS_VERIFICATION, EXPIRED }
    public enum ParticipationMode { ANY, SOLO, CONSORTIUM }
    public enum Preset {
        FIT(50, 20, 20, 10), GROWTH(40, 35, 20, 5), FEASIBILITY(40, 15, 40, 5);
        private final List<Integer> weights;
        Preset(int fit, int value, int feasibility, int urgency) {
            this.weights = List.of(fit, value, feasibility, urgency);
        }
        public List<Integer> weights() { return weights; }
    }
}
