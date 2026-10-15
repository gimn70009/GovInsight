package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.BusinessTypeSummary;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/** Uses current notice evidence; older analyses can be supplemented without a model call on reads. */
final class BusinessTypeResolver {
    private static final Pattern HEADING = Pattern.compile(
            "^[\\h□■○◦●ㅇ•*※\\d.()\\[\\]-]*(사업\\h*목적|지원\\h*내용|지원\\h*분야|사업\\h*내용|과업\\h*내용|주요\\h*과업|모집\\h*내용|지원\\h*항목|지원\\h*규모|사업\\h*개요)\\h*(?:[)\\]]\\h*[:：]?|[:：]|$)");
    private static final Pattern BOUNDARY = Pattern.compile("^[□■]|^[\\h\\d.()\\[\\]-]*(?:지원대상|모집대상|참여자격|지원기간|신청|접수|제출|평가|선정|지원제외|유의|문의|추진일정|사업기간)");
    private static final Pattern FORM = Pattern.compile("^[\\h\\[<(【]*(?:붙임|별첨|별지|서식)\\h*(?:제\\h*)?\\d|^[\\h\\[<(【]*(?:작성\\h*예시|사업계획서|신청서|동의서)");
    private static final Pattern NON_NOTICE = Pattern.compile("(?i)예시|견본|FAQ|참고자료|신청서|계획서|동의서|서식|양식|협약서");
    private static final Pattern UNSUPPORTED = Pattern.compile("전년도|지난해|타\\h*사업|다른\\h*사업|작성\\h*예시|지원\\h*(?:제외|없음|불가|하지)|미지원|후속|향후|추후|연계\\h*가능|참고용|일부 원문 생략");
    private static final Pattern ACTION = Pattern.compile("지원|제공|수행|구축|도입|모집|융자|입주|개발|실증|컨설팅");
    private static final int LIMIT = 200_000;
    private BusinessTypeResolver() {}

    private record Source(String text, String name) {
        String origin() { return name == null ? "NOTICE_BODY" : "ATTACHMENT"; }
        RequirementSource quote(String excerpt, String heading) {
            return new RequirementSource(origin(), name, heading, null, excerpt);
        }
    }
    private record Candidate(BusinessType type, int score, int order, RequirementSource source) {}

