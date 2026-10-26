package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import java.util.regex.Pattern;

/** A source-backed projection. Topic matching does not decide whether a company qualifies. */
final class ParticipationCategoryProjector {
    private static final Pattern CHOICE = Pattern.compile("또는|혹은|중\\s*(?:어느\\s*)?하나|택\\s*1|중\\s*\\d+\\s*개");
    private ParticipationCategoryProjector() {}

    static List<ParticipationCategory> project(List<ParticipationRule> rules, boolean incomplete) {
        boolean multipleTypes = ParticipationDisplayText.multipleTypes(rules);
        var rows = new LinkedHashMap<String, List<ParticipationCriterionEntry>>();
        for (var criterion : ParticipationCriterion.values()) rows.put(criterion.name(), new ArrayList<>());
        var extras = new LinkedHashMap<String, List<ParticipationCriterionEntry>>();
        var uncertain = EnumSet.noneOf(ParticipationCriterion.class);
        for (var rule : ParticipationDisplayText.prepare(rules)) {
            if (rule.text().matches("(?s)^(?:\\(예시\\)|예시[:：]).*")
                    || ParticipationClauseText.administrative(rule.text())
                    || ParticipationClauseText.caption(rule.text()) && rule.details().isEmpty()) continue;
            if (rule.section().equals("REVIEW")) {
                String evidence = String.join(" ", rule.sources().stream().map(RequirementSource::excerpt)
                        .filter(Objects::nonNull).toList());
                boolean preferenceReview = rule.reviewReason() != ParticipationReviewReason.MIXED_REQUIREMENT
                        && (rule.label() + " " + rule.text()).matches("(?s).*(?:우대|가점|감점).*");
                boolean fundingReview = (rule.label() + " " + rule.text()).matches("(?s).*(?:자부담|사업비|부담금).*");
                for (var criterion : ParticipationCriterion.values())
                    if (criterion.group.equals("PREFERENCE") == preferenceReview
                            && (!fundingReview || criterion == ParticipationCriterion.MATCHING)
                            && criterion.topic.matcher(evidence).find()) uncertain.add(criterion);
                String group = preferenceReview ? "PREFERENCE" : fundingReview ? "EXECUTION" : "COMPANY";
                extras.computeIfAbsent(group, key -> new ArrayList<>()).add(entry(rule, rule.text(), null, null, List.of()));
                continue;
            }
            if (rule.section().equals("TARGETED") && !rule.outcomes().isEmpty()) {
                String audience = Objects.toString(rule.audience(), "");
                var targets = topics(audience, false, rule);
                if (rule.label().equals("과거 지원 이력") || ParticipationCriterion.DUPLICATE.topic.matcher(audience).find())
                    rows.get("DUPLICATE").add(entry(rule, rule.text(), null, null, List.of()));
                else if (!targets.isEmpty())
                    targets.stream().map(Hit::criterion).distinct().forEach(criterion ->
                            rows.get(criterion.name()).add(entry(rule, rule.text(), null, null, List.of())));
                else extras.computeIfAbsent("RESTRICTIONS", key -> new ArrayList<>()).add(entry(rule, rule.text(), null, null, List.of()));
                continue;
            }
            if (rule.label().equals("협력 구조")) {
                rows.get("CONSORTIUM").add(entry(rule, rule.text(), null, null, List.of()));
                continue;
            }
            if (rule.section().equals("FUNDING") || rule.label().equals("사업 수행 의지·계획")) {
                String code = rule.section().equals("FUNDING") ? "MATCHING" : "PLAN";
                var candidates = new ArrayList<String>();
                candidates.add(rule.text());
                rule.details().stream().filter(value -> value.startsWith("적용 기준\n"))
                        .map(value -> value.substring("적용 기준\n".length())).forEach(candidates::add);
                for (String candidate : candidates) for (String clause : ParticipationExecutionClauses.split(candidate)) {
                    String compact = clause.replaceAll("\\s+", " ").strip();
                    boolean tableSummary = compact.matches("(?:유형별 자부담|사업계획서·발표평가) 기준.*");
                    if (tableSummary || (code.equals("MATCHING") ? ParticipationExecutionClauses.funding(compact)
                            : ParticipationExecutionClauses.plan(compact))) {
                        String display = tableSummary ? (code.equals("MATCHING") ? "유형별 자부담" : "사업계획서·발표평가") + " 기준은 원문 근거에서 확인할 수 있습니다."
                                : ParticipationExecutionClauses.display(compact);
                        rows.get(code).add(entry(rule, display, null, null, List.of()));
                    }
                }
                continue;
            }
            boolean preference = rule.section().equals("PREFERENCE") || rule.section().equals("DEDUCTION");
            boolean scoreTable = preference && rule.details().stream().anyMatch(value -> value.contains("점 가점") || value.contains("점 감점"));
            var texts = new ArrayList<String>();
            if (!scoreTable) texts.add(rule.text());
            boolean groupedExclusion = rule.section().equals("EXCLUSION") && rule.text().matches("(?s).*등.*제외 조건.*");
            if (groupedExclusion) texts.clear();
            if (!rule.section().equals("EXCLUSION") || groupedExclusion)
                texts.addAll(ParticipationClauseText.joinWrappedParentheses(rule.details()).stream().filter(text -> !text.startsWith("법적 자격 기준:")
                        && !text.startsWith("적용 대상\n") && !text.startsWith("공통 적용 기준\n")
                        && !text.matches("(?s)^(?:상기|전년도 결산|접수마감일 기준|이 경우).*(?:국세청|재무제표|계산|판단|서류 일체).*")
                        && !text.startsWith("참고자료")).toList());
            boolean mapped = false;
            for (String text : ParticipationClauseText.joinWrappedParentheses(texts)) {
                if (ParticipationClauseText.caption(text) || ParticipationClauseText.administrative(text)) continue;
                boolean composition = !preference && text.matches("(?s).*(?:공동연구개발기관|참여기관|컨소시[엄움]).*(?:구성해야|참여해야|포함.*필수|이상.*(?:구성|참여)).*");
                if (composition) {
                    rows.get("CONSORTIUM").add(entry(rule, text, null, null, List.of()));
                    mapped = true;
                }
                var hits = topics(text, preference, rule);
                if (hits.isEmpty()) {
                    if (scoreTable && !text.startsWith("공통 적용 기준")) {
                        extras.computeIfAbsent("PREFERENCE", key -> new ArrayList<>()).add(entry(rule, text, null, null, List.of()));
                        mapped = true;
                    }
                    continue;
                }
                mapped = true;
                boolean split = hits.stream().map(Hit::criterion).distinct().count() > 1;
                String context = split || !text.equals(rule.text()) ? text : null;
                String relation = split && CHOICE.matcher(String.join(" ", Objects.toString(rule.scope(), ""), text)).find()
                        ? "ALTERNATIVE" : split ? "LINKED" : null;
                List<String> related = hits.stream().map(hit -> hit.criterion.label).distinct().toList();
                for (int index = 0; index < hits.size(); index++) {
                    Hit hit = hits.get(index);
                    int start = index == 0 ? 0 : ParticipationClauseText.boundary(text, hits.get(index - 1).end, hit.start);
                    int end = index + 1 == hits.size() ? text.length() : ParticipationClauseText.boundary(text, hit.end, hits.get(index + 1).start);
                    // Company size/form alone cannot distinguish revenue tiers or role-specific applicants.
                    String fragment = hit.criterion == ParticipationCriterion.COMPANY_SIZE || hit.criterion == ParticipationCriterion.BUSINESS_FORM
                            ? text : ParticipationClauseText.fragment(text, start, end);
                    var candidate = entry(rule, fragment, context, relation, related);
                    var target = rows.get(hit.criterion.name());
                    if (target.stream().noneMatch(old -> same(old, candidate))) target.add(candidate);
                }
            }
            if (!mapped && !ParticipationClauseText.caption(rule.text())) {
                String group = preference ? "PREFERENCE" : rule.section().equals("EXCLUSION") ? "RESTRICTIONS" : "COMPANY";
                extras.computeIfAbsent(group, key -> new ArrayList<>()).add(entry(rule, rule.text(), null, null, List.of()));
            }
        }
        var groups = new LinkedHashMap<String, List<ParticipationCriterionRow>>();
        var labels = new LinkedHashMap<String, String>();
        for (var criterion : ParticipationCriterion.values()) {
            labels.put(criterion.group, criterion.groupLabel);
            var entries = distinctEntries(rows.get(criterion.name()).stream().map(entry -> ParticipationDisplayText.qualify(entry, multipleTypes)).toList());
            if (entries.stream().anyMatch(entry -> !ParticipationDisplayText.readable(entry))) uncertain.add(criterion);
            entries = entries.stream().filter(ParticipationDisplayText::readable).toList();
            groups.computeIfAbsent(criterion.group, key -> new ArrayList<>()).add(new ParticipationCriterionRow(
                    criterion.name(), criterion.label, entries.isEmpty() ? incomplete || uncertain.contains(criterion) ? "REVIEW_REQUIRED" : "NOT_STATED" : "STATED",
                    List.copyOf(entries)));
        }
        extras.forEach((group, entries) -> groups.get(group).add(new ParticipationCriterionRow("OTHER",
                "그 밖의 명시 조건·확인 사항",
                entries.stream().allMatch(entry -> entry.rule().section().equals("REVIEW")) ? "REVIEW_REQUIRED" : "STATED",
                List.copyOf(distinctEntries(entries.stream().map(entry -> ParticipationDisplayText.qualify(entry, multipleTypes)).filter(ParticipationDisplayText::readable).toList())))));
        return groups.entrySet().stream().map(group -> new ParticipationCategory(group.getKey(),
                labels.get(group.getKey()), List.copyOf(group.getValue()))).toList();
    }

