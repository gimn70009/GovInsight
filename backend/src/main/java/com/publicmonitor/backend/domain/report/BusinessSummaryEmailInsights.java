package com.publicmonitor.backend.domain.report;

import com.publicmonitor.backend.domain.analysis.entity.OpportunityDimensionType;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.Analysis;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.Preparation;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.StrategyGap;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Mail-specific concise copy, with conservative fallback to complete saved statements. */
public record BusinessSummaryEmailInsights(
        String reviewReason, List<String> priorityChecks, EmailPreparationSchedule schedule
) {
    public static BusinessSummaryEmailInsights unavailable() {
        return new BusinessSummaryEmailInsights("저장된 분석 정보가 없습니다. 공고 원문을 확인해 주세요.",
                List.of("참여 요건을 원문에서 확인해 주세요."), EmailPreparationSchedule.unknown());
    }

    public static BusinessSummaryEmailInsights from(DocumentDetectionDetailResponse detail,
            String deadline, LocalDateTime sentAt, Integer preparationDays) {
        return from(detail, deadline, sentAt, preparationDays, null);
    }

    public static BusinessSummaryEmailInsights from(DocumentDetectionDetailResponse detail,
            String deadline, LocalDateTime sentAt, Integer preparationDays, EmailReviewBrief.Result review) {
        var analysis = detail.analysis();
        var proposal = analysis == null ? null : analysis.proposal();
        var preparation = proposal == null ? null : proposal.preparation();
        var schedule = EmailPreparationSchedule.from(deadline, sentAt, preparationDays);
        // A past assessment cannot override an explicit submission deadline, even if parsing needs review.
        if ((deadline == null || clean(deadline).isEmpty())
                && dimension(analysis, OpportunityDimensionType.URGENCY).matches("^회사 행동 없음[.。]?(?:\\s.*)?$")) {
            schedule = new EmailPreparationSchedule("준비 일정 해당 없음", null);
        }
        if (proposal != null && Boolean.TRUE.equals(proposal.usesDemoProfile())) {
            return new BusinessSummaryEmailInsights("회사 정보를 등록한 뒤 종합 검토 내용을 확인해 주세요.",
                    List.of("회사 정보를 등록한 뒤 참여 요건을 확인해 주세요."), schedule);
        }
        var reasons = new LinkedHashSet<String>();
        add(reasons, dimension(analysis, OpportunityDimensionType.COMPANY_FIT));
        add(reasons, dimension(analysis, OpportunityDimensionType.BUSINESS_VALUE));
        if (reasons.isEmpty() && preparation != null && preparation.strategy() != null) {
            add(reasons, preparation.strategy().decisionReason());
        }
        String reason = reasons.isEmpty() ? unavailable().reviewReason() : String.join(" ", reasons);
        if (!concise(reason, 160)) reason = "참여 요건과 지원 조건을 확인한 뒤 회사의 기술·사업 방향과의 연관성을 검토해 주세요.";
        var checks = new LinkedHashSet<String>();
        if (preparation != null && preparation.strategy() != null && preparation.strategy().criticalGaps() != null) {
            for (var gap : preparation.strategy().criticalGaps()) {
                if (gap instanceof Map<?, ?> fields) addAction(checks, string(fields.get("gap")), string(fields.get("nextAction")));
                else if (gap instanceof StrategyGap item) addAction(checks, item.gap(), item.nextAction());
            }
        }
        addChecklist(checks, preparation);
        if (checks.isEmpty()) {
            String feasibility = dimension(analysis, OpportunityDimensionType.FEASIBILITY);
            if (concise(feasibility, 80)) add(checks, feasibility);
        }
        if (review != null && review.valid()) {
            if (review.reviewReason() != null) reason = review.reviewReason();
            if (!review.priorityChecks().isEmpty()) {
                checks.clear();
                checks.addAll(review.priorityChecks());
            }
        }
        if (checks.isEmpty()) checks.add(unavailable().priorityChecks().getFirst());
        return new BusinessSummaryEmailInsights(reason, checks.stream().limit(2).toList(), schedule);
    }

    private static void addChecklist(LinkedHashSet<String> checks, Preparation preparation) {
        if (preparation == null || preparation.eligibilityChecklist() == null) return;
        for (var item : preparation.eligibilityChecklist()) {
            if (checks.size() >= 2) break;
            if (item == null || "OPTIONAL".equals(item.requirementLevel())) continue;
            String detail = clean(item.detail());
            String target = clean(item.appliesTo());
            // Do not turn an incomplete condition or a particular role's obligation into a universal action.
            if (!detail.isEmpty() || !target.isEmpty()) {
                if (!target.isEmpty() && target.length() <= 25) {
                    String guidance = target + "에 적용되는 참여 요건과 예외 조건을 원문에서 확인해 주세요.";
                    if (EmailReviewText.complete(guidance, 80, 1)) add(checks, guidance);
                } else if (EmailReviewText.complete(detail, 80, 1)) {
                    add(checks, detail);
                }
            } else {
                addAction(checks, item.title(), item.nextAction());
            }
        }
    }

    private static void addAction(LinkedHashSet<String> checks, String context, String action) {
        if (checks.size() >= 2) return;
        String next = clean(action);
        String label = clean(context);
        boolean restricted = label.matches("(?s).*(?:제외|해당|경우|한정|조건부|에 한해|만 신청).*");
        if (EmailReviewText.complete(next, 80, 1) && (!restricted || next.contains(label))) add(checks, next);
    }

    private static boolean concise(String value, int limit) {
        return EmailReviewText.complete(value, limit, limit == 160 ? 2 : 1);
    }

    private static String dimension(Analysis analysis, OpportunityDimensionType type) {
        if (analysis == null || analysis.opportunity() == null || analysis.opportunity().dimensions() == null) return "";
        return analysis.opportunity().dimensions().stream().filter(item -> item != null && item.type() == type)
                .map(item -> clean(item.reason())).filter(value -> !value.isEmpty()).findFirst().orElse("");
    }

    private static String string(Object value) { return value instanceof String text ? text : ""; }

    private static void add(LinkedHashSet<String> values, String value) {
        String text = clean(value);
        if (!text.isEmpty()) values.add(text);
    }

    private static String clean(String value) {
        if (value == null) return "";
        String text = value.replaceAll("\\s+", " ").strip();
        return text.matches("(?:미확인|확인 필요|원문 확인 필요|정보 없음|해당 없음|없음)[.!]?") ? "" : text;
    }
}
