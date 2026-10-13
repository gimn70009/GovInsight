package com.publicmonitor.backend.domain.report;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Email only: show complete, explicit channels, with detailed instructions left in the source. */
final class ReportEmailCompactPresentation {
    private static final String UNKNOWN = "원문에서 확인해 주세요.";
    private static final Pattern URL = Pattern.compile(
            "(?i)(?:https?://|www\\.)[^\\s<>()\\[\\]\"']+|[a-z0-9.-]+\\.(?:go\\.kr|or\\.kr|co\\.kr|org|com)(?:/[^\\s<>()\\[\\]\"']*)?");
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
        return label.equals("문의 담당") ? ReportContactPresentation.lines(value) : routes(value);
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
        var distinct = distinctRoutes(routes);
        if (distinct.isEmpty() || distinct.size() > 3) return List.of(UNKNOWN);
        return distinct;
    }

    private static final Pattern ROUTE_LINK = Pattern.compile("^\\[([^\\[\\]\\r\\n]+)\\]\\((https?://[^\\s()<>\"]+)\\)$");
    private record RouteAddress(String scheme, String host, int port, String path, String query, String fragment) {}
    private record RouteIdentity(RouteAddress address, String role) {}
    private record RouteEntry(String text, RouteAddress address, String role) {}

    private static List<String> distinctRoutes(LinkedHashSet<String> routes) {
        var entries = new ArrayList<RouteEntry>();
        var specificAddresses = new LinkedHashSet<RouteAddress>();
        for (String route : routes) {
            var match = ROUTE_LINK.matcher(route);
            if (!match.matches() || !ReportBodyFormatter.safeUrl(match.group(2))) {
                entries.add(new RouteEntry(route, null, ""));
                continue;
            }
            String label = match.group(1);
            String role = label.contains(":") ? label.substring(0, label.indexOf(':')).strip() : "";
            var address = routeAddress(match.group(2));
            entries.add(new RouteEntry(route, address, role));
            if (!role.isEmpty()) specificAddresses.add(address);
        }
        var result = new ArrayList<String>();
        var seen = new LinkedHashSet<RouteIdentity>();
        for (var entry : entries) {
            if (entry.address() == null) {
                result.add(entry.text());
            } else if (!(entry.role().isEmpty() && specificAddresses.contains(entry.address()))
                    && seen.add(new RouteIdentity(entry.address(), entry.role()))) {
                result.add(entry.text());
            }
        }
        return List.copyOf(result);
    }

    private static RouteAddress routeAddress(String value) {
        var uri = URI.create(value);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        // Query, fragment, path case and percent encoding may identify different submission steps.
        return new RouteAddress(scheme, uri.getHost().toLowerCase(Locale.ROOT), port, path,
                uri.getRawQuery(), uri.getRawFragment());
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

}
