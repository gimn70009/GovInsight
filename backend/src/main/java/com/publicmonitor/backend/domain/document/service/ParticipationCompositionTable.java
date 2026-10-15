package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Only labelled applicant roles and explicit participant composition requirements become criteria. */
final class ParticipationCompositionTable {
    private static final Pattern ROLE = Pattern.compile("\\((주관|공동|참여|위탁|임상시험|실증)\\)\\h*");
    private ParticipationCompositionTable() {}

    static List<ParticipationCondition> read(String[] rows, List<String> footnotes, RequirementSource source) {
        var result = new ArrayList<ParticipationCondition>();
        for (String row : rows) {
            String[] cells = row.split("\t", -1);
            if (cells.length != 2) return List.of(review(source));
            var matcher = ROLE.matcher(cells[1]);
            var roles = new ArrayList<String>();
            var starts = new ArrayList<Integer>();
            var ends = new ArrayList<Integer>();
            while (matcher.find()) { roles.add(matcher.group(1)); starts.add(matcher.end()); ends.add(matcher.start()); }
            for (int i = 0; i < roles.size(); i++) {
                String role = roles.get(i);
                String content = cells[1].substring(starts.get(i), i + 1 < roles.size() ? ends.get(i + 1) : cells[1].length());
                var clauses = new ArrayList<>(List.of(content.split("\\h*※\\h*")));
                String lead = clauses.removeFirst().strip();
                clauses.replaceAll(String::strip);
                clauses.removeIf(String::isBlank);
                boolean applicantRole = role.matches("주관|공동|참여|위탁");
                int main = -1;
                for (int c = 0; c < clauses.size(); c++) {
                    if (clauses.get(c).matches(".*(?:기관|기업|병원).*(?:참여 필수|구성하여야|포함해야).*")) { main = c; break; }
                }
                // Execution locations and other project instructions do not establish applicant eligibility.
                if (!applicantRole && main < 0) continue;
                String text;
                if (main >= 0) { text = clauses.remove(main); clauses.addFirst(lead); }
                else if (lead.matches(".*(?:기업|기관|대학|연구소)(?: 등)?$")) text = role + "기관은 " + lead + "에 해당해야 합니다.";
                else { text = role + "기관의 구성 기준을 충족해야 합니다."; clauses.addFirst(lead); }
                text = text.replace("참여 필수", "참여해야 합니다");
                if (applicantRole) clauses.addAll(footnotes);
                result.add(new ParticipationCondition(ParticipationKind.MANDATORY, "참여기관 구성", text, applicantRole ? null : lead,
                        List.copyOf(clauses), source));
            }
        }
        return result.isEmpty() ? List.of(review(source)) : List.copyOf(result);
    }

    static ParticipationCondition review(RequirementSource source) {
        return new ParticipationCondition(ParticipationKind.REVIEW_REQUIRED, "참여기관 구성",
                "기관별 역할과 필수 참여 요건을 확인해야 합니다.", null, List.of(), source, ParticipationReviewReason.TABLE_MAPPING_UNCLEAR);
    }
}
