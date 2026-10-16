package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.BusinessTypeSummary;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.NoticeNature;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/** Uses current notice evidence; older analyses can be supplemented without a model call on reads. */
final class BusinessTypeResolver {
    private static final Pattern HEADING = Pattern.compile(
            "^[\\h□■○◦●ㅇ•*※①②③④⑤⑥⑦⑧⑨⑩ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ\\d.()\\[\\]-]*(사업\\h*목적|지원\\h*내용|지원\\h*분야|(?:주요\\h*)?사업\\h*내용|(?:주요\\h*)?과업\\h*(?:의\\h*)?(?:(?:주요|세부)\\h*)?내용|과업\\h*(?:의\\h*)?범위|주요\\h*과업|연구\\h*(?:목표|내용(?:\\h*및\\h*범위)?|범위)|모집\\h*내용|지원\\h*항목|지원\\h*설비|지원\\h*범위|지원\\h*방식|지원\\h*규모|(?:시범\\h*)?사업\\h*개요|(?:시범\\h*)?사업\\h*목적|개\\h*념)\\h*(?:[)\\]]\\h*[:：]?|[:：]|$)");
    private static final Pattern BOUNDARY = Pattern.compile("^[□■]|^\\[참고 구간 제외\\]|^\\[파일:|^RFP\\h*번호|^[\\h\\dⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ.()\\[\\]-]*(?:지원대상|지원\\h*자격|모집대상|참여자격|지원기간|연구\\h*추진\\h*방법|연구비|연구\\h*기간|신청|접수|제출|평가|선정|지원제외|유의|문의|추진일정|사업기간|과업\\h*수행\\h*일반사항|과업\\h*지시사항|사업\\h*예산|입찰\\h*개요|제안서\\h*작성)");
    private static final Pattern FORM = Pattern.compile("^[\\h\\[<(【]*(?:붙임|별첨|별지|서식)\\h*(?:제\\h*)?\\d|^[\\h\\[<(【]*(?:작성\\h*예시|사업계획서|신청서|동의서)");
    private static final Pattern NON_NOTICE = Pattern.compile("(?i)예시|견본|FAQ|참고자료|신청서|계획서|동의서|서식|양식|협약서");
    private static final Pattern UNSUPPORTED = Pattern.compile("전년도|지난해|타\\h*사업|다른\\h*사업|작성\\h*예시|지원\\h*(?:제외|없음|불가|하지)|미지원|후속|향후|추후|연계\\h*가능|참고용|일부 원문 생략");
    private static final Pattern ACTION = Pattern.compile("지원|제공|수행|구축|도입|모집|융자|입주|개발|실증|컨설팅");
    private static final Pattern SPECULATIVE_EXPLANATION = Pattern.compile("수반될가능성|가능성이있어|가능성을시사|유형으로추정");
    private static final Pattern ADMINISTRATIVE_ACTIVITY = Pattern.compile("지원대상|신청자격|보유자격|접수마감|접수기간|제출기한|접수기한|제출마감|신청마감");
    private static final Pattern EXPLICIT_SUPPORT = Pattern.compile("(?:비용|취득비|제작비|개발비|구축비)(?:를|을)?지원|지원합니다|제공합니다|수행합니다");
    private static final Pattern DEMONSTRATION_ACTION = Pattern.compile("시범.{0,80}(?:참여|적용|허용|유지)");
    private static final Pattern INSTALLATION_ACTION = Pattern.compile("설치|교체|개선|구매|(?:설비|시설)(?:의)?투자비");
    private static final Pattern TRAINING_ACTION = Pattern.compile("코칭|교육|훈련");
    private static final Pattern ADVISORY_ACTION = Pattern.compile("조사|분석|검토|수립");
    private static final int LIMIT = 200_000;
    private BusinessTypeResolver() {}

    private record Source(String text, String raw, String name, String unavailableReason) {
        boolean available() { return unavailableReason == null && !text.isBlank(); }
        String origin() { return name == null ? "NOTICE_BODY" : "ATTACHMENT"; }
        RequirementSource quote(String excerpt, String heading) {
            return new RequirementSource(origin(), name, heading, null, excerpt);
        }
    }
    private record Candidate(BusinessType type, int score, int order, RequirementSource source) {}

