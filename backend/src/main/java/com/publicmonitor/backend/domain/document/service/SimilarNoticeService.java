package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionResponseCode;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.web.dto.SimilarNoticeResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Lazy
@RequiredArgsConstructor
public class SimilarNoticeService {

    private static final int MAX_RESULTS = 3;
    private static final double MIN_COSINE_SIMILARITY = 0.78;
    private static final Set<String> GENERIC_TOPIC_TERMS = Set.of(
            "공고", "사업", "지원", "지원사업", "프로그램", "과제", "대상", "기업", "기관",
            "시행", "시행계획", "계획", "신청", "참여", "모집", "선정", "정부", "산업",
            "기술", "개발", "구축", "혁신", "사업화", "연구개발", "중소기업", "중견기업",
            "연도", "년도", "신규", "하반기", "상반기"
    );
    private static final Set<String> GENERIC_TOPIC_PREFIXES = Set.of(
            "공고", "사업", "지원", "프로그램", "과제", "대상", "신청", "참여", "모집", "선정"
    );
    private static final Pattern MONEY_PATTERN = Pattern.compile(
            "((?:총\\s*)?(?:최대\\s*)?[0-9][0-9,.]*\\s*(?:억|천만|백만)?\\s*원)"
    );
    private static final Pattern PURPOSE_HEADING = Pattern.compile(
            "^(?:[ⅠⅡⅢIVX0-9.\\- ]*)사업\\s*목적(?:\\s*[:：])?\\s*(.*)$"
    );
    private static final Pattern NEXT_SECTION_HEADING = Pattern.compile(
            "^(?:[ⅠⅡⅢIVX0-9.\\- ]*)?(?:사업\\s*(?:내용|구분)|지원\\s*(?:규모|내용|분야|대상)|사업비|추진\\s*(?:방향|체계)|신청\\s*자격).*$"
    );
    private static final Pattern INLINE_PURPOSE_SECTION = Pattern.compile(
            "(?:[0-9]+[.\\-]?\\s*)?사업\\s*목적\\s*(?:[○ㅇ□●▪▶※*\\-]+\\s*)?(.+?)(?=(?:[0-9]+[.\\-]?\\s*)?(?:사업\\s*(?:내용|구분)|지원\\s*(?:규모|내용|분야|대상)|사업비|추진\\s*(?:방향|체계)))"
    );
    private static final Pattern PARTNER_CONDITION = Pattern.compile(
            "([^.!?]{0,180}(?:컨소시엄\\s*(?:을\\s*)?구성|파트너\\s*(?:기관)?\\s*(?:필수|참여|확보)|공동연구개발기관\\s*(?:참여\\s*)?필수)[^.!?]{0,180}[.!?]?)"
    );

