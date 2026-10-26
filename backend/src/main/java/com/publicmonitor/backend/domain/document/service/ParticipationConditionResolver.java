package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ParticipationCondition;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ParticipationGuide;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ParticipationKind;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.Preparation;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ParticipationReviewReason;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/** Source-only guide; a requirement is not a judgement about the user's company. */
final class ParticipationConditionResolver {
    private static final int MAX_TEXT = 200_000;
    private static final int MAX_BLOCK = 12_000;
    private static final String LABELS = "(?:신청|지원|참여|모집)\\h*(?:자격|대상|요건|조건)"
            + "|(?:신청|지원|참여)\\h*(?:제외(?:대상)?|제한(?:대상)?)|제외대상|결격사유|융자\\h*(?:대상|제한|제외(?:대상)?)"
            + "|(?:공통\\h*)?감점\\h*(?:사항|항목|기준)?|우대\\h*(?:사항|조건|대상|기업|항목)?|가점\\h*(?:사항|항목|기준|대상)?";
    private static final Pattern HEADING = Pattern.compile("^(" + LABELS + ")(?:\\h*[:：)）\\]>]\\h*|\\h+|$)(.*)$");
    private static final Pattern PREFIX = Pattern.compile("^(?:[□■○◦●ㅇ•*-]|[①-⑳➀-➉]|\\d+[.)]|[가-하][.)])\\h*");
    private static final Pattern BOUNDARY = Pattern.compile("^(?:사업개요|지원내용|지원규모|지원기간|사업기간|신청방법|접수방법|신청기간|신청절차"
            + "|제출서류|신청서류|구비서류|제출방법|평가방법|평가기준|평가및선정|선정절차|선정평가|선정기준|평가절차|사업내용|추진일정|사후관리|유의사항|문의처|융자범위|융자조건|융자규모|사업목적|주요과업|기타사항|공통적용사항|동시수행과제제한|정부지원연구개발비지원기준|(?:지원한도|지원비율|지원금액|지원항목|지원비용|모집규모|모집일정)(?:[:：]|$))");
    private static final Pattern BLOCKED = Pattern.compile("^(?:붙임|별첨|별지|서식|양식|작성예시|작성견본|참고자료)\\h*\\d*");
    private static final Pattern EXCLUDED_FILE = Pattern.compile("(?i)예시|견본|FAQ|참고자료|신청서|계획서|동의서|서식|양식|운영지침");
    private static final Pattern ALTERNATIVE = Pattern.compile("중\\h*(?:어느\\h*)?하나|중\\h*\\d+\\h*개\\h*이상|어느\\h*하나|택\\h*1");
    private static final Pattern ALL_REQUIRED = Pattern.compile("(?:다음|아래).{0,30}(?:모두|전부)");
    private static final Pattern ELIGIBILITY = Pattern.compile("업력|창업|매출|특허|업종|산업|중소기업|소재|자격|제외|체납|휴업|폐업|휴[·‧ㆍ]폐업|가점|우대|신청.{0,15}(?:가능|불가)");
    private ParticipationConditionResolver() {}

    static ParticipationGuide resolve(String body, List<DocumentAttachment> attachments, Preparation preparation) {
        return resolve(body, attachments, preparation, true);
    }

    record Candidates(ParticipationGuide guide, ParticipationScopeIndex scopes) {}

    static ParticipationGuide candidates(String body, List<DocumentAttachment> attachments, Preparation preparation) {
        return candidatesWithScopes(body, attachments, preparation).guide();
    }

    static Candidates candidatesWithScopes(String body, List<DocumentAttachment> attachments, Preparation preparation) {
        var raw = resolve(body, attachments, preparation, false);
        var sources = ParticipationRawSources.collectWithScopes(body, attachments);
        return new Candidates(new ParticipationGuide(raw.conditions(), sources.rules(), raw.variants(), List.of()), sources.scopes());
    }

