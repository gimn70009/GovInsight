package com.publicmonitor.backend.domain.report;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Email only: show complete, explicit channels, with detailed instructions left in the source. */
final class ReportEmailCompactPresentation {
    private static final String UNKNOWN = "원문에서 확인해 주세요.";
    private static final Pattern URL = Pattern.compile(
            "(?i)(?:https?://|www\\.)[^\\s<>()\\[\\]\"']+|[a-z0-9.-]+\\.(?:go\\.kr|or\\.kr|co\\.kr|org|com)(?:/[^\\s<>()\\[\\]\"']*)?");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:0\\d{1,3}[-)\\s]+\\d{3,4}[-\\s]+\\d{4}(?:/\\d{4})?|1[568]\\d{2}-\\d{4})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern DETAIL = Pattern.compile(
            "^(?:※|필수이행|유의|주의|[①-⑳])|회원가입|약관|사전\\s*등록|권한|허위|위[·․]변조|불이익|선정\\s*취소|접수시스템.*차단");
    private static final Pattern PROCEDURE = Pattern.compile("중복성\\s*제기|이의\\s*(?:신청|제기)|선정\\s*후|협약\\s*후");
    private static final Pattern ROLE = Pattern.compile("(영문|국문|국내|해외|한국|스페인|일본|영국)(?:서)?(?=\\s|[:：(]|측|$)");

    private ReportEmailCompactPresentation() {}

    static boolean supports(String label) {
        return label.equals("제출처·방법") || label.equals("문의 담당");
    }

    static List<String> lines(String label, String value) {
        return label.equals("문의 담당") ? contacts(value) : routes(value);
    }

    private static List<String> sourceLines(String value) {
        var result = new ArrayList<String>();
        String normalized = value.replaceAll("(?<=\\s)[□■○●▪•◦▫]\\s+", "\n");
        for (String raw : normalized.split("\\n|\\s+/\\s+(?=[^/]{0,60}(?:제출|접수|문의|연락처|이메일))")) {
            String line = raw.replaceFirst("^(?:[\\s•·ㆍㅇ○●□■↳※*-]+|[oO]\\s+)+", "")
                    .replace("☎", "").replaceAll("\\s+", " ").strip();
            if (!line.isBlank()) result.add(line);
        }
        return result;
    }

    private static List<String> routes(String value) {
        var routes = new LinkedHashSet<String>();
        var simple = new LinkedHashSet<String>();
        for (String line : sourceLines(value)) {
            if (DETAIL.matcher(line).find() || PROCEDURE.matcher(line).find()
                    || line.contains("문의") || line.contains("원문 확인")) continue;
            var matcher = URL.matcher(line);
            boolean hasUrl = false;
            int previousEnd = 0;
            while (matcher.find()) {
                String local = line.substring(previousEnd, matcher.start());
                previousEnd = matcher.end();
                if (matcher.start() > 0 && line.charAt(matcher.start() - 1) == '@') continue;
                String address = matcher.group().replaceFirst("[.,;]+$", "");
                String url = address.matches("(?i)^https?://.*") ? address : "https://" + address;
                if (!ReportBodyFormatter.safeUrl(url) || url.contains("@")) continue;
                String host = URI.create(url).getHost().toLowerCase();
                String text = routeLabel(local, host);
                if (text == null) continue;
                routes.add("[" + text + "](" + url + ")");
                hasUrl = true;
            }
            if (hasUrl) {
                if (multipleActions(line)) {
                    String tail = line.substring(previousEnd).strip()
                            .replaceFirst("^[)\\]\\s/;·]+", "")
                            .replaceFirst("^\\(", "").replaceFirst("\\)$", "").strip();
                    // A URL and a second mandatory action can share one source
                    // line. Keep the entire short trailing clause, never just
                    // the words matched by an action pattern.
                    if (tail.length() > 110 || !multipleActions(tail)) return List.of(UNKNOWN);
                    routes.add(tail);
                }
                continue;
            }
            if (line.length() <= 110 && (line.matches(".*(?:온라인|우편|방문|이메일|전자우편|접수처|제출처|모두.*제출|한쪽.*제외).*")
                    || line.matches(".*(?:영문|국문|모두|동시에|동시).*?(?:제출|접수).*"))) {
                String text = line.replaceFirst("^(?:신청|접수|제출)\\s*(?:방법|처)\\s*[:：]\\s*", "");
                if (!text.matches(".*(?:등록과 동시에|제출하여야|전용시스템|전자파일|소인분|18시까지|서명본).*")) simple.add(text);
            }
        }
        // Preserve the sequence of an explicit two-step postal/email obligation.
        if (value.matches("(?s).*우편.{0,45}(?:후|이후).{0,20}(?:이메일|전자우편).*")
                && !value.matches("(?s).*우편.{0,8}(?:불필요|받지|불가|금지).*")) {
            boolean visit = (value.contains("방문접수") || value.contains("방문 접수"))
                    && !value.matches("(?s).*방문.{0,12}(?:불필요|받지|불가|금지|안\\s*됨).*");
            String method = visit
                    ? "우편·방문 접수 후 이메일 제출" : "우편 접수 후 이메일 제출";
            routes.add(method);
            var email = EMAIL.matcher(value);
            if (email.find()) routes.add("이메일: " + email.group());
        } else if (routes.isEmpty()) {
            routes.addAll(simple);
        }
        // A country-specific obligation must not disappear behind a generic portal.
        for (String item : simple) {
            if (ROLE.matcher(item).find() || item.matches(".*(?:모두.*제출|동시.*(?:접수|제출)|한쪽.*제외|원본.*(?:우편|방문)).*")) routes.add(item);
        }
        if (routes.isEmpty() || routes.size() > 3) return List.of(UNKNOWN);
        return List.copyOf(routes);
    }

    private static boolean multipleActions(String value) {
        return value.matches(".*온라인.{0,20}후.{0,30}원본.{0,20}(?:우편|방문|제출|발송).*")
                || value.matches(".*(?:두\\s*(?:사이트|곳)|양쪽|양국).{0,30}동시.{0,20}(?:접수|제출).*")
                || value.matches(".*한\\s*쪽.{0,30}(?:제외|불가).*");
    }

    private static String routeLabel(String line, String host) {
        String role = "";
        var roleMatch = ROLE.matcher(line.split("[:：]", 2)[0]);
        if (roleMatch.find()) role = roleMatch.group(1) + " 접수: ";
        if (host.equals("iris.go.kr") || host.endsWith(".iris.go.kr")) return role + "IRIS 온라인 접수";
        if (host.equals("one-click.co.kr") || host.endsWith(".one-click.co.kr")) {
            return (line.contains("재무제표") ? "재무제표: " : role) + "원클릭 제출";
        }
        if (host.equals("k-pass.kr") || host.endsWith(".k-pass.kr")) return role + "K-PASS 온라인 접수";
        if (role.isEmpty() && !line.matches(".*(?:신청|접수|제출|등록처|계획서).*")) return null;
        return role.isEmpty() ? "온라인 접수 페이지" : role + "접수 페이지";
    }

    private static List<String> contacts(String value) {
        var groups = new LinkedHashMap<String, LinkedHashSet<String>>();
        String pending = "";
        for (String line : sourceLines(value)) {
            if (DETAIL.matcher(line).find() || PROCEDURE.matcher(line).find() || line.contains("공고기관")) continue;
            var channels = new LinkedHashSet<String>();
            var phone = PHONE.matcher(line);
            while (phone.find()) channels.add(phone.group().replaceAll("\\s+", ""));
            var email = EMAIL.matcher(line);
            while (email.find()) channels.add(email.group());
            if (channels.isEmpty()) {
                if (line.length() <= 70 && line.matches(".*(?:팀|과|실|부서|센터).*")) pending = line;
                continue;
            }
            String label = EMAIL.matcher(PHONE.matcher(line).replaceAll("")).replaceAll("")
                    .replaceAll("\\([^)]*\\)", "").replaceAll("[(),/\\s]+$", "").strip();
            label = label.replaceAll("^(?:연락처|전화|이메일)\\s*[:：]?\\s*", "");
            if (label.isBlank()) label = pending;
            label = contactLabel(label);
            if (label.length() > 70) continue;
            groups.computeIfAbsent(label.isBlank() ? "문의" : label, ignored -> new LinkedHashSet<>()).addAll(channels);
        }
        if (groups.isEmpty()) {
            String clean = String.join(" ", sourceLines(value));
            return List.of(clean.length() <= 70 && clean.matches(".*(?:문의|담당).*?(?:팀|과|실|부서|센터).*") ? clean : UNKNOWN);
        }
        var result = new ArrayList<String>();
        for (var entry : groups.entrySet()) {
            result.add(entry.getKey().replaceFirst("[:：]\\s*$", "") + ": " + String.join(", ", entry.getValue()));
        }
        return List.copyOf(result.subList(0, Math.min(2, result.size())));
    }

    private static String contactLabel(String label) {
        return label.replaceAll("연구개발계획서\\s*전산\\s*등록\\s*관련\\s*문의\\s*[:：]?\\s*", "접수 시스템: ")
                .replaceAll("재무제표\\s*온라인\\s*(?:전용시스템\\s*)?제출\\s*관련\\s*문의\\s*[:：]?\\s*", "재무제표: ")
                .replaceAll("(영문|국문)\\s*연구개발계획서\\s*접수\\s*[:：]?\\s*", "$1 접수: ")
                .replaceAll("^(?:문의처|문의 담당|담당부서)\\s*[:：]?\\s*", "")
                .replaceAll("[:：]\\s*(?=\\S)", " · ")
                .replaceAll("\\s+", " ").strip();
    }
}
