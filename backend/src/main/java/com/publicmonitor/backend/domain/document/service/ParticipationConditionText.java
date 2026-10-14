package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ParticipationCondition;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ParticipationKind;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Presentation only: reconnect wrapped prose and preserve the official qualifiers in visible clauses. */
final class ParticipationConditionText {
    private static final Pattern MARKER = Pattern.compile("^(?:[□■○◦●ㅇ•⦁∘※*→-]|[①-⑳➀-➉]|\\d+[.)])\\h*");
    private ParticipationConditionText() {}

    static List<String> paragraphs(List<String> physicalLines) {
        List<String> result = new ArrayList<>();
        for (String physical : physicalLines) {
            // Bullets inside a table cell are real list boundaries; physical line breaks are not.
            for (String part : physical.replaceAll("\\h+([•⦁])", "\n$1")
                    .replaceAll("\\h+(-\\h+(?=[가-힣])|\\*{1,2}\\h+|\\d+[.]\\h+)", "\n$1")
                    .replaceAll("(?<=[.。])\\h+(?=단,|다만)", "\n").split("\\R")) {
                String line = part.replace('\u00a0', ' ').strip();
                if (line.isBlank() || line.matches("-?\\h*\\d+\\h*-?")) continue;
                boolean starts = MARKER.matcher(line).find() || line.matches("^[<〈].*[>〉]$")
                        || line.matches("^(?:단,|다만).*")
                        || line.matches("^\\((?:일반|혁신|희망|성장|도약|재창업)[^)]*\\).*");
                if (result.isEmpty() || starts || complete(result.getLast())) result.add(line);
                else result.set(result.size() - 1, result.getLast() + " " + line);
            }
        }
        return result.stream().map(ParticipationConditionText::spacing).toList();
    }

    private static boolean complete(String value) {
        return value.matches("(?s).*(?:합니다|됩니다|한다|불가|가능|필수|없음)[.!。]?$");
    }

    static ParticipationCondition prepare(ParticipationCondition row) {
        List<String> input = new ArrayList<>();
        input.add(row.text());
        input.addAll(row.details());
        var paragraphs = new ArrayList<String>();
        input.forEach(unit -> paragraphs.addAll(paragraphs(List.of(unit))));
        if (paragraphs.isEmpty()) return row;
        String main = sentence(paragraphs.getFirst(), row.kind());
        var details = new ArrayList<String>();
        for (String part : paragraphs.subList(1, paragraphs.size())) {
            String value = sentence(part, null);
            if (!value.isBlank() && !value.equals(main) && !details.contains(value)) details.add(value);
        }
        return new ParticipationCondition(row.kind(), row.category(), main,
                row.scope() == null ? null : spacing(row.scope()), details, row.source());
    }

    static String spacing(String value) {
        return value.replaceAll("\\h+", " ").strip()
                .replaceAll("신사업\\s+창업사관학교", "신사업창업사관학교")
                .replace("경우지원사업", "경우 지원사업")
                .replace("소지한장애", "소지한 장애")
                .replace("소상 공인", "소상공인")
                .replace("의운영에관한법률", "의 운영에 관한 법률")
                .replace("의이용및보호에관한법률", "의 이용 및 보호에 관한 법률");
    }

    static String unmark(String value) {
        return spacing(value).replaceFirst("^(?:[□■○◦●ㅇ•⦁∘※*→-]+|[①-⑳➀-➉]|\\d+[.)])\\h*", "").strip();
    }

