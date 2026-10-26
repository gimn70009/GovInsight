package com.publicmonitor.backend.domain.report;

import com.publicmonitor.backend.domain.document.service.BusinessType;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse;
import java.util.List;

/** The six rows use the same fields and fallback order as the web BusinessSummary component. */
public record BusinessSummaryEmailView(
        String type, List<Organization> organizations, String title, String sourceOrganization, String supportScale,
        String purpose, String deadline, Integer score, String change, String originalUrl
) {
    public record Organization(String name, String role) {}

    public static BusinessSummaryEmailView from(DocumentDetectionDetailResponse detail) {
        var analysis = detail.analysis();
        var summary = analysis == null ? null : analysis.businessSummary();
        var preparation = analysis == null || analysis.proposal() == null ? null : analysis.proposal().preparation();
        String purpose = first(summary == null ? null : summary.purpose(), analysis == null ? null : analysis.summary());
        String deadline = first(analysis == null ? null : analysis.applicationDeadline(),
                preparation == null ? null : preparation.applicationDeadline());
        var organizations = detail.responsibleOrganizations() == null ? List.<Organization>of()
                : detail.responsibleOrganizations().stream()
                    .filter(organization -> value(organization.name()) != null
                            && value(organization.role()) != null && value(organization.excerpt()) != null)
                    .map(organization -> new Organization(
                        organization.name(), organization.role())).toList();
        String change = detail.changeType() == null ? "상태 미확인" : switch (detail.changeType()) {
            case NEW_DOCUMENT -> "신규";
            case UPDATED_DOCUMENT -> "변경";
            case UNCHANGED_DOCUMENT -> "변경 없음";
        };
        return new BusinessSummaryEmailView(typeLabel(detail), organizations, detail.title(),
                value(detail.organizationName()) == null ? "공공기관 확인 필요" : value(detail.organizationName()),
                BusinessSummaryEmailText.supportScale(display(summary == null ? null : summary.supportScale())),
                display(purpose), display(deadline),
                analysis == null || analysis.opportunity() == null ? null : analysis.opportunity().totalScore(),
                change, safeUrl(detail.originalUrl()));
    }

    private static String typeLabel(DocumentDetectionDetailResponse detail) {
        var type = detail.businessType();
        if (type != null && "CLASSIFIED".equals(type.status()) && type.code() != null) {
            try { return BusinessType.valueOf(type.code()).label(); }
            catch (IllegalArgumentException ignored) { /* Unknown codes stay unconfirmed like the web. */ }
        }
        if (type != null && "NOT_APPLICABLE".equals(type.status())) {
            return type.noticeNature() == null ? "해당 없음" : type.noticeNature().label();
        }
        return detail.analysisPending() ? "분석 중" : "확인 필요";
    }

    static String safeUrl(String url) {
        return url != null && ReportBodyFormatter.safeUrl(url) ? url : null;
    }

    private static String first(String preferred, String fallback) {
        return value(preferred) != null ? value(preferred) : value(fallback);
    }

    private static String value(String text) {
        if (text == null || text.isBlank()) return null;
        String value = text.strip();
        return value.matches("(?:미확인|확인 필요|원문 확인 필요|정보 없음|원문에서 확인하지 못했습니다)[.!]?")
                ? null : value;
    }

    private static String display(String text) {
        String value = value(text);
        return value == null ? "확인 필요" : value.replaceAll("[ \\t]*[;；]+[ \\t]*", ", ");
    }
}
