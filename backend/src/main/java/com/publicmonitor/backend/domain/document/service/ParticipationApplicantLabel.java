package com.publicmonitor.backend.domain.document.service;

import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.Statement;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import java.util.*;
import java.util.regex.Pattern;

/** Resolve applicant labels from explicit table structure, without a dictionary of job titles. */
final class ParticipationApplicantLabel {
    private static final Pattern TABLE = Pattern.compile("\\[표](.*?)\\[/표]", Pattern.DOTALL);
    private static final Set<String> APPLICANT_HEADERS = Set.of(
            "구분", "신청주체", "신청대상", "신청기관", "대상기관", "기관구분", "기관유형", "참여기관");
    private static final Set<String> QUALIFICATION_HEADERS = Set.of(
            "자격", "신청자격", "지원자격", "참여자격", "신청요건", "자격요건");
    private static final Pattern APPLICANT = Pattern.compile(
            ".*(?:기업|기관|단체|협회|대학|학교|조합|법인|사업자|소상공인|소공인|연구소|자치단체)(?:\\([^()]*\\))?");

    private record Row(String label, String text) {}

    private ParticipationApplicantLabel() {}

    static String from(Statement item, List<RequirementSource> sources) {
        if (!"BUSINESS_FORM".equals(item.criterion()) || "EXCLUSION".equals(item.kind())) return null;
        var rows = sources.stream().flatMap(source -> rows(source.excerpt()).stream()).toList();
        var labels = new LinkedHashSet<String>();
        for (String quote : item.supportingQuotes()) {
            String cited = compact(quote);
            if (cited.length() < 8) return null;
            var matches = rows.stream().filter(row -> compact(row.text()).contains(cited)
                    && !compact(row.label()).contains(cited)).map(Row::label).distinct().toList();
            // Repeated text in different rows, mixed rows or missing headers must not imply a role.
            if (matches.size() != 1) return null;
            labels.add(matches.getFirst());
        }
        return labels.size() == 1 ? labels.iterator().next() : null;
    }

    // A title's explicit personnel context and the cited row, not the role's name, decide this.
    static boolean isPersonnelAudience(Statement item, List<RequirementSource> sources) {
        if (!"EXPERIENCE".equals(item.criterion()) || item.audience() == null
                || item.audience().isBlank() || item.supportingQuotes().isEmpty()) return false;
        String label = compact(item.audience()).replaceFirst("\\([^()]*\\)$", "");
        if (label.isBlank()) return false;
        return item.supportingQuotes().stream().allMatch(quote -> sources.stream()
                .anyMatch(source -> personnelRow(source.excerpt(), label, quote)));
    }

    private static boolean personnelRow(String excerpt, String label, String quote) {
        if (excerpt == null || compact(quote).length() < 8) return false;
        var tables = TABLE.matcher(excerpt);
        int previousEnd = 0;
        while (tables.find()) {
            String prefix = excerpt.substring(previousEnd, tables.start());
            String heading = prefix.lines().filter(line -> !line.isBlank())
                    .reduce((first, last) -> last).orElse("");
            String region = excerpt.substring(previousEnd, tables.end());
            previousEnd = tables.end();
            if (!heading.matches(".*[（(]\\s*(?:전담\\s*)?인력\\s*[)）].*")) continue;
            String firstRow = tables.group(1).lines().filter(line -> !line.isBlank())
                    .findFirst().orElse("");
            if (Arrays.stream(firstRow.split("\\t")).map(ParticipationApplicantLabel::compact)
                    .anyMatch(Set.of("신청주체", "신청기관", "신청대상", "신청구분")::contains)) continue;
            String cited = compact(quote);
            if (!compact(region).contains(cited)) continue;
            for (String row : tables.group(1).split("\\R")) {
                String[] cells = row.split("\\t", -1);
                if (cells.length < 2) continue;
                boolean ownLabel = Arrays.stream(cells, 0, cells.length - 1)
                        .anyMatch(cell -> compact(cell).equals(label));
                String rowText = compact(row);
                if (ownLabel && (cited.contains(rowText) || rowText.contains(cited))) return true;
            }
        }
        return false;
    }

    private static List<Row> rows(String excerpt) {
        var rows = new ArrayList<Row>();
        if (excerpt == null) return rows;
        // Inline labels carry the same explicit role relationship as two-column applicant tables.
        for (String line : excerpt.split("\\R")) {
            var labelled = Pattern.compile("^[\\h◦○●•-]*[（(]([^()（）]+)[)）]\\h*(.+)$").matcher(line);
            if (labelled.matches() && APPLICANT.matcher(compact(labelled.group(1))).matches())
                rows.add(new Row(labelled.group(1).strip(), line));
        }
        var tables = TABLE.matcher(excerpt);
        while (tables.find()) {
            var lines = tables.group(1).lines().filter(line -> !line.isBlank()).toList();
            if (lines.isEmpty()) continue;
            String[] headers = lines.getFirst().split("\\t", -1);
            if (headers.length != 2) continue;
            int labelColumn = APPLICANT_HEADERS.contains(compact(headers[0])) ? 0 : 1;
            if (!APPLICANT_HEADERS.contains(compact(headers[labelColumn]))
                    || !QUALIFICATION_HEADERS.contains(compact(headers[1 - labelColumn]))) continue;
            for (String line : lines.subList(1, lines.size())) {
                String[] cells = line.split("\\t", -1);
                if (cells.length != 2 || cells[1 - labelColumn].isBlank()) continue;
                String label = cells[labelColumn].strip();
                if (label.isBlank() || label.length() > 100 || label.contains("⏎")) continue;
                // A generic 구분 column can also describe programs: accept only applicant nouns.
                if (compact(headers[labelColumn]).equals("구분") && !APPLICANT.matcher(compact(label)).matches()) continue;
                rows.add(new Row(label, line));
            }
        }
        return rows;
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
}