    private static ParticipationGuide resolve(String body, List<DocumentAttachment> attachments,
                                               Preparation preparation, boolean project) {
        var result = new LinkedHashMap<String, ParticipationCondition>();
        collect(body, null, result);
        for (var file : attachments) {
            if (file.getParseStatus() == AttachmentParseStatus.COMPLETED && allowedFile(file.getFileName())) {
                collect(file.getExtractedText(), file.getFileName(), result);
            }
        }
        // Saved AI recommendations and company facts cannot establish official eligibility.
        if (preparation != null && preparation.eligibilityChecklist() != null) {
            for (var item : preparation.eligibilityChecklist()) {
                if (item == null || "RECOMMENDED".equals(item.requirementLevel()) || item.source() == null) continue;
                var source = item.source();
                String original = "NOTICE_BODY".equals(source.origin()) ? body : null;
                if ("ATTACHMENT".equals(source.origin()) && source.attachmentName() != null && allowedFile(source.attachmentName())) {
                    var matches = attachments.stream().filter(file -> file.getParseStatus() == AttachmentParseStatus.COMPLETED
                            && file.getFileName().equals(source.attachmentName())).toList();
                    if (matches.size() == 1) original = matches.getFirst().getExtractedText();
                }
                String quote = source.excerpt();
                if (original == null || quote == null || quote.isBlank() || quote.length() > MAX_BLOCK
                        || !ELIGIBILITY.matcher(quote).find()
                        || ParticipationEligibilityContext.spendingInstruction(quote)) continue;
                if (result.values().stream().anyMatch(row -> key(row.source().excerpt()).contains(key(quote))
                        || key(quote).contains(key(row.source().excerpt())))) continue;
                // Re-run section guards so a matching quote inside a form/example is not treated as a notice condition.
                String eligibleText = noticeText(original);
                if (!key(eligibleText).contains(key(quote))) continue;
                var lines = ParticipationConditionText.paragraphs(List.of(quote));
                if (lines.isEmpty()) continue;
                // A saved fragment never overrides a fuller paragraph already extracted from this source.
                if (result.values().stream().anyMatch(row -> key(row.text()).contains(key(clean(lines.getFirst()))))) continue;
                add(result, ParticipationKind.REVIEW_REQUIRED, clean(lines.getFirst()), null,
                        lines.subList(1, lines.size()), source, ParticipationReviewReason.SOURCE_CONTEXT_UNCONFIRMED);
            }
        }
        var display = new LinkedHashMap<String, ParticipationCondition>();
        var sourcedConditions = new ArrayList<ParticipationCondition>();
        for (var raw : result.values().stream()
                .map(ParticipationEligibilityContext::applicationOnly).filter(java.util.Objects::nonNull)
                .flatMap(row -> ParticipationEligibilityContext.separateFunding(row).stream()).toList()) {
            var row = ParticipationConditionText.prepare(raw);
            sourcedConditions.add(row);
            String identity = row.kind() + ":" + row.reviewReason() + ":" + key(row.text()) + ":" + key(row.scope()) + ":" + key(String.join(" ", row.details()));
            display.putIfAbsent(identity, row);
        }
        var conditions = List.copyOf(display.values());
        var texts = new ArrayList<String>();
        if (body != null) texts.add(noticeText(body));
        attachments.stream().filter(file -> file.getParseStatus() == AttachmentParseStatus.COMPLETED
                && allowedFile(file.getFileName()) && file.getExtractedText() != null)
                .forEach(file -> texts.add(noticeText(file.getExtractedText())));
        var rules = ParticipationGuidePresentation.rules(sourcedConditions);
        var expanded = new LinkedHashMap<String, ParticipationCondition>();
        collectCategories(body, null, expanded);
        attachments.stream().filter(file -> file.getParseStatus() == AttachmentParseStatus.COMPLETED && allowedFile(file.getFileName()))
                .forEach(file -> collectCategories(file.getExtractedText(), file.getFileName(), expanded));
        var categorizedConditions = new ArrayList<>(sourcedConditions);
        expanded.values().stream().map(ParticipationEligibilityContext::applicationOnly).filter(java.util.Objects::nonNull)
                .flatMap(row -> ParticipationEligibilityContext.separateFunding(row).stream())
                .map(ParticipationConditionText::prepare).filter(row -> !categorizedConditions.contains(row))
                .forEach(categorizedConditions::add);
        var categoryRules = new ArrayList<>(ParticipationGuidePresentation.rules(categorizedConditions));
        categoryRules.addAll(ParticipationCategorySources.supplement(body, attachments, categoryRules));
        return new ParticipationGuide(conditions, project ? rules : categoryRules, ParticipationGuidePresentation.variants(texts),
                project ? ParticipationCategoryProjector.project(categoryRules, ParticipationCategorySources.incomplete(body, attachments)) : List.of());
    }

    private static void collectCategories(String text, String name, LinkedHashMap<String, ParticipationCondition> result) {
        for (String document : ParticipationCategorySources.documents(text)) {
            var partial = new LinkedHashMap<String, ParticipationCondition>();
            collect(document, name, partial, true);
            result.putAll(partial);
        }
    }

    private static boolean allowedFile(String name) {
        return name != null && (!EXCLUDED_FILE.matcher(name).find() || name.matches(".*(?:공고|모집|안내).*"));
    }

