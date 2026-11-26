package com.publicmonitor.backend.domain.document.service;

import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import java.util.*;
import java.util.regex.Pattern;

/** Checks linked notice exemptions, including when displaying an already-paid result. */
final class ParticipationApplicability {
    private static final Pattern EXEMPTION = Pattern.compile("산정(?:에서)?제외|적용(?:에서)?제외|면제|제한(?:이)?없|적용하지않");
    private static final Pattern ORGANIZATION = Pattern.compile("(?:(?:총괄|공동|주관|참여|수행|운영|공급|도입))+?(?:연구개발)?(?:기관|기업)");
    private static final Pattern PERSONNEL_SUBJECT = Pattern.compile("(?:운영인력|전담인력|상근인력|매니저|직원)(?:\\([^)]*\\))?(?:은|는|이|가)");
    private static final Map<String, String> DIMENSIONS = Map.of(
            "project_count", "동시수행|(?:수행|참여)(?:가능)?과제(?:수|는최대)",
            "participation_rate", "참여율|인건비계상률",
            "debt_ratio", "부채비율", "liquidity_ratio", "유동비율",
            "company_age", "업력|사업개시|설립후",
            "membership_count", "가입(?:수|개수)|협의체.*(?:개이내|개까지)",
            "contribution", "민간부담|자기부담|기업부담|자부담");

    static String criterion(Statement item) {
        if ("REVENUE".equals(item.criterion()) && item.text().matches(".*(?:민간부담금|자기부담금|기업부담금|자부담).*"))
            if (!item.text().matches(".*(?:매출|수출|수입금액).*")) return "CREDIT";
        if ("BUSINESS_FORM".equals(item.criterion()) && PERSONNEL_SUBJECT.matcher(compact(item.text())).find()) return "EXPERIENCE";
        return item.criterion();
    }

    static boolean conflict(Statement item, List<Source> sources) {
        String prose = item.text() + " " + item.details().stream().filter(d -> !d.reviewRequired())
                .map(Detail::text).collect(java.util.stream.Collectors.joining(" "));
        var claimed = dimensions(prose);
        for (var source : sources) {
            if (!item.sourceIds().contains(source.id()) || !source.groupLabel().contains(ParticipationChecklistSources.MARKER)) continue;
            String context = recruitment(source);
            if (context.isEmpty()) continue;
            for (var exception : sources) {
                if (!exception.groupLabel().contains(ParticipationChecklistSources.EXCEPTION)
                        || !context.equals(recruitment(exception)) || !EXEMPTION.matcher(compact(exception.excerpt())).find()) continue;
                var roles = organizations(prose);
                var exemptRoles = organizations(exception.excerpt());
                if (!roles.isEmpty() && !exemptRoles.isEmpty() && Collections.disjoint(roles, exemptRoles)) continue;
                var relevant = dimensions(exception.excerpt());
                relevant.retainAll(claimed);
                if (relevant.isEmpty()) continue;
                var quotes = new ArrayList<>(item.supportingQuotes());
                item.details().stream().filter(d -> !d.reviewRequired()).forEach(d -> quotes.addAll(d.supportingQuotes()));
                boolean cited = quotes.stream().anyMatch(q -> compact(exception.excerpt()).contains(compact(q))
                        && !Collections.disjoint(relevant, dimensions(q)) && EXEMPTION.matcher(compact(q)).find());
                boolean preserved = EXEMPTION.matcher(compact(item.text())).find()
                        && !Collections.disjoint(relevant, dimensions(item.text()))
                        && organizations(item.text()).containsAll(exemptRoles)
                        && !compact(item.text()).matches(".*(?:면제|제외)(?:되지않|하지않|하지아니).*");
                if (!cited || !preserved) return true;
            }
        }
        return false;
    }

    private static Set<String> dimensions(String value) {
        var result = new HashSet<String>();
        DIMENSIONS.forEach((key, expression) -> { if (Pattern.compile(expression).matcher(compact(value)).find()) result.add(key); });
        return result;
    }

    private static Set<String> organizations(String text) {
        return ORGANIZATION.matcher(compact(text)).results().map(m -> m.group().replace("연구개발", ""))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String recruitment(Source source) {
        var match = Pattern.compile("\\[공고 대상]([^\\n]+)").matcher(source.groupLabel());
        return match.find() ? compact(match.group(1)) : "";
    }

    private static String compact(String value) { return value.replaceAll("\\s+|[「」｢｣『』\"“”]", "").replace("ㆍ", "·"); }
}