    static BusinessTypeSummary resolve(String title, String body, List<DocumentAttachment> attachments,
                                       String saved, ObjectMapper mapper) {
        return resolve(title, body, attachments, saved, null, mapper);
    }

    record Resolution(BusinessTypeSummary summary, String decisionReason, String storedReason,
                      Integer rejectedSourceIndex, int usableSourceCount,
                      int failedAttachmentCount, int emptyAttachmentCount) {}
    private record Stored(BusinessTypeSummary summary, String reason, Integer sourceIndex) {}

    static BusinessTypeSummary resolve(String title, String body, List<DocumentAttachment> attachments,
                                       String saved, String documentType, ObjectMapper mapper) {
        return diagnose(title, body, attachments, saved, documentType, mapper).summary();
    }

    static Resolution diagnose(String title, String body, List<DocumentAttachment> attachments,
                               String saved, String documentType, ObjectMapper mapper) {
        var inventory = sources(body, attachments);
        var sources = inventory.stream().filter(Source::available).toList();
        var stored = stored(saved, inventory, mapper);
        var analyzed = stored.summary();
        var candidates = new ArrayList<Candidate>();
        for (var source : sources) collect(source, title, candidates);
        BusinessTypeSummary summary;
        String reason;
        var nature = GeneralNoticeClassifier.classify(title, sources.stream().map(Source::text).toList(),
                documentType, analyzed != null || !candidates.isEmpty());
        if (nature != null) {
            summary = new BusinessTypeSummary("NOT_APPLICABLE", null, null, null,
                    "현재 공고는 " + nature.label() + "입니다. 지원 유형과 구분하며 신청·접수 정보는 별도로 확인합니다.",
                    List.of(), List.of(), new NoticeNature(nature.name(), nature.label()));
            reason = nature == GeneralNoticeClassifier.Nature.CONTEST || nature == GeneralNoticeClassifier.Nature.AWARD || nature == GeneralNoticeClassifier.Nature.DESIGNATION_RECRUITMENT
                    ? nature.name() : "GENERAL_NOTICE";
        } else if (analyzed != null) {
            summary = analyzed;
            reason = "SAVED_CLASSIFICATION";
        } else if (candidates.isEmpty()) {
            summary = missing("REVIEW_REQUIRED", "현재 공고의 지원 내용에서 대표 유형을 확인하지 못했습니다. 공고 원문을 확인해 주세요.");
            reason = sources.isEmpty() ? "NO_USABLE_SOURCE" : "NO_SUPPORT_ACTIVITY";
        } else {
            summary = fromCandidates(candidates);
            reason = "RULE_CLASSIFICATION";
        }
        int failed = (int) attachments.stream().filter(file -> file.getParseStatus() == AttachmentParseStatus.FAILED).count();
        int empty = (int) attachments.stream().filter(file -> file.getExtractedText() == null || file.getExtractedText().isBlank()).count();
        return new Resolution(summary, reason, stored.reason(), stored.sourceIndex(), sources.size(), failed, empty);
    }