    static String sentence(String raw, ParticipationKind kind) {
        String value = unmark(raw).replaceFirst("[,，]$", "");
        if (value.isBlank()) return value;
        value = value.replaceAll("(?<=[가-힣0-9])\\*+(?=[은는이가\\h,]|$)", "");
        value = value.replace("必", "필수").replace("內", "내").replace("中", "중");
        value = value.replaceFirst("인정하지\\h*않음[.]?$", "인정하지 않습니다.")
                .replaceFirst("가점[（(](\\d+\\h*점)[）)]\\h*부여[.]?$", "가점 $1을 부여합니다.")
                .replaceFirst("이어야\\h*함[.]?$", "이어야 합니다.")
                .replaceFirst("하여야\\h*함[.]?$", "해야 합니다.")
                .replaceFirst("해야\\h*함[.]?$", "해야 합니다.")
                .replaceFirst("신청\\h*가능[.]?$", "신청할 수 있습니다.")
                .replaceFirst("신청\\h*불가[.]?$", "신청할 수 없습니다.")
                .replaceFirst("신청을\\h*제한[.]?$", "신청이 제한됩니다.");
        var age = Pattern.compile("^업력\\h*(\\d+\\h*년\\h*(?:이내|미만|이상|초과))\\h*(기업|소상공인)[.]?$").matcher(value);
        if (age.matches()) return "업력이 " + age.group(1) + "인 " + age.group(2) + "이 대상입니다.";
        if (kind == ParticipationKind.EXCLUSION && value.matches(".*(?:기업|소상공인|경우|자(?:\\(기업\\))?)[.]?$")) {
            return value.replaceFirst("[.]$", "") + (value.endsWith("경우") ? "에는" : value.endsWith("자") ? "는" : "은") + " 신청이 제한됩니다.";
        }
        if (value.matches(".*(?:합니다|됩니다|습니다|하세요|한다)[.!?]$")) return value;
        if (value.matches(".*(?:경우|기업|기관|소상공인|수료생|보유자|받은 자)[.]?$")) {
            String base = value.replaceFirst("[.]$", "");
            if (kind == ParticipationKind.PREFERENCE) return base + (base.endsWith("경우") ? "는" : "은") + " 우대 대상입니다.";
            if (kind == ParticipationKind.MANDATORY) return base + (base.endsWith("경우") ? "에 해당해야 합니다." : "이 대상입니다.");
        }
        var legalType = Pattern.compile("^([『「｢].+?[』」｣](?:\\h*제\\d+조(?:제\\d+항)?(?:\\([^)]*\\))?)?)\\h*(?:에\\h*해당하는|에\\h*따른)\\h*(.+?)[.]?$").matcher(value);
        if (legalType.matches()) return legalType.group(2) + " (근거: " + legalType.group(1) + ").";
        // Other prose stays lossless. List structure, rather than invented paraphrases, carries its meaning.
        return value.matches(".*[.!?。>〉]$") ? value : value + ".";
    }
    /** Conservative grammatical completion; no new threshold, alternative or exemption is inferred. */
    static String displaySentence(String raw, ParticipationKind kind) {
        String value = sentence(raw, kind).replaceFirst("[.]$", "");
        if (value.matches(".*(?:합니다|됩니다|습니다|입니다|하세요|한다)$")) return value + ".";
        var qualified = Pattern.compile("^(.*?(?:소상공인|소공인|기업|기관|경우|자))(\\([^()]+\\))$").matcher(value);
        if (qualified.matches()) {
            if (kind == ParticipationKind.EXCLUSION) return value + topic(qualified.group(1)) + " 신청이 제한됩니다.";
            if (kind == ParticipationKind.MANDATORY) return value + "이 대상입니다.";
            return nominal(value);
        }
        var criteria = Pattern.compile("^(.{2,60})의 신청 기준$").matcher(value);
        if (criteria.matches()) return criteria.group(1) + topic(criteria.group(1)) + " 공고에 명시된 자격 요건을 충족해야 합니다.";
        var legal = Pattern.compile("^(.+?) \\(근거: (.+)\\)$").matcher(value);
        if (legal.matches()) return nominal(legal.group(1)) + " (적용 법령: " + legal.group(2) + ")";
        value = value.replaceFirst("부여함$", "부여합니다").replaceFirst("인정함$", "인정합니다")
                .replaceFirst("제출해야 함$", "제출해야 합니다").replaceFirst("제출 가능$", "제출할 수 있습니다")
                .replaceFirst("인정 가능$", "인정될 수 있습니다");
        if (value.matches(".*(?:합니다|됩니다|습니다|입니다)$")) return value + ".";
        if (kind == ParticipationKind.EXCLUSION && value.matches(".*(?:업|기업|기관|경우|자(?:\\(기업\\))?)$"))
            return value + topic(value) + " 신청이 제한됩니다.";
        if (value.matches(".*(?:기업|기관|단체|소상공인|수료생|보유자|받은 자|가능한 자|[가-힣]+한 자|결정된 자|체결한 자|소공인|제조업|지주회사|전문회사|창업기획자|법인)$"))
            return nominal(value);
        var exceptionDate = Pattern.compile("^조건\\h*예외\\h*대상\\((.+?)\\h*기준\\)$").matcher(value);
        if (exceptionDate.matches()) return "예외 여부는 " + exceptionDate.group(1) + " 기준으로 확인합니다.";
        if (value.matches("^업력\\h*\\d+\\h*년\\h*(?:이내|이상|이하|미만|초과)$"))
            return value.replaceFirst("^업력\\h*", "업력 기준은 ") + "입니다.";
        if (value.matches(".*(?:확인서|증명서|신청서|서류)\\h+제출$"))
            return value.replaceFirst("\\h+제출$", " 제출이 필요합니다.");
        if (value.endsWith("허용")) return value + "됩니다.";
        if (value.endsWith("포함")) return value + "됩니다.";
        if (value.endsWith("해당")) return value + "합니다.";
        if (value.endsWith("예외")) return value + "입니다.";
        if (value.endsWith("경우")) return value + "입니다.";
        if (value.endsWith("기준")) return value + "으로 확인합니다.";
        if (value.endsWith("필수")) return value.substring(0, value.length() - 2).strip() + "이 필수입니다.";
        return value + ".";
    }

    private static String nominal(String value) {
        return value + "입니다.";
    }

    private static String topic(String value) {
        String ending = value.replaceAll("[)』」｣‘’“”\\h]", "");
        if (ending.isEmpty()) return "은";
        char last = ending.charAt(ending.length() - 1);
        return last >= '가' && last <= '힣' && (last - '가') % 28 == 0 ? "는" : "은";
    }

    static boolean incomplete(String value) {
        return value.replaceFirst("[.]$", "").matches(".*(?:[가-힣](?:을|를)|및|또는|하거나|으로부터|투자금을|신청자격\\h*구분)$");
    }

}