    private record Hit(ParticipationCriterion criterion, int start, int end) {}

    private static List<Hit> topics(String text, boolean preference, ParticipationRule rule) {
        var hits = new ArrayList<Hit>();
        for (var criterion : ParticipationCriterion.values()) {
            if (criterion.group.equals("PREFERENCE") != preference) continue;
            if (criterion == ParticipationCriterion.COMPANY_SIZE
                    && text.matches("(?s).*(?:대기업|중소기업|중견기업).*(?:경우|과제수|과제 수).*(?:필수|구성|사전지원제외).*")) continue;
            if (criterion == ParticipationCriterion.SECTOR && text.matches(".*업종별 상시근로자 수.*")) continue;
            if (criterion == ParticipationCriterion.CONSORTIUM
                    && !text.matches("(?s).*(?:컨소시[엄움]|참여기관 구성|산학연|확약|협약기관).*")) continue;
            if (criterion == ParticipationCriterion.CERTIFICATION && text.matches("(?s).*이전.*")
                    && !text.matches("(?s).*(?:인증|인정서|보유해야|보유한).*")) continue;
            if (rule.section().equals("EXCLUSION") && criterion == ParticipationCriterion.EXPERTS
                    && (ParticipationCriterion.SANCTIONS.topic.matcher(text).find()
                        || ParticipationCriterion.TAX.topic.matcher(text).find()
                        || ParticipationCriterion.DEFAULT.topic.matcher(text).find())) continue;
            if (criterion == ParticipationCriterion.EXPERTS
                    && ParticipationCriterion.DUPLICATE.topic.matcher(text).find()
                    && !text.matches("(?s).*(?:학위|박사|석사|자격증|전문 인력).*")) continue;
            var match = criterion.topic.matcher(text);
            while (match.find()) {
                // Legal citations and parenthetical examples stay attached to their enclosing condition.
                if (ParticipationClauseText.inside(text, match.start())) continue;
                var target = (rule.section().equals("EXCLUSION") || text.matches("(?s).*(?:제외업종|제외 대상 업종|융자대상에서 제외).*"))
                        && criterion == ParticipationCriterion.SECTOR
                        ? ParticipationCriterion.EXCLUDED_SECTOR : criterion;
                hits.add(new Hit(target, match.start(), match.end()));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::start).thenComparing(Comparator.comparingInt(Hit::end).reversed()));
        var result = new ArrayList<Hit>();
        for (var hit : hits) {
            if (!result.isEmpty() && hit.start < result.getLast().end) continue;
            if (!result.isEmpty() && hit.criterion == result.getLast().criterion) {
                Hit last = result.removeLast();
                result.add(new Hit(last.criterion, last.start, hit.end));
            } else result.add(hit);
        }
        // Do not split within a legal reference or parentheses even when topics were encountered in enum order.
        return result;
    }