    private final DocumentDetectionRepository detectionRepository;
    private final DocumentAnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public SimilarNoticeResponse find(Long detectionId) {
        DocumentDetection detection = detectionRepository.findById(detectionId)
                .orElseThrow(() -> new DocumentDetectionException(DocumentDetectionResponseCode.NOT_FOUND));
        DocumentVersion currentVersion = detection.getDocumentVersion();
        DocumentAnalysis currentAnalysis = analysisRepository.findByDocumentVersionId(currentVersion.getId())
                .orElse(null);
        SimilarNoticeResponse.ComparisonSide currentSide = side(currentVersion, currentAnalysis);
        if (currentAnalysis == null) {
            return new SimilarNoticeResponse(currentSide, List.of());
        }
        List<Double> currentEmbedding = embedding(currentAnalysis);
        if (currentEmbedding.isEmpty()) {
            return new SimilarNoticeResponse(currentSide, List.of());
        }

        List<DocumentAnalysis> latestAnalyses = analysisRepository.findAll().stream()
                .filter(candidate -> !candidate.getDocumentVersion().getDocument().getId()
                        .equals(currentVersion.getDocument().getId()))
                .collect(java.util.stream.Collectors.toMap(
                        candidate -> candidate.getDocumentVersion().getDocument().getId(),
                        candidate -> candidate,
                        (left, right) -> left.getDocumentVersion().getVersionNo()
                                >= right.getDocumentVersion().getVersionNo() ? left : right
                )).values().stream().toList();

        List<ScoredCandidate> scored = new ArrayList<>();
        for (DocumentAnalysis candidate : latestAnalyses) {
            List<Double> candidateEmbedding = embedding(candidate);
            if (!sameEmbeddingModel(currentAnalysis, candidate)
                    || candidateEmbedding.size() != currentEmbedding.size()) {
                continue;
            }
            double similarity = cosine(currentEmbedding, candidateEmbedding);
            List<String> sharedTopics = sharedTopicTerms(
                    currentVersion,
                    currentAnalysis,
                    candidate.getDocumentVersion(),
                    candidate
            );
            List<String> sharedTitleTopics = sharedTitleTopicTerms(
                    currentVersion,
                    candidate.getDocumentVersion()
            );
            boolean strongTitleMatch = sharedTitleTopics.stream()
                    .anyMatch(term -> term.length() >= 6);
            if (!sharedTopics.isEmpty()
                    && (similarity >= MIN_COSINE_SIMILARITY || strongTitleMatch)) {
                List<String> reasons = strongTitleMatch ? sharedTitleTopics : sharedTopics;
                int score = (int) Math.round(similarity * 100);
                scored.add(new ScoredCandidate(
                        candidate,
                        strongTitleMatch ? Math.max(score, 85) : score,
                        reasons
                ));
            }
        }

        List<SimilarNoticeResponse.SimilarNotice> matches = scored.stream()
                .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed())
                .limit(MAX_RESULTS)
                .map(candidate -> toResponse(currentVersion, currentAnalysis, candidate))
                .toList();
        return new SimilarNoticeResponse(currentSide, matches);
    }

    private SimilarNoticeResponse.SimilarNotice toResponse(
            DocumentVersion currentVersion,
            DocumentAnalysis currentAnalysis,
            ScoredCandidate scored
    ) {
        DocumentVersion candidateVersion = scored.analysis().getDocumentVersion();
        DocumentDetection latestDetection = detectionRepository
                .findTopByDocumentIdOrderByDetectedAtDescIdDesc(candidateVersion.getDocument().getId())
                .orElseThrow(() -> new DocumentDetectionException(DocumentDetectionResponseCode.NOT_FOUND));
        return new SimilarNoticeResponse.SimilarNotice(
                latestDetection.getId(),
                scored.score(),
                candidateVersion.getTitle(),
                candidateVersion.getDocument().getOriginalUrl(),
                side(candidateVersion, scored.analysis()),
                commonPoints(scored.sharedTopics()),
                "기존 공고에서 정리한 사업 목표, 기술 구성과 참여기관 역할을 참고할 수 있습니다. 신청 내용은 새 공고에 맞게 다시 작성해야 합니다.",
                legalReview(currentAnalysis, scored.analysis(), scored.sharedTopics())
        );
    }

    private SimilarNoticeResponse.LegalReview legalReview(
            DocumentAnalysis currentAnalysis,
            DocumentAnalysis candidateAnalysis,
            List<String> sharedTopics
    ) {
        List<SimilarNoticeResponse.LegalRiskCheck> checks = List.of(
                legalRiskCheck("DUPLICATE_SUPPORT", "중복지원", currentAnalysis, candidateAnalysis, sharedTopics),
                legalRiskCheck("COST_DOUBLE_COUNTING", "사업비·인건비 중복계상", currentAnalysis, candidateAnalysis, sharedTopics),
                legalRiskCheck("RESULT_IP_REUSE", "성과물·지식재산 재사용", currentAnalysis, candidateAnalysis, sharedTopics),
                legalRiskCheck("CONFIDENTIALITY", "비밀정보·영업비밀", currentAnalysis, candidateAnalysis, sharedTopics),
                legalRiskCheck("PROPOSAL_TEXT_REUSE", "제안서 문장·자료 재사용", currentAnalysis, candidateAnalysis, sharedTopics)
        );
        List<String> restrictedLabels = checks.stream()
                .filter(check -> check.status().equals("HIGH"))
                .map(SimilarNoticeResponse.LegalRiskCheck::label)
                .toList();
        String topic = topicPhrase(sharedTopics);
        String summary = restrictedLabels.isEmpty()
                ? "두 공고의 " + topic + " 관련 원문에서 명시적인 중복·재사용 제한을 확인하지 못했습니다. 제한이 없다는 뜻은 아니므로 실제 신청 범위는 담당기관에 확인해야 합니다."
                : "두 공고의 " + topic + " 관련 원문을 비교한 결과, "
                        + String.join(", ", restrictedLabels) + " 항목에서 명시적인 제한을 확인했습니다.";
        return new SimilarNoticeResponse.LegalReview(
                restrictedLabels.isEmpty() ? "REVIEW_REQUIRED" : "HIGH", summary, checks,
                "공고 원문 기반의 사전 위험 점검이며 법률 자문이 아닙니다. 최종 신청 전 공고 담당기관과 법무·재무 담당자의 확인이 필요합니다."
        );
    }

    private SimilarNoticeResponse.LegalRiskCheck legalRiskCheck(
            String type, String label, DocumentAnalysis currentAnalysis,
            DocumentAnalysis candidateAnalysis, List<String> sharedTopics
    ) {
        LegalFinding current = legalFinding(currentAnalysis, type);
        LegalFinding candidate = legalFinding(candidateAnalysis, type);
        boolean restrictionFound = "RESTRICTION_FOUND".equals(current.status())
                || "RESTRICTION_FOUND".equals(candidate.status());
        String finding = "현재 공고: " + current.summary() + " 유사 공고: " + candidate.summary();
        List<String> evidenceParts = new ArrayList<>();
        if (!current.evidence().isBlank()) evidenceParts.add("현재 공고: “" + current.evidence() + "”");
        if (!candidate.evidence().isBlank()) evidenceParts.add("유사 공고: “" + candidate.evidence() + "”");
        return new SimilarNoticeResponse.LegalRiskCheck(
                type, label, restrictionFound ? "HIGH" : "REVIEW_REQUIRED", finding,
                String.join(" ", evidenceParts), pairAction(type, current, candidate, sharedTopics)
        );
    }

    private String pairAction(String type, LegalFinding current, LegalFinding candidate, List<String> sharedTopics) {
        String topic = topicPhrase(sharedTopics);
        String basis = restrictionBasis(current, candidate);
        return switch (type) {
            case "DUPLICATE_SUPPORT" -> "두 신청서에서 " + topic + " 과제의 목적·수행 범위·산출물이 어떻게 다른지 표로 구분하고, " + basis + "에 해당하는지 양쪽 공고 담당기관에 문의합니다.";
            case "COST_DOUBLE_COUNTING" -> topic + " 수행에 투입하는 인력·기간·장비·실증비를 사업별 산정표로 분리하고, " + basis + "에 저촉되는 비용이 없는지 재무 담당자와 확인합니다.";
            case "RESULT_IP_REUSE" -> topic + " 관련 기존 성과물의 소유자와 사용권을 정리하고, 새 과제에서 재사용할 결과물과 새로 개발할 결과물을 구분해 " + basis + " 충족 여부를 확인합니다.";
            case "CONFIDENTIALITY" -> topic + " 관련 제안서에 포함할 데이터·도면·실증 결과를 공개 가능 자료와 비공개 자료로 나누고, " + basis + "에 따라 사전 동의가 필요한 자료를 법무 담당자와 확인합니다.";
            case "PROPOSAL_TEXT_REUSE" -> topic + " 관련 기존 제안서의 문장·표·도표별 권리자를 확인하고, 그대로 재사용하지 말고 새 공고의 목적과 평가항목에 맞게 다시 작성한 뒤 " + basis + " 충족 여부를 검토합니다.";
            default -> "두 공고의 " + topic + " 관련 범위와 " + basis + "을 담당기관에 확인합니다.";
        };
    }

    private String restrictionBasis(LegalFinding current, LegalFinding candidate) {
        List<String> parts = new ArrayList<>();
        if ("RESTRICTION_FOUND".equals(current.status())) parts.add("현재 공고의 " + findingReference(current));
        if ("RESTRICTION_FOUND".equals(candidate.status())) parts.add("유사 공고의 " + findingReference(candidate));
        if (!parts.isEmpty()) return String.join("과 ", parts);
        if ("CAUTION".equals(current.status())) return "현재 공고의 사전 승인·권리 확인 조건";
        if ("CAUTION".equals(candidate.status())) return "유사 공고의 사전 승인·권리 확인 조건";
        return "원문에 별도로 기재된 세부 집행·권리 조건";
    }

    private String findingReference(LegalFinding finding) {
        return finding.evidence().isBlank() ? "제한 조건" : "‘" + finding.evidence() + "’ 조항";
    }

    private String topicPhrase(List<String> sharedTopics) {
        List<String> topics = sharedTopics.stream().limit(3).toList();
        return topics.isEmpty() ? "공통 사업" : String.join("·", topics);
    }
    private LegalFinding legalFinding(DocumentAnalysis analysis, String type) {
        if (analysis == null || analysis.getComparisonSummary() == null) return LegalFinding.missing();
        try {
            JsonNode risks = objectMapper.readTree(analysis.getComparisonSummary()).path("legalRisks");
            if (risks.isArray()) {
                for (JsonNode risk : risks) {
                    if (!type.equals(risk.path("type").asText())) continue;
                    String status = risk.path("status").asText();
                    String summary = risk.path("summary").asText().strip();
                    String evidence = risk.path("evidenceExcerpt").asText().strip();
                    if (!summary.isBlank()) return new LegalFinding(status, summary, evidence);
                }
            }
        } catch (RuntimeException ignored) {
            // 과거 또는 손상된 분석 데이터는 안전하게 추가 확인 상태로 처리합니다.
        }
        return LegalFinding.missing();
    }
    private String commonPoints(List<String> sharedTopics) {
        String terms = String.join(", ", sharedTopics.stream().limit(3).toList());
        return "두 공고는 " + terms + " 분야와 관련된 사업 목적 및 핵심 과업이 함께 확인됩니다.";
    }

    private List<String> sharedTopicTerms(
            DocumentVersion currentVersion,
            DocumentAnalysis currentAnalysis,
            DocumentVersion candidateVersion,
            DocumentAnalysis candidateAnalysis
    ) {
        Set<String> current = topicTerms(currentVersion, currentAnalysis);
        Set<String> candidate = topicTerms(candidateVersion, candidateAnalysis);
        Set<String> shared = new LinkedHashSet<>();
        for (String currentTerm : current) {
            for (String candidateTerm : candidate) {
                String common = sharedTopicTerm(currentTerm, candidateTerm);
                if (common != null) shared.add(common);
                if (shared.size() == 5) return List.copyOf(shared);
            }
        }
        return List.copyOf(shared);
    }

    private List<String> sharedTitleTopicTerms(
            DocumentVersion currentVersion,
            DocumentVersion candidateVersion
    ) {
        return sharedTerms(
                topicTerms(currentVersion.getTitle(), ""),
                topicTerms(candidateVersion.getTitle(), "")
        );
    }

    private List<String> sharedTerms(Set<String> current, Set<String> candidate) {
        Set<String> shared = new LinkedHashSet<>();
        for (String currentTerm : current) {
            for (String candidateTerm : candidate) {
                String common = sharedTopicTerm(currentTerm, candidateTerm);
                if (common != null) shared.add(common);
                if (shared.size() == 5) return List.copyOf(shared);
            }
        }
        return List.copyOf(shared);
    }

    private String sharedTopicTerm(String left, String right) {
        if (left.equals(right)) return left;
        if (left.length() < 4 || right.length() < 4) return null;
        if (left.contains(right)) return right;
        if (right.contains(left)) return left;
        return null;
    }

    private Set<String> topicTerms(DocumentVersion version, DocumentAnalysis analysis) {
        ComparisonFields fields = comparisonFields(analysis);
        String content = version.getContentText() == null ? "" : version.getContentText();
        String purpose = fields == null ? purpose(content, analysis) : fields.purpose();
        String organization = version.getDocument().getMonitoringSource().getOrganizationName();
        return topicTerms(version.getTitle() + " " + purpose, organization);
    }

    private Set<String> topicTerms(String value, String organization) {
        String source = (organization == null || organization.isBlank()
                ? value
                : value.replace(organization, " ")).toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        for (String raw : source.split("[^0-9a-z가-힣]+")) {
            String term = canonicalTopicTerm(raw);
            if (term.length() >= 2 && !term.matches("20[0-9]{2}(?:년|년도)?")
                    && !isGenericTopicTerm(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private String canonicalTopicTerm(String term) {
        return switch (term) {
            case "인공지능" -> "ai";
            case "샌드박스" -> "규제샌드박스";
            default -> term;
        };
    }

    private boolean isGenericTopicTerm(String term) {
        return GENERIC_TOPIC_TERMS.contains(term)
                || GENERIC_TOPIC_PREFIXES.stream().anyMatch(term::startsWith);
    }

    private SimilarNoticeResponse.ComparisonSide side(DocumentVersion version, DocumentAnalysis analysis) {
        String content = version.getContentText() == null ? "" : version.getContentText();
        ComparisonFields comparison = comparisonFields(analysis);
        return new SimilarNoticeResponse.ComparisonSide(
                version.getDocument().getMonitoringSource().getOrganizationName(),
                comparison == null ? purpose(content, analysis) : comparison.purpose(),
                comparison == null
                        ? firstMatch(MONEY_PATTERN, content, "원문에서 지원 규모를 확인하지 못했습니다.")
                        : comparison.supportScale(),
                comparison == null
                        ? proposalValue(analysis, "applicationDeadline", "원문에서 접수 마감을 확인하지 못했습니다.")
                        : comparison.applicationDeadline(),
                comparison == null ? eligibility(analysis) : comparison.eligibility(),
                comparison == null ? requiredPartner(content) : comparison.requiredPartner()
        );
    }

    private ComparisonFields comparisonFields(DocumentAnalysis analysis) {
        if (analysis == null || analysis.getComparisonSummary() == null) return null;
        try {
            JsonNode summary = objectMapper.readTree(analysis.getComparisonSummary());
            String purpose = summary.path("purpose").asText().strip();
            String supportScale = summary.path("supportScale").asText().strip();
            String applicationDeadline = summary.path("applicationDeadline").asText().strip();
            String eligibility = summary.path("eligibility").asText().strip();
            String requiredPartner = summary.path("requiredPartner").asText().strip();
            if (purpose.isBlank() || supportScale.isBlank() || applicationDeadline.isBlank()
                    || eligibility.isBlank() || requiredPartner.isBlank()) {
                return null;
            }
            return new ComparisonFields(
                    purpose, supportScale, applicationDeadline, eligibility, requiredPartner
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String purpose(String content, DocumentAnalysis analysis) {
        Matcher inlineSection = INLINE_PURPOSE_SECTION.matcher(normalizeText(content));
        if (inlineSection.find()) return cleanSourceLine(inlineSection.group(1));

        List<String> purposeLines = new ArrayList<>();
        boolean collecting = false;
        for (String rawLine : content.split("\\R")) {
            String line = normalizeText(rawLine);
            if (line.isBlank()) continue;
            if (!collecting) {
                Matcher heading = PURPOSE_HEADING.matcher(line);
                if (!heading.matches()) continue;
                collecting = true;
                String inlinePurpose = cleanSourceLine(heading.group(1));
                if (!inlinePurpose.isBlank()) purposeLines.add(inlinePurpose);
                continue;
            }
            if (NEXT_SECTION_HEADING.matcher(line).matches() || line.matches("^[0-9]+[.)]$")) break;
            String cleaned = cleanSourceLine(line);
            if (!cleaned.isBlank()) purposeLines.add(cleaned);
        }
        if (!purposeLines.isEmpty()) return String.join(" ", purposeLines);
        return analysis == null ? "사업 목적을 확인하지 못했습니다." : normalizeText(analysis.getSummary());
    }

    private String requiredPartner(String content) {
        Matcher condition = PARTNER_CONDITION.matcher(normalizeText(content));
        if (condition.find()) return cleanSourceLine(condition.group(1));

        String best = "";
        int bestScore = 0;
        for (String rawLine : content.split("\\R")) {
            String line = cleanSourceLine(normalizeText(rawLine));
            if (line.isBlank() || line.length() > 500) continue;
            int score = 0;
            if (line.contains("컨소시엄")) score += 4;
            if (line.contains("파트너")) score += 4;
            if (line.matches(".*(?:공동연구개발기관|공동기관|참여기관).*")) score += 2;
            if (line.matches(".*(?:필수|구성|참여해야|참여하여야).*")) score += 2;
            if (score > bestScore) {
                best = line;
                bestScore = score;
            }
        }
        return bestScore >= 4 ? best : "원문에서 필수 파트너 조건을 확인하지 못했습니다.";
    }

    private String cleanSourceLine(String value) {
        return value
                .strip()
                .replaceFirst("^[○ㅇ□●▪▶※*\\-]+\\s*", "")
                .replaceFirst("^(?:[0-9]+[.\\-]?\\s*)?사업\\s*내용\\s*", "")
                .strip();
    }

    private String eligibility(DocumentAnalysis analysis) {
        if (analysis == null || analysis.getProposalDirection() == null) {
            return "신청 자격을 확인하지 못했습니다.";
        }
        try {
            JsonNode items = objectMapper.readTree(analysis.getProposalDirection())
                    .path("preparation").path("eligibilityChecklist");
            List<String> titles = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    if (!item.path("title").asText().isBlank()) titles.add(item.path("title").asText());
                    if (titles.size() == 2) break;
                }
            }
            return titles.isEmpty() ? "신청 자격을 확인하지 못했습니다." : String.join(", ", titles);
        } catch (RuntimeException exception) {
            return "신청 자격을 확인하지 못했습니다.";
        }
    }

    private String proposalValue(DocumentAnalysis analysis, String field, String fallback) {
        if (analysis == null || analysis.getProposalDirection() == null) return fallback;
        try {
            String value = objectMapper.readTree(analysis.getProposalDirection())
                    .path("preparation").path(field).asText();
            return value.isBlank() ? fallback : value;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private List<Double> embedding(DocumentAnalysis analysis) {
        if (analysis.getSimilarityEmbedding() == null) return List.of();
        try {
            JsonNode values = objectMapper.readTree(analysis.getSimilarityEmbedding());
            if (!values.isArray()) return List.of();
            List<Double> result = new ArrayList<>();
            for (JsonNode value : values) result.add(value.asDouble());
            return result;
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private boolean sameEmbeddingModel(DocumentAnalysis left, DocumentAnalysis right) {
        return left.getEmbeddingModelName() != null
                && left.getEmbeddingModelName().equals(right.getEmbeddingModelName());
    }

    private double cosine(List<Double> left, List<Double> right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private String firstMatch(Pattern pattern, String value, String fallback) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1).strip() : fallback;
    }

    private String normalizeText(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    private record ScoredCandidate(
            DocumentAnalysis analysis,
            int score,
            List<String> sharedTopics
    ) {
    }

    private record LegalFinding(String status, String summary, String evidence) {
        private static LegalFinding missing() {
            return new LegalFinding("NOT_FOUND", "원문에서 관련 제한을 확인하지 못했습니다.", "");
        }
    }
    private record ComparisonFields(
            String purpose,
            String supportScale,
            String applicationDeadline,
            String eligibility,
            String requiredPartner
    ) {
    }
}