    private static String noticeText(String text) {
        StringBuilder result = new StringBuilder();
        boolean blocked = false;
        var sectionContext = new ParticipationSectionContext();
        for (String raw : new ParticipationSourceLines(text.substring(0, Math.min(MAX_TEXT, text.length()))).lines()) {
            String line = raw.strip();
            if (sectionContext.excludes(line)) continue;
            if (line.startsWith("[파일:")) {
                int end = line.indexOf(']');
                blocked = end < 0 || !allowedFile(line.substring(4, end).strip());
            } else if ((BLOCKED.matcher(clean(line).replaceFirst("^[<\\[(（]\\h*", "")).find()
                    && !ParticipationEligibilityContext.referenceFootnote(line)) || line.startsWith("[일부 원문 생략")) {
                blocked = true;
            } else if (!blocked) {
                result.append(raw).append('\n');
            }
        }
        return result.toString();
    }

    private static void collect(String text, String fileName, LinkedHashMap<String, ParticipationCondition> result) {
        collect(text, fileName, result, false);
    }

    private static void collect(String text, String fileName, LinkedHashMap<String, ParticipationCondition> result, boolean categorized) {
        if (text == null || text.isBlank()) return;
        var sourceLines = new ParticipationSourceLines(text.substring(0, Math.min(MAX_TEXT, text.length())));
        String[] lines = sourceLines.lines();
        Block block = null;
        boolean blocked = false;
        var sectionContext = new ParticipationSectionContext(categorized);
        String program = "";
        String introduction = text.substring(0, Math.min(text.length(), 1800)).replaceAll("\\s+", " ");
        boolean separateBeneficiary = introduction.matches(".*주관기관.{0,40}모집.*")
                && introduction.matches(".*창업기업.{0,40}별도.{0,10}공고.*");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (sectionContext.excludes(line)) {
                emit(block, fileName, result);
                block = null;
                continue;
            }
            String fundTitle = fundTitle(line, i > 0 ? lines[i - 1].strip() : "");
            if (fundTitle != null) {
                emit(block, fileName, result);
                block = null;
                program = fundTitle;
                continue;
            }
            if (line.isBlank() || line.matches("-?\\h*\\d+\\h*-?")) {
                if (block != null) { block.lines.add(lines[i]); block.endLine = i + 1; }
                continue;
            }
            if (line.startsWith("[파일:")) {
                emit(block, fileName, result);
                block = null;
                int end = line.indexOf(']');
                blocked = end < 0 || !allowedFile(line.substring(4, end).strip());
                continue;
            }
            if (line.startsWith("[일부 원문 생략")) { block = null; blocked = true; continue; }
            if (BLOCKED.matcher(clean(line).replaceFirst("^[<\\[(（]\\h*", "")).find()
                    && !ParticipationEligibilityContext.referenceFootnote(line)) {
                emit(block, fileName, result);
                block = null;
                blocked = true;
                continue;
            }
            if (blocked) continue;
            boolean splitHeader = key(line).equals("구분") && i + 1 < lines.length
                    && key(lines[i + 1]).matches("신청자격|지원대상|자격요건|내용|세부기준");
            boolean inlineHeader = key(line).matches("구분(?:신청자격|지원대상|자격요건|내용|세부기준)");
            if (splitHeader || inlineHeader) {
                int end = i + (splitHeader ? 2 : 1);
                var rows = new ArrayList<String>();
                rows.add("구분\t" + (splitHeader ? key(lines[i + 1]) : key(line).substring(2)));
                String role = null;
                List<String> content = new ArrayList<>();
                while (end < lines.length && !lines[end].strip().matches("^[□■].*")
                        && !lines[end].strip().equals("[표]") && !lines[end].startsWith("[파일:")) {
                    String value = lines[end].strip();
                    if (BOUNDARY.matcher(clean(value).replaceFirst("^\\d+(?:-\\d+)?[.)]?\\h+", "").replaceAll("\\h+", "")).find()) break;
                    String roleValue = key(value);
                    if (roleValue.matches("공공|민간|주관|참여|공동|수요|운영|전담|영리|비영리")
                            && end + 1 < lines.length && key(lines[end + 1]).matches("기관|기업")) {
                        roleValue += key(lines[++end]);
                    }
                    if (roleValue.matches("^(?:공공|민간|주관|참여|공동|수요|운영|전담|영리|비영리)(?:기관|기업)(?:\\h*\\([^)]*\\))?$")) {
                        if (role != null) rows.add(role + "\t" + String.join(" ", content));
                        role = roleValue;
                        content.clear();
                    } else if (role != null && value.matches("^\\([^)]*\\)$") && content.isEmpty()) role += " " + value;
                    else if (!value.isBlank()) content.add(value);
                    end++;
                }
                if (role != null) rows.add(role + "\t" + String.join(" ", content));
                String quote = sourceLines.excerpt(block == null ? i : block.startLine, end);
                if (rows.size() > 1 && readTable(rows.toArray(String[]::new), block, List.of(), quote, fileName, result)) {
                    block = null;
                    i = end - 1;
                    continue;
                }
                if (block != null) {
                    emit(block, fileName, result);
                    var preceding = ParticipationConditionText.paragraphs(block.lines.stream()
                            .filter(ParticipationConditionResolver::displayLine).toList());
                    String tableScope = String.join("\n", block.audience, block.lead,
                            preceding.isEmpty() ? "" : clean(preceding.getLast())).strip();
                    add(result, ParticipationKind.REVIEW_REQUIRED, "표에서 적용 대상별 자격 요건을 구분하지 못했습니다.",
                            tableScope.isBlank() ? null : tableScope, List.of(),
                            source(fileName, block.heading, quote), ParticipationReviewReason.TABLE_MAPPING_UNCLEAR);
                    block = null;
                    i = end - 1;
                    continue;
                }
            }
            if (line.equals("[표]")) {
                int end = i + 1;
                while (end < lines.length && !lines[end].strip().equals("[/표]")
                        && !lines[end].startsWith("[파일:") && !lines[end].startsWith("[일부 원문 생략")) end++;
                if (end == lines.length || !lines[end].strip().equals("[/표]")) { block = null; i = end - 1; continue; }
                // Only complete tables with named criterion columns are interpreted as individual conditions.
                List<String> footnotes = new ArrayList<>();
                int after = end + 1;
                while (after < lines.length && (lines[after].isBlank()
                        || lines[after].strip().matches("^[*※].*"))) {
                    if (!lines[after].isBlank()) footnotes.add(lines[after]);
                    after++;
                }
                String quote = sourceLines.excerpt(block == null ? i : block.startLine, after);
                boolean conditionTable = readTable(Arrays.copyOfRange(lines, i + 1, end), block, footnotes, quote, fileName, result);
                if (!conditionTable) emit(block, fileName, result);
                block = null;
                i = after - 1;
                continue;
            }
            String candidate = clean(line).replaceFirst("^[<\\[(（]\\h*", "");
            // These headings describe funded projects or the next section, not eligible applicants.
            if (ParticipationEligibilityContext.projectHeading(candidate)
                    || (i + 1 < lines.length && lines[i + 1].strip().matches("사업|과제|기술|품목")
                        && ParticipationEligibilityContext.projectHeading(candidate + " " + lines[i + 1].strip()))
                    ) {
                emit(block, fileName, result);
                block = null;
                continue;
            }
            if (ParticipationEligibilityContext.compositionHeading(candidate)) {
                emit(block, fileName, result);
                block = new Block(ParticipationKind.MANDATORY, candidate, "", sourceLines, i, program);
                continue;
            }
            String exclusionTitle = key(candidate).replaceFirst("^\\d+[.)]?", "");
            if (exclusionTitle.matches("(?:신청|지원)대상제외(?:등유의사항|유의사항)?")) {
                emit(block, fileName, result);
                boolean hasSubtitle = Arrays.stream(lines).skip(i + 1).limit(6)
                        .anyMatch(value -> key(value).matches("[<〈]다음의경우는(?:지원|신청)대상에서제외.*"));
                block = hasSubtitle ? null : new Block(ParticipationKind.EXCLUSION, "신청 제외", "", sourceLines, i, program);
                continue;
            }
            if (key(line).matches("[<〈]다음의경우는(?:지원|신청)대상에서제외(?:처리됨|할수있음)[>〉]")) {
                emit(block, fileName, result);
                String scope = line.contains("수 있음") ? "아래 조건에 해당하면 지원 대상에서 제외될 수 있습니다." : "아래 조건에 해당하면 지원 대상에서 제외됩니다.";
                block = new Block(ParticipationKind.EXCLUSION, "신청 제외", scope, sourceLines, i, program);
                continue;
            }
            String sectionCandidate = candidate.replaceFirst("^\\d+(?:-\\d+)?[.)]?\\h+", "").replaceAll("\\h+", "");
            if (BOUNDARY.matcher(sectionCandidate).find()) {
                emit(block, fileName, result);
                block = null;
                continue;
            }
            String withoutNumber = candidate.replaceFirst("^\\d+(?:-\\d+)?[.)]?\\h+", "");
            if (HEADING.matcher(withoutNumber).matches()) candidate = withoutNumber;
            var heading = HEADING.matcher(candidate);
            if (heading.matches() && (heading.group(2).isBlank()
                    || candidate.matches("^(" + LABELS + ")\\h*[:：)）\\]>].*")
                    || line.matches("^[□■].*"))) {
                emit(block, fileName, result);
                String audience = separateBeneficiary && key(heading.group(1)).equals("지원대상")
                        ? "지원받는 창업기업의 조건 · 주관기관 자격과 별도" : program;
                block = new Block(kind(heading.group(1)), heading.group(1), heading.group(2).strip(), sourceLines, i, audience);
                continue;
            }
            if (BOUNDARY.matcher(candidate.replaceAll("\\h+", "")).find()
                    || line.matches("^[□■].*")) {
                emit(block, fileName, result);
                block = null;
                continue;
            }
            if (block != null) {
                block.lines.add(lines[i]);
                block.endLine = i + 1;
                block.length += line.length();
                if (block.length > MAX_BLOCK) { block = null; }
            }
        }
        // The final condition may have lost its exception in truncated source text.
        if (text.length() <= MAX_TEXT) emit(block, fileName, result);
    }

    private static String fundTitle(String line, String previous) {
        var inline = Pattern.compile("^\\d+(?:-\\d+)?\\h+(.{2,45}?(?:자금|융자|대출))\\h+(?:대\\h*리\\h*대\\h*출|직\\h*접\\h*대\\h*출).*").matcher(line);
        if (inline.matches()) return clean(inline.group(1));
        if (previous.matches("\\d+(?:-\\d+)?") && line.length() < 60 && line.matches(".*(?:자금|융자|대출)$")) return clean(line);
        return null;
    }

    /** A detail table belongs to the immediately preceding variant, not every earlier variant. */
    private static Block separatePrecedingConditions(Block block, String file, LinkedHashMap<String, ParticipationCondition> result) {
        if (block == null) return null;
        int family = 0, count = 0, last = -1;
        for (int i = 0; i < block.lines.size(); i++) {
            int current = bulletFamily(block.lines.get(i));
            if (family == 0 && current > 0) family = current;
            if (family > 0 && current == family) { count++; last = i; }
        }
        if (count < 2) return block;
        var earlier = new Block(block.kind, block.heading, block.lead,
                block.sourceLines, block.startLine, block.audience);
        earlier.endLine = block.endLine;
        earlier.lines.addAll(block.lines.subList(0, last));
        emit(earlier, file, result);
        var current = new Block(block.kind, block.heading, block.lead,
                block.sourceLines, block.startLine, block.audience);
        current.endLine = block.endLine;
        current.lines.addAll(block.lines.subList(last, block.lines.size()));
        return current;
    }

    private static final class Block {
        final ParticipationKind kind;
        final String heading;
        final String lead;
        final ParticipationSourceLines sourceLines;
        int endLine;
        final int startLine;
        final String audience;
        final List<String> lines = new ArrayList<>();
        int length;
        Block(ParticipationKind kind, String heading, String lead, ParticipationSourceLines sourceLines, int startLine, String audience) {
            this.kind = kind; this.heading = heading; this.lead = lead; this.sourceLines = sourceLines;
            this.startLine = startLine; this.endLine = startLine + 1; this.audience = audience;
        }
    }

    private static ParticipationKind kind(String heading) {
        if (heading.matches(".*(?:제외|제한|결격).*")) return ParticipationKind.EXCLUSION;
        if (heading.contains("감점")) return ParticipationKind.DEDUCTION;
        if (heading.matches(".*(?:우대|가점).*")) return ParticipationKind.PREFERENCE;
        return ParticipationKind.MANDATORY;
    }

    private static void emit(Block block, String file, LinkedHashMap<String, ParticipationCondition> result) {
        if (block == null || (block.lead.isBlank() && block.lines.isEmpty())) return;
        if (block.lead.matches("(?:해당\\h*)?(?:없음|없습니다)[.!]?") && block.lines.stream().noneMatch(ParticipationConditionResolver::displayLine)) return;
        if (ParticipationEligibilityContext.compositionHeading(block.heading)) {
            var row = ParticipationCompositionTable.review(source(file, block.heading,
                    block.sourceLines.excerpt(block.startLine, block.endLine)));
            result.putIfAbsent("composition-review:" + row.source(), row);
            return;
        }
        if (ParticipationScoreTables.flattened(String.join(" ", block.lines))) {
            var row = ParticipationScoreTables.review(block.kind, source(file, block.heading,
                    block.sourceLines.excerpt(block.startLine, block.endLine)));
            result.putIfAbsent("score-review:" + row.source(), row);
            return;
        }
        List<String> raw = new ArrayList<>();
        if (!block.lead.isBlank()) raw.add(block.lead);
        raw.addAll(ParticipationConditionText.paragraphs(block.lines.stream().filter(ParticipationConditionResolver::displayLine).toList()));
        String all = String.join("\n", raw);
        if (all.length() > MAX_BLOCK) return;
        // Mandatory OR alternatives remain one group; shared AND/any-exclusion scope stays on every row.
        if (grouped(block.kind, all) && raw.stream().filter(v -> v.matches("^[○◦●]\\h*\\([^)]*형\\).*")).count() < 2) {
            emitRow(block, file, raw, block.audience.isBlank() ? null : block.audience, result);
            return;
        }
        var logicalLines = ParticipationConditionText.paragraphs(block.lines.stream().filter(ParticipationConditionResolver::displayLine).toList());
        int family = 0;
        for (String line : logicalLines) {
            family = bulletFamily(line);
            if (family > 0) break;
        }
        if (block.kind == ParticipationKind.EXCLUSION && logicalLines.stream().anyMatch(v -> bulletFamily(v) == 1)) family = 1;
        if (family == 0) { emitRow(block, file, raw, block.audience.isBlank() ? null : block.audience, result); return; }
        List<String> prefix = new ArrayList<>();
        if (!block.audience.isBlank()) prefix.add(block.audience);
        if (!block.lead.isBlank()) prefix.add(block.lead);
        List<List<String>> rows = new ArrayList<>();
        List<String> current = null;
        for (String line : logicalLines) {
            if (!displayLine(line)) continue;
            if (bulletFamily(line) == family) {
                current = new ArrayList<>();
                rows.add(current);
            }
            if (current == null) prefix.add(line);
            else current.add(line);
        }
        List<String> common = new ArrayList<>();
        for (List<String> row : rows) {
            for (int i = 1; i < row.size(); i++) {
                if (row.get(i).strip().matches("^[*※]\\h*(?:공통|모든|전체|상기\\h*(?:조건|항목|요건|기준)|위의\\h*조건).*")) {
                    common.addAll(row.subList(i, row.size()));
                    row.subList(i, row.size()).clear();
                    break;
                }
            }
        }
        rows.forEach(row -> row.addAll(common));
        String scope = String.join("\n", prefix.stream().map(ParticipationConditionResolver::clean).toList());
        for (List<String> row : rows) emitRow(block, file, row, scope.isBlank() ? null : scope, result);
    }

    private static boolean grouped(ParticipationKind kind, String text) {
        return kind == ParticipationKind.EXCLUSION
                ? ALL_REQUIRED.matcher(text).find() : ALTERNATIVE.matcher(text).find();
    }

    private static boolean displayLine(String line) {
        return !line.isBlank() && !line.strip().matches("-?\\h*\\d+\\h*-?");
    }

    private static int bulletFamily(String line) {
        if (line.strip().matches("^[①-⑳➀-➉].*")) return 1;
        if (line.strip().matches("^[○◦●ㅇ]\\h+.*")) return 2;
        if (line.strip().matches("^\\d+[.)]\\h+.*")) return 3;
        if (line.strip().matches("^[-]\\h+.*")) return 4;
        if (line.strip().matches("^[•⦁]\\h+.*")) return 5;
        return 0;
    }

    private static void emitRow(Block block, String file, List<String> lines, String scope,
                                LinkedHashMap<String, ParticipationCondition> result) {
        if (lines.isEmpty()) return;
        var paragraphs = ParticipationConditionText.paragraphs(lines);
        if (paragraphs.isEmpty()) return;
        String text = clean(paragraphs.getFirst());
        if (text.isBlank()) return;
        if (block.kind == ParticipationKind.MANDATORY
                && ((paragraphs.size() == 1 && ParticipationEligibilityContext.headingFragment(text))
                    || (ParticipationEligibilityContext.spendingInstruction(text)
                        && paragraphs.stream().skip(1).noneMatch(ParticipationEligibilityContext::applicantRequirement)))) return;
        List<String> details = paragraphs.stream().skip(1).filter(v -> !v.isBlank()).toList();
        // Include the entire source block: shared scope and exceptions remain verifiable verbatim.
        String quote = block.sourceLines.excerpt(block.startLine, block.endLine);
        add(result, block.kind,
                text, scope, details, source(file, block.heading, quote));
    }

    private static boolean readTable(String[] rows, Block context, List<String> footnotes, String tableQuote, String file, LinkedHashMap<String, ParticipationCondition> result) {
        if (rows.length < 2 || tableQuote.length() > MAX_BLOCK) return false;
        if (context != null && ParticipationEligibilityContext.compositionHeading(context.heading)) {
            var evidence = source(file, context.heading, tableQuote);
            for (var row : ParticipationCompositionTable.read(rows, footnotes, evidence))
                result.putIfAbsent("composition:" + row.text() + ":" + evidence, row);
            return true;
        }
        if (ParticipationScoreTables.header(rows[0]) || ParticipationScoreTables.flattened(rows[0])) {
            var evidence = source(file, context == null ? "평가 기준" : context.heading, tableQuote);
            var scoreTable = ParticipationScoreTables.read(rows, footnotes, evidence);
            if (scoreTable == null) scoreTable = ParticipationScoreTables.review(
                    context == null ? (rows[0].contains("감점") ? ParticipationKind.DEDUCTION : ParticipationKind.PREFERENCE) : context.kind, evidence);
            result.putIfAbsent("score-table:" + evidence, scoreTable);
            return true;
        }
        String[] headers = rows[0].split("\t", -1);
        int criteria = -1, classification = -1, scopeColumn = -1;
        for (int i = 0; i < headers.length; i++) {
            String header = key(headers[i]);
            if (header.matches("조건|자격요건|신청자격|참여조건|지원조건|세부기준|세부내용|요건|기준|내용")) {
                if (criteria >= 0) return false;
                criteria = i;
            }
            if (header.matches("분류|필수여부")) classification = i;
            if (header.equals("구분")) scopeColumn = i;
        }
        if (criteria < 0 || headers.length < 2
                || Arrays.stream(rows).anyMatch(row -> row.split("\t", -1).length != headers.length)) return false;
        if (classification < 0 && scopeColumn >= 0) {
            final int column = scopeColumn;
            if (Arrays.stream(rows).skip(1).anyMatch(row -> explicitKind(key(row.split("\t", -1)[column])) != null)) {
                classification = scopeColumn;
                scopeColumn = -1;
            }
        }
        if (context == null && classification < 0) return false;
        context = separatePrecedingConditions(context, file, result);
        String scope = context == null ? null : String.join("\n", context.audience, context.lead,
                String.join(" ", ParticipationConditionText.paragraphs(context.lines).stream().map(ParticipationConditionText::unmark).toList())).strip();
        // A table of alternatives must be shown as a group, preserving the logical operator.
        if (scope != null && grouped(context.kind, scope)) {
            var groupDetails = new ArrayList<>(Arrays.stream(rows).map(row -> row.replace("\t", " · ")).toList());
            groupDetails.addAll(footnotes.stream().map(ParticipationConditionResolver::clean).toList());
            add(result, context.kind, scope, null, groupDetails,
                    source(file, context.heading, tableQuote));
            return true;
        }
        if (context != null && context.kind == ParticipationKind.MANDATORY && scopeColumn >= 0) {
            final int roleColumn = scopeColumn;
            boolean organizationRoles = Arrays.stream(rows).skip(1).allMatch(row ->
                    key(row.split("\\t", -1)[roleColumn]).matches("(?:공공|민간|주관|참여|공동|수요|운영|전담|영리|비영리)(?:기관|기업)(?:\\([^)]*\\))?"));
            if (organizationRoles) {
                emit(context, file, result);
                scope = context.audience.isBlank() ? null : context.audience;
            }
        }
        for (int r = 1; r < rows.length; r++) {
            String[] cells = rows[r].split("\t", -1);
            if (cells[criteria].isBlank() || (context == null && !ELIGIBILITY.matcher(cells[criteria]).find())) continue;
            String label = classification < 0 ? "" : key(cells[classification]);
            ParticipationKind type = switch (label) {
                case "필수", "필수조건", "신청자격", "지원대상" -> ParticipationKind.MANDATORY;
                case "우대", "우대사항", "가점", "가점사항" -> ParticipationKind.PREFERENCE;
                case "지원제외", "제외", "제외대상", "결격사유", "참여제한" -> ParticipationKind.EXCLUSION;
                default -> classification >= 0 ? ParticipationKind.REVIEW_REQUIRED
                        : context == null ? ParticipationKind.REVIEW_REQUIRED : context.kind;
            };
            // A nonempty unrecognized classification must not inherit "mandatory".
            if (classification >= 0 && !label.isEmpty()
                    && !label.matches("필수|필수조건|신청자격|지원대상|우대|우대사항|가점|가점사항|지원제외|제외|제외대상|결격사유|참여제한")
                    && context == null) continue;
            List<String> details = new ArrayList<>();
            for (int c = 0; c < cells.length; c++) {
                if (c != criteria && c != classification && c != scopeColumn && !cells[c].isBlank())
                    details.add((headers[c].isBlank() ? "" : headers[c].strip() + ": ") + cells[c].strip());
            }
            details.addAll(footnotes.stream().map(ParticipationConditionResolver::clean).toList());
            String role = scopeColumn < 0 ? "" : clean(cells[scopeColumn]);
            String rowScope = scope == null ? "" : scope;
            var clauses = ParticipationConditionText.paragraphs(List.of(cells[criteria]));
            String rowText = clean(clauses.getFirst());
            if (clauses.size() > 1) {
                if (!role.isBlank()) {
                    rowText = role + "의 신청 기준";
                    details.addAll(0, clauses);
                } else details.addAll(0, clauses.subList(1, clauses.size()));
            } else if (!role.isBlank()) rowText = role + ": " + rowText;
            add(result, type, rowText, rowScope.isBlank() ? null : rowScope, details,
                    source(file, context == null ? "참여 조건 표" : context.heading, tableQuote),
                    type == ParticipationKind.REVIEW_REQUIRED ? ParticipationReviewReason.CLASSIFICATION_UNCLEAR : null);
        }
        return true;
    }

    private static ParticipationKind explicitKind(String label) {
        return switch (label) {
            case "필수", "필수조건", "신청자격", "지원대상" -> ParticipationKind.MANDATORY;
            case "우대", "우대사항", "가점", "가점사항" -> ParticipationKind.PREFERENCE;
            case "지원제외", "제외", "제외대상", "결격사유", "참여제한" -> ParticipationKind.EXCLUSION;
            default -> null;
        };
    }

    private static RequirementSource source(String file, String heading, String quote) {
        return new RequirementSource(file == null ? "NOTICE_BODY" : "ATTACHMENT", file, heading, null, quote);
    }

    private static void add(LinkedHashMap<String, ParticipationCondition> result, ParticipationKind kind,
                            String text, String scope, List<String> details, RequirementSource source) {
        add(result, kind, text, scope, details, source, null);
    }

    private static void add(LinkedHashMap<String, ParticipationCondition> result, ParticipationKind kind,
                            String text, String scope, List<String> details, RequirementSource source,
                            ParticipationReviewReason reviewReason) {
        if (result.size() >= 80 || text.isBlank()) return;
        if (kind == ParticipationKind.MANDATORY && text.matches(".*(?:우대|가점).*")) {
            // A mixed eligibility/preference sentence cannot establish that every clause is mandatory.
            kind = ParticipationKind.REVIEW_REQUIRED;
            reviewReason = ParticipationReviewReason.MIXED_REQUIREMENT;
        }
        if (kind == ParticipationKind.PREFERENCE && (text + String.join(" ", details))
                .matches("(?s).*(?:우대|가점).{0,12}(?:없음|없습니다|미적용|미부여|부여하지|적용하지|하지 않).*")) {
            kind = ParticipationKind.REVIEW_REQUIRED;
            reviewReason = ParticipationReviewReason.PREFERENCE_NEGATED;
        }
        String identity = kind + ":" + reviewReason + ":" + key(text) + ":" + key(scope) + ":" + key(String.join("\n", details)) + ":" + source;
        String category = scope != null && scope.contains("주관기관 자격과 별도") ? "지원 대상" : category(text);
        result.putIfAbsent(identity, new ParticipationCondition(kind, category, text, scope, details, source, reviewReason));
    }

    private static String category(String text) {
        if (text.matches(".*(?:업력|창업\\h*\\d|설립.{0,8}\\d).*")) return "업력·창업";
        if (text.matches(".*(?:민간부담금|기관부담연구개발비|자부담).*")) return "자부담";
        if (text.matches(".*(?:매출|매출액).*")) return "매출";
        if (text.matches(".*(?:특허|지식재산|실용신안).*")) return "특허·지식재산";
        if (text.matches(".*(?:체납|채무|휴업|폐업|휴[·‧ㆍ]폐업|부도|파산).*")) return "경영·재무";
        if (text.matches(".*(?:소재|소재지|본사|사업장).*")) return "소재지";
        if (!text.matches(".*(?:법률|법인|기관|지주회사).*") && text.matches(".*(?:업종|산업|분야|제조|품목).*")) return "업종·분야";
        if (text.matches(".*(?:인증|확인서|인정서).*")) return "인증·자격";
        if (text.matches(".*(?:중복|기지원|수혜|수요기업).*")) return "중복 참여";
        return "참여 자격";
    }

    private static String clean(String text) {
        return PREFIX.matcher(ParticipationConditionText.unmark(text)).replaceFirst("").replaceAll("\\h+", " ").strip();
    }

    private static String key(String text) { return text == null ? "" : text.replaceAll("\\s+", ""); }
}
