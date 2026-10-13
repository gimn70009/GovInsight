package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ResponsibleOrganization;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Read explicit role/name pairs from the current version, never from generated analysis. */
final class ResponsibleOrganizationExtractor {
    private static final int MAX_TEXT_LENGTH = 200_000;
    private static final String ROLES = "주관기관|주최기관|총괄기관|총괄부처|소관부처|시행기관|전담기관|운영기관|관리기관|전문기관|주관|주최|접수처|문의처";
    private static final String SPACED_ROLES = String.join("|", List.of(ROLES.split("\\|")).stream()
            .map(role -> String.join("\\h*", role.split(""))).toList());
    private static final Pattern LABELED = Pattern.compile(
            "^[\\h|□◦○ㅇ▪▫※*·•∙⦁■▶◈-]*(?:\\(|\\[)?\\h*(?<role>" + SPACED_ROLES
                    + ")(?![가-힣A-Za-z])\\h*(?<separator>[)\\]:：|]|\\(|$|\\h)(?<value>.*)$");
    private static final Pattern NAME = Pattern.compile(
            "^((?:\\([재사]\\)\\h*)?[가-힣A-Za-z][가-힣A-Za-z0-9·ㆍ‧∙&.\\-\\h]{1,78}?"
                    + "(?:공단|재단|협회|진흥원|진흥회|연구원|연구소|센터|대학교|대학|공사|기술원|개발원|평가원|지원단|사업단|조합|상공회의소|테크노파크|위원회|청|부|원|시|도))"
                    + "(?=$|[\\h,;/|()])");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)예시|견본|가칭|미정|추후|모집|선정|기재|작성|입력|해당|각\\s*기관|전문기업|중소기업$|(?:^|[가-힣])(?:OO|XX)|[○□ㅇ]{2}");
    private static final Pattern FORM_HEADING = Pattern.compile(
            "^[\\h\\[<(【]*(?:붙임|별첨|별지|서식)\\h*(?:제\\h*)?\\d|^[\\h\\[<(【]*(?:사업계획서|신청서|공적조서|동의서)");
    private static final Pattern EXAMPLE_HEADING = Pattern.compile("^.{0,12}(?:작성\\h*예시|작성\\h*예제|작성\\h*견본)");
    private static final Pattern NON_NOTICE_FILE = Pattern.compile("(?i)예시|견본|FAQ|참고자료|신청서|계획서|동의서|서식|양식|협약서");
    private static final Pattern NOTICE_FILE = Pattern.compile("공고|모집|안내");
    private static final Pattern OTHER_CONTEXT = Pattern.compile("전년도|지난해|타\\h*사업|다른\\h*사업|참고\\h*사업|작성\\h*예시|예\\h*[:：]");

    private ResponsibleOrganizationExtractor() {}

    static List<ResponsibleOrganization> extract(String body, List<DocumentAttachment> attachments) {
        var found = new LinkedHashMap<String, ResponsibleOrganization>();
        collect(body, null, found);
        for (var attachment : attachments) {
            String fileName = attachment.getFileName();
            if (attachment.getParseStatus() != AttachmentParseStatus.COMPLETED
                    || (NON_NOTICE_FILE.matcher(fileName).find() && !NOTICE_FILE.matcher(fileName).find())) continue;
            collect(attachment.getExtractedText(), attachment, found);
        }
        // A contact entry need not repeat an institution already identified with its operating role.
        var operatingNames = found.values().stream().filter(item -> !isContact(item.role()))
                .map(item -> nameKey(item.name())).toList();
        return found.values().stream()
                .filter(item -> !isContact(item.role()) || !operatingNames.contains(nameKey(item.name())))
                .sorted(Comparator.comparingInt(item -> isContact(item.role()) ? 1 : 0))
                .toList();
    }

    private static void collect(String text, DocumentAttachment attachment,
                                LinkedHashMap<String, ResponsibleOrganization> found) {
        if (text == null || text.isBlank()) return;
        String bounded = text.substring(0, Math.min(text.length(), MAX_TEXT_LENGTH));
        String[] lines = bounded.replace('\u00a0', ' ').replace("\u200b", "").split("\\R+");
        boolean form = false;
        boolean example = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (FORM_HEADING.matcher(line).find()) form = true;
            if (EXAMPLE_HEADING.matcher(line).find()) example = true;
            if (example || OTHER_CONTEXT.matcher(line).find()) continue;
            var label = LABELED.matcher(line);
            if (!label.matches()) continue;
            String role = label.group("role").replaceAll("\\h", "");
            if (form && !isContact(role)) continue;
            String value = label.group("value").replaceFirst("^[\\h:：|]+", "").strip();
            String excerpt = line;
            if (value.isEmpty() && index + 1 < lines.length) {
                value = lines[index + 1].strip();
                excerpt += "\n" + value;
            }
            // Parentheses may surround the institution name (including a separate table cell).
            if (value.startsWith("(") && !value.matches("^\\([재사]\\).*")) value = value.substring(1).strip();
            for (String name : names(value)) {
                var item = new ResponsibleOrganization(name, role,
                        attachment == null ? "NOTICE_BODY" : "ATTACHMENT",
                        attachment == null ? null : attachment.getFileName(), excerpt);
                found.putIfAbsent(role + ":" + nameKey(name), item);
            }
        }
    }

    private static List<String> names(String value) {
        if (value.length() > 500 || OTHER_CONTEXT.matcher(value).find()
                || value.matches(".*(?:예시|견본|가칭|예정|미정|추후).*")) return List.of();
        var names = new ArrayList<String>();
        String remaining = value;
        while (!remaining.isBlank() && names.size() < 8) {
            var name = NAME.matcher(remaining);
            if (!name.find()) break;
            String candidate = name.group(1).replaceAll("\\h+", " ").strip();
            if (PLACEHOLDER.matcher(candidate).find()) break;
            names.add(candidate);
            remaining = remaining.substring(name.end()).strip();
            // Only explicit list separators join multiple institutions; departments/phones are not names.
            if (!remaining.matches("^[,/;].*")) break;
            remaining = remaining.substring(1).strip();
        }
        return names;
    }

    private static boolean isContact(String role) {
        return role.equals("문의처") || role.equals("접수처");
    }

    private static String nameKey(String name) {
        return name.replaceAll("[\\h·ㆍ‧∙]", "").toLowerCase(Locale.ROOT);
    }
}