    private static BusinessTypeSummary fromCandidates(ArrayList<Candidate> candidates) {
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed().thenComparingInt(Candidate::order));
        var distinct = new LinkedHashMap<BusinessType, Candidate>();
        for (var candidate : candidates) distinct.putIfAbsent(candidate.type(), candidate);
        var primary = candidates.getFirst().type();
        var evidence = new ArrayList<RequirementSource>();
        var details = new ArrayList<String>();
        for (var item : distinct.values()) {
            if (!evidence.contains(item.source())) {
                if (evidence.size() == 4) continue;
                evidence.add(item.source());
            }
            if (item.type() != primary && !item.source().equals(candidates.getFirst().source()) && details.size() < 5) details.add(item.type().label() + ": " + supportDescription(item.source(), item.type().description()));
        }
        String explanation = supportDescription(candidates.getFirst().source(),
                "공고에 명시된 지원 활동을 기준으로 대표 유형을 정했습니다.");
        return new BusinessTypeSummary("CLASSIFIED", primary.name(), primary.label(), primary.description(),
                explanation, List.copyOf(details), List.copyOf(evidence));
    }

    private static String supportDescription(RequirementSource source, String fallback) {
        String text = source.excerpt().strip();
        var heading = HEADING.matcher(text);
        if (heading.find()) text = text.substring(heading.end()).strip();
        text = text.replaceFirst("^[□■○◦●ㅇ•*※\\h-]+", "").strip();
        return text.length() >= 8 && text.length() <= 300 ? text : fallback;
    }

    private static BusinessTypeSummary missing(String status, String explanation) {
        return new BusinessTypeSummary(status, null, null, null, explanation, List.of(), List.of());
    }

    private static List<Source> sources(String body, List<DocumentAttachment> attachments) {
        var result = new ArrayList<Source>();
        String rawBody = body == null ? "" : body;
        result.add(new Source(noticePart(rawBody), rawBody, null, null));
        for (var file : attachments) {
            String raw = file.getExtractedText() == null ? "" : file.getExtractedText();
            String reason = file.getParseStatus() != AttachmentParseStatus.COMPLETED ? "SOURCE_NOT_READY"
                    : NON_NOTICE.matcher(file.getFileName()).find() && !file.getFileName().matches(".*(?:공고|모집|안내).*")
                    ? "EXCLUDED_SOURCE" : null;
            result.add(new Source(reason == null ? noticePart(raw) : "", raw, file.getFileName(), reason));
        }
        return result;
    }

    private static boolean noticeHeading(String line) {
        return Pattern.compile("공고|모집\\h*안내|사업\\h*안내").matcher(line).find()
                && !NON_NOTICE.matcher(line).find() && !Pattern.compile("작성\\h*예시").matcher(line).find();
    }

    private static String noticePart(String original) {
        String text = original.substring(0, Math.min(original.length(), LIMIT));
        var members = Pattern.compile("(?m)^\\[파일: (.+?)\\]\\h*$").matcher(text).results().toList();
        if (!members.isEmpty()) {
            var selected = new ArrayList<String>();
            for (int i = 0; i < members.size(); i++) {
                var member = members.get(i);
                String name = member.group(1);
                if (NON_NOTICE.matcher(name).find() && !name.matches(".*(?:공고|모집|안내).*")) continue;
                int end = i + 1 < members.size() ? members.get(i + 1).start() : text.length();
                selected.add(member.group() + "\n" + noticePart(text.substring(member.end(), end)));
            }
            return String.join("\n", selected);
        }
        text = withoutReferenceSections(text);
        var marker = Pattern.compile("(?m)^[\\h\\[<(【]*(?:붙임|별첨|별지|서식)\\h*(?:제\\h*)?\\d|^[\\h\\[<(【]*작성\\h*예시").matcher(text);
        while (marker.find()) {
            int end = text.indexOf('\n', marker.start());
            String line = text.substring(marker.start(), end < 0 ? text.length() : end);
            if (!noticeHeading(line)) return text.substring(0, marker.start());
        }
        return text;
    }


    private static final Pattern CONTENTS_TITLE = Pattern.compile("(?m)^[\\h【\\[(]*목\\h*차[\\h】\\])]*$");
    private static final Pattern BODY_START = Pattern.compile("(?m)^\\h*(?:[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+[.\\h]+|[1-9]\\d?(?:\\.\\h*|\\h+))(?:사업|과업|연구|지원)\\h*(?:개요|목적|내용|범위|목표|안내)\\h*$");
    private static final Pattern PAGE_ENTRY = Pattern.compile("(?m)^.{1,120}\\d+\\h*$");
    private static final Pattern EXAMPLE_TITLE = Pattern.compile("(?m)^(?:[^\\r\\n]{0,80}[（(]예시[）)]|[<〈《【\\[][^\\r\\n]{0,80}예시\\h*[>〉》】\\]])\\h*$");
    private static final Pattern EXAMPLE_END = Pattern.compile("(?m)^\\h*(?:[□■]|[0-9]+[.)]\\h*|\\[파일:)");

    private static String withoutReferenceSections(String text) {
        var contents = CONTENTS_TITLE.matcher(text);
        if (contents.find()) {
            var body = BODY_START.matcher(text);
            if (body.find(contents.end()) && body.start() - contents.end() <= 10_000
                    && PAGE_ENTRY.matcher(text.substring(contents.end(), body.start())).results().count() >= 3) {
                // A paginated contents list can mention forms before the actual notice starts.
                text = text.substring(0, contents.start()) + "\n[참고 구간 제외]\n" + text.substring(body.start());
            }
        }
        var example = EXAMPLE_TITLE.matcher(text);
        var result = new StringBuilder();
        int cursor = 0;
        while (example.find()) {
            if (example.start() < cursor) continue;
            var next = EXAMPLE_END.matcher(text);
            int end = next.find(example.end()) ? next.start() : text.length();
            result.append(text, cursor, example.start()).append("\n[참고 구간 제외]\n");
            cursor = end;
        }
        return result.append(text.substring(cursor)).toString();
    }

    private static Stored rejected(String reason, Integer sourceIndex) {
        return new Stored(null, reason, sourceIndex);
    }

    private static Stored stored(String saved, List<Source> available, ObjectMapper mapper) {
        if (saved == null || saved.isBlank()) return rejected("NO_SAVED_ANALYSIS", null);
        try {
            var root = mapper.readTree(saved);
            if (!root.isObject()) return rejected("INVALID_SAVED_PAYLOAD", null);
            var value = root.path("businessType");
            if (value.isMissingNode() || value.isNull()) return rejected("NO_SAVED_CLASSIFICATION", null);
            if (!value.isObject() || !value.path("primaryType").isString()) return rejected("INVALID_SAVED_PAYLOAD", null);
            BusinessType type;
            try {
                type = BusinessType.valueOf(value.path("primaryType").asString());
            } catch (IllegalArgumentException exception) {
                return rejected("INVALID_TYPE", null);
            }
            var explanation = value.path("explanation");
            if (!explanation.isString() || explanation.asString().isBlank() || explanation.asString().length() > 500) return rejected("INVALID_EXPLANATION", null);
            var evidence = new ArrayList<RequirementSource>();
            var quotes = value.path("sources");
            if (!quotes.isArray() || quotes.isEmpty() || quotes.size() > 4) return rejected("INVALID_EVIDENCE", null);
            int index = 0;
            for (var quote : quotes) {
                String origin = quote.path("origin").asString("");
                String name = quote.path("attachmentName").isString() ? quote.path("attachmentName").asString() : null;
                String excerpt = quote.path("excerpt").asString("");
                if (compact(excerpt).length() < 5 || excerpt.length() > 1000) return rejected("INVALID_EVIDENCE", index);
                var matches = available.stream().filter(source -> source.origin().equals(origin)
                        && java.util.Objects.equals(source.name(), name)).toList();
                if (matches.isEmpty()) return rejected("SOURCE_NOT_FOUND", index);
                if (matches.size() != 1) return rejected("AMBIGUOUS_SOURCE", index);
                var source = matches.getFirst();
                if (source.unavailableReason() != null) return rejected(source.unavailableReason(), index);
                if (source.raw().isBlank()) return rejected("SOURCE_TEXT_UNAVAILABLE", index);
                if (UNSUPPORTED.matcher(excerpt).find()) return rejected("UNSUPPORTED_EVIDENCE", index);
                if (!compact(source.text()).contains(compact(excerpt))) {
                    return rejected(compact(source.raw()).contains(compact(excerpt))
                            ? "EXCLUDED_SOURCE_SECTION" : "QUOTE_MISMATCH", index);
                }
                if (!compact(source.raw()).contains(compact(excerpt))) return rejected("QUOTE_MISMATCH", index);
                evidence.add(source.quote(excerpt, null));
                index++;
            }
            var details = new ArrayList<String>();
            var clauses = value.path("details");
            if (!clauses.isArray() || clauses.size() > 5) return rejected("INVALID_DETAILS", null);
            for (var clause : clauses) {
                if (!clause.isString() || clause.asString().isBlank() || clause.asString().length() > 300) return rejected("INVALID_DETAILS", null);
                details.add(clause.asString());
            }
            if (SPECULATIVE_EXPLANATION.matcher(compact(explanation.asString())).find()) {
                return rejected("SPECULATIVE_CLASSIFICATION", null);
            }
            if (evidence.stream().noneMatch(item -> supportsActivity(type, item.excerpt()))) {
                return rejected("TYPE_ACTIVITY_MISMATCH", null);
            }
            var summary = new BusinessTypeSummary("CLASSIFIED", type.name(), type.label(), type.description(),
                    explanation.asString(), List.copyOf(details), List.copyOf(evidence));
            return new Stored(summary, "VALIDATED", null);
        } catch (RuntimeException exception) {
            return rejected("INVALID_SAVED_PAYLOAD", null);
        }
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }

    private static boolean supportsActivity(BusinessType type, String text) {
        String value = compact(text);
        if (!type.matches(value) || UNSUPPORTED.matcher(text).find()) return false;
        if (ADMINISTRATIVE_ACTIVITY.matcher(value).find()
                && !EXPLICIT_SUPPORT.matcher(value).find()) return false;
        return ACTION.matcher(value).find()
                || type == BusinessType.ADOPTION && INSTALLATION_ACTION.matcher(value).find()
                || type == BusinessType.WORKFORCE && TRAINING_ACTION.matcher(value).find()
                || type == BusinessType.ADVISORY && ADVISORY_ACTION.matcher(value).find()
                || type == BusinessType.DEMONSTRATION && DEMONSTRATION_ACTION.matcher(value).find();
    }


    private static boolean endsSupportSection(String line, String heading, int rank) {
        if (!BOUNDARY.matcher(line).find()) return false;
        // A box can introduce a support sentence, not just a new section heading.
        if (heading == null || rank < 200 || !line.matches("^[□■].*")) return true;
        String content = line.replaceFirst("^[□■]\\h*", "");
        if (content.matches("^(?:신청|접수|제출|지원\\h*대상|지원\\h*자격|참여\\h*자격|참고|유의|문의|평가|선정\\h*기준|추진\\h*절차).*")
                || UNSUPPORTED.matcher(content).find()) return true;
        for (var type : BusinessType.values()) {
            if (supportsActivity(type, content)) return false;
        }
        return true;
    }

    private static void collect(Source source, String title, List<Candidate> result) {
        var sourceLines = new ParticipationSourceLines(source.text());
        String[] lines = sourceLines.lines();
        String heading = null;
        int rank = 0, offset = 0, count = 0;
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String physical = lines[lineIndex];
            String line = physical.strip();
            if (FORM.matcher(line).find() && !noticeHeading(line)) break;
            var match = HEADING.matcher(line);
            boolean headingLine = match.find();
            if (headingLine) {
                heading = match.group(1).replaceAll("\\h+", "");
                rank = heading.matches("지원규모|지원내용|지원분야|지원항목|지원설비|지원범위|지원방식|(?:주요)?사업내용|(?:주요)?과업(?:의)?(?:주요|세부)?내용|과업(?:의)?범위|주요과업|연구(?:내용(?:및범위)?|범위)|모집내용") ? 200 : 100;
                count = 0;
            } else if (endsSupportSection(line, heading, rank)) heading = null;
            if (heading != null && ++count < 35 && line.length() <= 1000 && line.length() >= 8
                    && !UNSUPPORTED.matcher(line).find()) {
                // Split current support from attached headings in flattened PDFs.
                String content = headingLine ? line.substring(match.end()).strip() : line;
                var next = Pattern.compile("[□■]\\h*[가-힣]+\\h*[:：]").matcher(content);
                if (next.find()) content = content.substring(0, next.start());
                String excerpt = line;
                for (int nextLine = lineIndex + 1; nextLine < Math.min(lines.length, lineIndex + 3); nextLine++) {
                    String following = lines[nextLine].strip();
                    if (HEADING.matcher(following).find() || endsSupportSection(following, heading, rank)
                            || FORM.matcher(following).find() || UNSUPPORTED.matcher(following).find()
                            || excerpt.length() + following.length() + 1 > 700) break;
                    excerpt = sourceLines.excerpt(lineIndex, nextLine + 1).strip();
                }
                for (var type : BusinessType.values()) {
                    String evidence = supportsActivity(type, content) ? line : excerpt;
                    if (!supportsActivity(type, headingLine ? evidence.substring(match.end()).strip() : evidence)) continue;
                    int score = rank + (title != null && type.matches(title) ? 20 : 0);
                    result.add(new Candidate(type, score, offset + type.position(evidence), source.quote(evidence, heading)));
                }
            }
            offset += physical.length() + 1;
        }
    }
}