    private static ParticipationCriterionEntry entry(ParticipationRule rule, String text, String context, String relation, List<String> related) {
        String display = rule.section().equals("REVIEW") ? text : ParticipationDisplayText.complete(text, context);
        var projected = new ParticipationRule(rule.section(), rule.label(), display, rule.scope(), rule.audience(),
                rule.details(), rule.exceptions(), rule.guidance(), rule.outcomes(), rule.sources(), rule.reviewReason());
        return new ParticipationCriterionEntry(projected, ParticipationDisplayText.context(context), relation, related);
    }

    private static List<ParticipationCriterionEntry> distinctEntries(List<ParticipationCriterionEntry> entries) {
        var result = new ArrayList<ParticipationCriterionEntry>();
        for (var candidate : entries) {
            int duplicate = -1;
            for (int i = 0; i < result.size(); i++)
                if (sameContent(result.get(i), candidate)) { duplicate = i; break; }
            if (duplicate < 0) result.add(candidate);
            else {
                var old = result.get(duplicate);
                var rule = old.rule();
                var sources = new LinkedHashSet<>(rule.sources());
                sources.addAll(candidate.rule().sources());
                result.set(duplicate, new ParticipationCriterionEntry(new ParticipationRule(rule.section(), rule.label(), rule.text(),
                        rule.scope(), rule.audience(), union(rule.details(), candidate.rule().details()), rule.exceptions(), rule.guidance(), rule.outcomes(),
                        List.copyOf(sources), rule.reviewReason()), old.context(), old.relation(), old.relatedCriteria()));
            }
        }
        return result;
    }

