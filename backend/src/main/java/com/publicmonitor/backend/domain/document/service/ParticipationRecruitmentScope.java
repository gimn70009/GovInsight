package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import java.util.*;
import java.util.regex.Pattern;

/** Recognize an explicitly separate recruitment section, without interpreting generated wording. */
final class ParticipationRecruitmentScope {
    private static final Pattern ROLE = Pattern.compile(
            "([가-힣A-Za-z]*(?:기관|기업|단체|조합|사업자|대학))(?:\\([^()]*\\))?\\h*모집");
    private static final Pattern LATER = Pattern.compile("별도|추후|예정");
    private static final Pattern AMBIGUOUS = Pattern.compile(
            "통합\\h*공고|통합\\h*모집|동시|함께|취소|미정|않|아니|없|"
            + "(?:기관|기업|단체|조합|사업자|대학)\\h*(?:및|와|과|[·ㆍ,/])");

    private ParticipationRecruitmentScope() {}

    static boolean referenceOnly(ParticipationRewriteProjection.Input input, RequirementSource source) {
        if (input.scopes() == null) return false;
        String context = input.scopes().recruitmentContext(source);
        String[] parts = context.split(" / ", -1);
        if (parts.length != 2 || AMBIGUOUS.matcher(context).find()
                || LATER.matcher(parts[0]).find() || !LATER.matcher(parts[1]).find()) return false;
        String current = role(parts[0]), separate = role(parts[1]);
        if (current == null || separate == null || current.equals(separate)) return false;
        String heading = Objects.toString(source.sectionTitle(), "");
        int original = heading.indexOf("[원문 구역] ");
        if (original >= 0) heading = heading.substring(original + "[원문 구역] ".length());
        heading = compact(heading).replaceFirst("^[□■]*(?:[가-하][.)]|[0-9]+[.)]?)?", "");
        // Some parsers attach only the next section's title after a complete table.
        // Ignore that empty heading for scope matching, but retain any following qualification prose.
        String excerpt = compact(source.excerpt()).replaceFirst(
                "\\[/표](?:[0-9]+)?신청자격(?:및선정기준)?$", "[/표]");
        // Mixed/current sections and common exclusions stay available, even if they mention later roles.
        if (excerpt.contains(current) || heading.contains(current)
                || heading.matches(".*(?:제외|공통|유의).*")
                || excerpt.matches(".*(?:\\[표]구분자격|(?:총괄|공통)신청자격).*")) return false;
        if (heading.matches(Pattern.quote(separate) + "(?:지원|신청|참여|모집)?(?:대상|자격|요건|유형)")) return true;
        // Explicit overall/subproject recruitment + the subproject support table is a structural pair.
        // Without all three original markers, do not infer that a table belongs to another call.
        return current.matches("총괄(?:주관)?연구개발기관")
                && separate.matches("세부(?:주관)?연구개발기관")
                && heading.matches("세부과제(?:지원)?유형")
                && excerpt.contains("[표]") && excerpt.contains("지원대상")
                && !excerpt.contains("신청자격");
    }

    static boolean referenceOnly(ParticipationRewriteProjection.Input input, List<String> sourceIds) {
        return sourceIds != null && !sourceIds.isEmpty() && sourceIds.stream().allMatch(id -> {
            var sources = input.evidence().get(id);
            return sources != null && !sources.isEmpty() && sources.stream().allMatch(source -> referenceOnly(input, source));
        });
    }

    private static String role(String text) {
        var matcher = ROLE.matcher(text);
        if (!matcher.find()) return null;
        String value = matcher.group(1);
        return matcher.find() ? null : value;
    }

    private static String compact(String text) { return Objects.toString(text, "").replaceAll("\\s+", ""); }
}