    static BusinessTypeSummary resolve(String title, String body, List<DocumentAttachment> attachments,
                                       String saved, ObjectMapper mapper) {
        if (ordinary(title)) return missing("NOT_APPLICABLE", "선정 결과나 일반 안내 게시글에는 지원사업 유형을 붙이지 않습니다.");
        var sources = sources(body, attachments);
        var analyzed = stored(saved, sources, mapper);
        if (analyzed != null) return analyzed;
        var candidates = new ArrayList<Candidate>();
        for (var source : sources) collect(source, title, candidates);
        if (candidates.isEmpty()) return missing("REVIEW_REQUIRED", "현재 공고의 지원 내용에서 대표 유형을 확인하지 못했습니다. 공고 원문을 확인해 주세요.");
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

    private static boolean ordinary(String title) {
        if (title == null) return false;
        if (title.matches(".*(?:추가\\s*모집|재공모|신규\\s*모집).*")) return false;
        return title.matches(".*(?:선정|평가|심사|합격|입찰).{0,10}결과.*|.*(?:보도자료|행정예고|입법예고|정책\\s*발표|설명회\\s*개최|시스템\\s*점검).*"
                + "|.*(?:기간제|공무직|직원|연구원)\\s*채용.*");
    }

    private static List<Source> sources(String body, List<DocumentAttachment> attachments) {
        var result = new ArrayList<Source>();
        if (body != null && !body.isBlank()) result.add(new Source(noticePart(body), null));
        for (var file : attachments) {
            if (file.getParseStatus() != AttachmentParseStatus.COMPLETED || file.getExtractedText() == null
                    || (NON_NOTICE.matcher(file.getFileName()).find() && !file.getFileName().matches(".*(?:공고|모집|안내).*"))) continue;
            result.add(new Source(noticePart(file.getExtractedText()), file.getFileName()));
        }
        return result;
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
        var marker = Pattern.compile("(?m)^[\\h\\[<(【]*(?:붙임|별첨|별지|서식)\\h*(?:제\\h*)?\\d|^[\\h\\[<(【]*작성\\h*예시").matcher(text);
        return marker.find() ? text.substring(0, marker.start()) : text;
    }

    private static BusinessTypeSummary stored(String saved, List<Source> available, ObjectMapper mapper) {
        if (saved == null || saved.isBlank()) return null;
        try {
            var value = mapper.readTree(saved).path("businessType");
            if (!value.isObject() || !value.path("primaryType").isString()) return null;
            var type = BusinessType.valueOf(value.path("primaryType").asString());
            var explanation = value.path("explanation");
            if (!explanation.isString() || explanation.asString().isBlank() || explanation.asString().length() > 500) return null;
            var evidence = new ArrayList<RequirementSource>();
            var quotes = value.path("sources");
            if (!quotes.isArray() || quotes.isEmpty() || quotes.size() > 4) return null;
            for (var quote : quotes) {
                String origin = quote.path("origin").asString("");
                String name = quote.path("attachmentName").isString() ? quote.path("attachmentName").asString() : null;
                String excerpt = quote.path("excerpt").asString("");
                if (excerpt.strip().length() < 5 || excerpt.length() > 1000 || UNSUPPORTED.matcher(excerpt).find()) return null;
                var matches = available.stream().filter(source -> source.origin().equals(origin)
                        && java.util.Objects.equals(source.name(), name)).toList();
                if (matches.size() != 1 || !compact(matches.getFirst().text()).contains(compact(excerpt))) return null;
                evidence.add(matches.getFirst().quote(excerpt, null));
            }
            var details = new ArrayList<String>();
            var clauses = value.path("details");
            if (!clauses.isArray() || clauses.size() > 5) return null;
            for (var clause : clauses) {
                if (!clause.isString() || clause.asString().isBlank() || clause.asString().length() > 300) return null;
                details.add(clause.asString());
            }
            return new BusinessTypeSummary("CLASSIFIED", type.name(), type.label(), type.description(),
                    explanation.asString(), List.copyOf(details), List.copyOf(evidence));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }

    private static void collect(Source source, String title, List<Candidate> result) {
        String[] lines = new ParticipationSourceLines(source.text()).lines();
        String heading = null;
        int rank = 0, offset = 0, count = 0;
        for (String physical : lines) {
            String line = physical.strip();
            if (FORM.matcher(line).find()) break;
            var match = HEADING.matcher(line);
            boolean headingLine = match.find();
            if (headingLine) {
                heading = match.group(1).replaceAll("\\h+", "");
                rank = heading.matches("지원내용|지원분야|지원항목|과업내용|주요과업|모집내용") ? 200 : 100;
                count = 0;
            } else if (BOUNDARY.matcher(line).find()) heading = null;
            if (heading != null && ++count < 35 && line.length() <= 1000 && line.length() >= 8
                    && !UNSUPPORTED.matcher(line).find() && ACTION.matcher(line).find()) {
                // Split current support from attached headings in flattened PDFs.
                String content = headingLine ? line.substring(match.end()).strip() : line;
                var next = Pattern.compile("[□■]\\h*[가-힣]+\\h*[:：]").matcher(content);
                if (next.find()) content = content.substring(0, next.start());
                for (var type : BusinessType.values()) {
                    if (!type.matches(content)) continue;
                    int score = rank + (title != null && type.matches(title) ? 20 : 0);
                    result.add(new Candidate(type, score, offset + type.position(content), source.quote(line, heading)));
                }
            }
            offset += physical.length() + 1;
        }
    }
}
