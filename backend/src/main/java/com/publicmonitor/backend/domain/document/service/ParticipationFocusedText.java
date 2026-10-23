package com.publicmonitor.backend.domain.document.service;

import java.util.*;
import java.util.regex.Pattern;

/** Local, conservative display checks. No model calls and no request/cache-key mutations. */
final class ParticipationFocusedText {
    private static final Pattern DOCUMENT = Pattern.compile("(?:신청|제출|구비)\\s*서류|체크\\s*리스트|확약서|납세증명|재무제표증명[^\\n]*제출|사업\\s*신청서|사업계획서|견적서");
    private static final Pattern OUTSIDE = Pattern.compile("소재지|대표자|국세|지방세|체납|휴[·ㆍ‧.]?폐업");
    record Display(String text, String quote) {}

    static boolean documentSection(String heading) {
        String value = Objects.toString(heading, "");
        int section = value.indexOf("[원문 구역]");
        if (section >= 0) value = value.substring(section + "[원문 구역]".length());
        return DOCUMENT.matcher(value).find() || value.matches(".*(?:(?:신청|접수|제출)\\s*방법|지원\\s*(?:내용|규모)|사업\\s*목적|평가\\s*절차).*");
    }

    static Display prepare(String code, String text, String quote, String heading, String kind) {
        if (text == null || quote == null || (documentSection(heading) && !Pattern.compile("신청\\s*(?:자격|대상|요건)|지원\\s*(?:대상|자격|요건)|선정\\s*대상|우대|가점").matcher(quote).find())) return null;
        boolean original = compact(text).equals(compact(quote));
        if (original && quote.contains("[표]")) {
            // Repair an old cached whole-table selection only when one unambiguous condition row exists.
            if (documentSection(quote.lines().findFirst().orElse(""))) return null;
            var rows = new LinkedHashSet<String>();
            boolean table = false;
            for (String line : quote.split("\\R")) {
                if (line.strip().equals("[표]")) { table = true; continue; }
                if (line.strip().equals("[/표]")) { table = false; continue; }
                if (!table || !line.contains("\t")) continue;
                String cell = line.substring(line.lastIndexOf('\t') + 1).strip();
                if (allowed(code, cell) && cell.matches(".*(?:이어야|하여야|해야|영위|보유|이상|이하|미만|초과|이내|가점|우대).*"))
                    rows.add(cell);
            }
            if (rows.size() != 1) return null;
            quote = rows.iterator().next();
            text = quote;
        }
        if (!allowed(code, quote) || !allowed(code, text)) return null;
        if (original) {
            if (text.length() > 600 || text.contains("[표]") || text.contains("\t")) return null;
            return new Display(text, quote);
        }
        if (!text.matches("(?s).*(?:다|요)\\.$") || text.length() > 240 || text.matches("(?s).*\\[/?표\\].*")
                || text.contains("\t") || text.matches("(?s).*https?://.*") || !facts(text, fieldQuote(text, quote, code), kind)) return null;
        return new Display(text, quote);
    }

    private static boolean allowed(String code, String text) {
        return !DOCUMENT.matcher(text).find() && !OUTSIDE.matcher(text).find()
                && (code.equals("BUSINESS_FORM") || !Pattern.compile("상시\\s*근로자|근로자\\s*수").matcher(text).find())
                && ParticipationFocusCriterion.matching(text, "").contains(code);
    }

    private static String fieldQuote(String text, String quote, String code) {
        var match = Pattern.compile("(?<age>업력\\s*\\d+년\\s*이상)의?\\s*(?<revenue>매출액\\s*[\\d,]+억원\\s*달성)\\s*중소기업").matcher(quote.strip());
        if (!match.matches()) return quote;
        if (code.equals("BUSINESS_AGE") && !text.contains("매출")) return match.group("age");
        if (code.equals("REVENUE") && !text.contains("업력")) return match.group("revenue");
        return quote;
    }

    static boolean facts(String text, String quote, String kind) {
        var preference = Pattern.compile("우대|가점|가산점|우선\\s*(?:선정|지원)");
        if (preference.matcher(text).find() != "PREFERENCE".equals(kind)) return false;
        return ParticipationFacts.equivalent(text, quote);
    }
    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
}