    private static List<String> union(List<String> left, List<String> right) {
        var values = new LinkedHashSet<>(left);
        values.addAll(right);
        return List.copyOf(values);
    }

    private static boolean sameContent(ParticipationCriterionEntry left, ParticipationCriterionEntry right) {
        return ParticipationDisplayText.identity(left.rule().text()).equals(ParticipationDisplayText.identity(right.rule().text())) && left.rule().section().equals(right.rule().section())
                && Objects.equals(left.rule().scope(), right.rule().scope())
                && Objects.equals(left.rule().audience(), right.rule().audience())
                && (left.rule().details().equals(right.rule().details()) || left.rule().details().isEmpty() || right.rule().details().isEmpty()
                    || left.rule().text().endsWith("기준은 세부 조건에서 확인할 수 있습니다."))
                && left.rule().outcomes().equals(right.rule().outcomes())
                && left.rule().guidance().equals(right.rule().guidance())
                && left.rule().exceptions().equals(right.rule().exceptions())
                && (Objects.equals(left.context(), right.context()) || ParticipationDisplayText.legalCategory(left.rule().text())
                    || ParticipationDisplayText.simpleHeadcount(left.rule().text()) && ParticipationDisplayText.simpleHeadcount(right.rule().text()))
                && Objects.equals(left.relation(), right.relation());
    }

    private static boolean same(ParticipationCriterionEntry left, ParticipationCriterionEntry right) {
        return ParticipationDisplayText.identity(left.rule().text()).equals(ParticipationDisplayText.identity(right.rule().text())) && left.rule().section().equals(right.rule().section())
                && Objects.equals(left.rule().scope(), right.rule().scope())
                && Objects.equals(left.rule().audience(), right.rule().audience())
                && left.rule().details().equals(right.rule().details())
                && left.rule().outcomes().equals(right.rule().outcomes())
                && left.rule().guidance().equals(right.rule().guidance())
                && left.rule().exceptions().equals(right.rule().exceptions())
                && (Objects.equals(left.context(), right.context()) || ParticipationDisplayText.legalCategory(left.rule().text())
                    || ParticipationDisplayText.simpleHeadcount(left.rule().text()) && ParticipationDisplayText.simpleHeadcount(right.rule().text()))
                && left.rule().sources().equals(right.rule().sources());
    }
}
