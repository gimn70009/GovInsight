package com.publicmonitor.backend.domain.settings.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

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
            Integer preparationDays, @NotNull ParticipationMode participationMode,
            @NotNull Integer urgencyDays, @NotNull Preset preset
    ) {
        @AssertTrue(message = "신청 준비 기간은 7·14·21·30일 또는 미정이어야 합니다.")
        public boolean isValidPreparationDays() {
            return preparationDays == null || List.of(7, 14, 21, 30).contains(preparationDays);
        }
        @AssertTrue(message = "집중 대응 기한은 3·7·14·30일이어야 합니다.")
        public boolean isValidUrgencyDays() {
            return urgencyDays == null || List.of(3, 7, 14, 30).contains(urgencyDays);
        }
    }
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
